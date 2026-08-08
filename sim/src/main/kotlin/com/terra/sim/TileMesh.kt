package com.terra.sim

import com.terra.core.Vec3
import com.terra.core.Vec3d
import com.terra.core.clamp01
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Maillage d'une tuile de terrain — lot B1.
 *
 * ## Ce que cette classe produit
 *
 * Un tampon de sommets prêt pour le GPU, dans le même format entrelacé que le
 * globe (10 flottants : position, couleur, normale, matériau), mais dont les
 * positions sont exprimées **en mètres, relativement au centre de la tuile**.
 *
 * ## Pourquoi des positions relatives
 *
 * En coordonnées monde, le flottant 32 bits ne distingue plus rien en deçà de
 * 50 cm à la surface : le terrain tremblerait dès la descente. Validé par
 * simulation avant écriture : la chaîne « sommet relatif en float32 + décalage
 * (centre − œil) calculé en double à chaque image » borne l'erreur à 0,64 mm
 * au ras du sol, contre 44 cm pour la chaîne naïve. Tout le calcul se fait donc
 * ici en double, et la conversion en float32 n'intervient qu'à la toute fin,
 * une fois la grande composante retranchée.
 *
 * ## Coïncidence des bords
 *
 * Les sommets sont paramétrés par leurs indices de grille **globaux**
 * ([CubeSphere.gridDirection]) : deux tuiles voisines de même niveau calculent
 * leurs sommets partagés à partir d'opérandes identiques, donc obtiennent des
 * positions bit à bit identiques. Entre niveaux différents, les sommets pairs
 * de la tuile fine coïncident de même avec ceux de la grossière ; seuls les
 * sommets impairs s'écartent du bord interpolé — c'est l'écart que couvrent
 * les jupes.
 *
 * ## Jupes
 *
 * Sous chaque bord, un rideau vertical descend de [skirtDepthM]. Profondeur
 * calculée, pas devinée : l'étude numérique (substitut spectral du fbm réel,
 * amplitude majorée) donne un écart maximal de 0,21 % de l'arête entre niveaux
 * adjacents ; on retient 0,5 % (marge ×2,5), plancher 1,5 m, plus 4 m couvrant
 * la différence d'amplitude du détail haute fréquence entre niveaux — qui
 * varie de 4 m par niveau et échappe à l'étude spectrale. Un test mesure
 * l'écart réel sur des paires de tuiles adjacentes et vérifie la couverture.
 *
 * ## Fils d'exécution
 *
 * La construction ne lit que des structures immuables après génération
 * ([TerrainProfile], [CoarseSampler]) : plusieurs tuiles peuvent se mailler en
 * parallèle sur le pool de travail sans aucune synchronisation.
 */
class TileMesh(
    val tile: TileId,
    profile: TerrainProfile,
    sampler: CoarseSampler,
    /** Rayon planétaire en mètres, en double pour toute la chaîne de position. */
    val planetRadiusM: Double
) {

    /** Centre de référence de la tuile, en mètres depuis le centre planétaire. */
    val centerXM: Double
    val centerYM: Double
    val centerZM: Double

    /** Sommets entrelacés, positions relatives au centre, en mètres. */
    val vertexData: FloatArray
    val vertexCount: Int

    val sizeBytes: Int get() = vertexData.size * 4

    init {
        val n = MESH_N
        // Grille ÉTENDUE d'un anneau : les indices vont de −1 à n+1.
        //
        // Cet anneau ne produit aucun triangle ; il sert uniquement à calculer
        // la normale des sommets de bord par différences centrées. Sans lui,
        // ces normales ignoreraient le relief de la tuile voisine et une
        // couture apparaîtrait à chaque bord. Les positions de l'anneau sont
        // calculées par indices GLOBAUX, donc identiques bit à bit à celles de
        // la voisine : la normale est continue par construction.
        val verts = n + 3
        val off = 1

        // --- 1. Grille de positions, calculée une fois en double -----------
        //
        // Le centre de référence est le point de la sphère au niveau de la mer
        // sous le milieu de la tuile : un choix quelconque conviendrait, mais
        // celui-ci borne la norme des positions relatives à une demi-diagonale
        // plus le relief, ce qui maximise la précision du float32 final.
        val baseGx = tile.x * n
        val baseGy = tile.y * n
        val centerDir = CubeSphere.gridDirection(tile.face, tile.level, baseGx + n / 2, baseGy + n / 2, n)
        val cx = centerDir.x * planetRadiusM
        val cy = centerDir.y * planetRadiusM
        val cz = centerDir.z * planetRadiusM
        centerXM = cx; centerYM = cy; centerZM = cz


        // Positions relatives (double), altitudes vraies, directions unitaires.
        val relX = DoubleArray(verts * verts)
        val relY = DoubleArray(verts * verts)
        val relZ = DoubleArray(verts * verts)
        val alt = FloatArray(verts * verts)
        val dirX = FloatArray(verts * verts)
        val dirY = FloatArray(verts * verts)
        val dirZ = FloatArray(verts * verts)
        val colR = FloatArray(verts * verts)
        val colG = FloatArray(verts * verts)
        val colB = FloatArray(verts * verts)
        val mat = FloatArray(verts * verts)

        // Indice de départ pour la marche du CoarseSampler : chaque sommet part
        // de la cellule trouvée pour le précédent, ce qui réduit la recherche à
        // une ou deux étapes au lieu d'une trentaine.
        val shoreBlend = shoreBlendM(tile.level)
        var hint = -1
        val colorHint = intArrayOf(0)
        val rgb = FloatArray(3)
        var idx = 0
        for (j in -1..n + 1) {
            for (i in -1..n + 1) {
                val d = CubeSphere.gridDirection(tile.face, tile.level, baseGx + i, baseGy + j, n)
                val df = d.toVec3()
                val a = profile.renderedAltitudeAt(df)

                // L'eau fait partie du maillage, à rayon constant — décision
                // notée dans l'état du projet ; une surface d'eau dédiée
                // viendra au lot 2.9.
                val renderAlt = max(a, 0f)
                val r = planetRadiusM + renderAlt.toDouble()

                relX[idx] = d.x * r - cx
                relY[idx] = d.y * r - cy
                relZ[idx] = d.z * r - cz
                alt[idx] = a
                dirX[idx] = df.x; dirY[idx] = df.y; dirZ[idx] = df.z

                // Couleur du biome INTERPOLÉE entre les trois sommets du
                // triangle, au lieu d'être copiée du plus proche. Sans cela,
                // chaque cellule de la grille peignait un polygone uni, et la
                // planète apparaissait pavée d'hexagones de 115 km.
                hint = sampler.nearestVertex(df, hint)
                val jitter = if (a > 0f) profile.colorJitterAt(df) else 1f
                sampler.sampleBiomeColor(df, colorHint, rgb)
                colorFor(sampler, hint, df, a, jitter, rgb, shoreBlend,
                    profile.params, colR, colG, colB, idx)

                // Eau douce : le lac reprend la teinte du ciel plus qu'il ne
                // la tire du fond, et s'assombrit avec la profondeur.
                // Eau douce : même physique que la mer, mais le fond est la
                // terre qu'elle recouvre — un lac peu profond laisse voir son
                // herbe, un lac de cratère tire au bleu nuit.
                val lakeDepth = profile.lakeDepthAt(df)
                if (lakeDepth > 0f) {
                    rgb[0] = colR[idx]; rgb[1] = colG[idx]; rgb[2] = colB[idx]
                    val lakeOut = seaScratchTl.get()
                    waterColor(lakeDepth, rgb, lakeOut)
                    colR[idx] = lakeOut[0]; colG[idx] = lakeOut[1]; colB[idx] = lakeOut[2]
                }

                // Matériau : eau de mer près du rivage, ou eau douce dès que
                // le lac atteint un mètre — même fondu de rive dans les deux
                // cas, pour que le reflet meure doucement sur les hauts-fonds.
                val seaness = clamp01((shoreBlend - a) / (2f * shoreBlend))
                val lakeness = clamp01(lakeDepth / 1.5f)
                mat[idx] = max(seaness, lakeness)
                idx++
            }
        }

        // --- 1 bis. Altitude au niveau parent ------------------------------
        //
        // La surface parente est l'interpolation linéaire des sommets d'indice
        // PAIR : c'est exactement le maillage que la tuile parente produirait
        // sur cette zone. Un sommet pair ne bouge donc pas ; un sommet impair
        // rejoint le milieu de ses voisins pairs. Aucune évaluation du terrain
        // n'est nécessaire — tout se lit dans la grille déjà calculée.
        val morphDelta = FloatArray(verts * verts)
        for (j in 0..n) {
            for (i in 0..n) {
                val c = (j + off) * verts + (i + off)
                val iOdd = (i and 1) == 1
                val jOdd = (j and 1) == 1
                val parentAlt = when {
                    !iOdd && !jOdd -> alt[c]
                    iOdd && !jOdd -> (alt[c - 1] + alt[c + 1]) * 0.5f
                    !iOdd && jOdd -> (alt[c - verts] + alt[c + verts]) * 0.5f
                    else -> (alt[c - verts - 1] + alt[c - verts + 1] +
                            alt[c + verts - 1] + alt[c + verts + 1]) * 0.25f
                }
                // L'eau reste plane à toute échelle : morpher son altitude
                // ferait onduler la surface de la mer au gré des bascules.
                morphDelta[c] = if (alt[c] <= 0f) 0f else max(parentAlt, 0f) - max(alt[c], 0f)
            }
        }

        // --- 1 ter. Normales par sommet ------------------------------------
        //
        // Différences centrées sur la grille : la normale d'un sommet vient de
        // ses quatre voisins, pas des facettes qui l'entourent. Deux avantages
        // décisifs : le résultat est continu à travers les bords de tuiles
        // (l'anneau étendu fournit les voisins manquants, aux positions bit à
        // bit identiques à celles de la tuile d'à côté), et il ne dépend pas
        // du découpage en triangles.
        //
        // C'est ce qui remplace l'ombrage par facette : chaque triangle
        // portait sa propre teinte, ce qui se lisait comme un pavage de
        // losanges dès qu'on approchait du sol.
        val nrmX = FloatArray(verts * verts)
        val nrmY = FloatArray(verts * verts)
        val nrmZ = FloatArray(verts * verts)
        for (j in 0..n) {
            for (i in 0..n) {
                val c = (j + off) * verts + (i + off)
                val e = c + 1
                val w = c - 1
                val nn = c + verts
                val ss = c - verts
                val ex = relX[e] - relX[w]; val ey = relY[e] - relY[w]; val ez = relZ[e] - relZ[w]
                val nx2 = relX[nn] - relX[ss]; val ny2 = relY[nn] - relY[ss]; val nz2 = relZ[nn] - relZ[ss]
                var vx = ey * nz2 - ez * ny2
                var vy = ez * nx2 - ex * nz2
                var vz = ex * ny2 - ey * nx2
                // Orientation vérifiée contre la verticale locale, jamais
                // supposée : la parité du paramétrage change d'une face du
                // cube à l'autre, et deux signes qui se compensent ont déjà
                // piégé ce projet.
                if (vx * dirX[c] + vy * dirY[c] + vz * dirZ[c] < 0.0) {
                    vx = -vx; vy = -vy; vz = -vz
                }
                val len = sqrt(vx * vx + vy * vy + vz * vz)
                if (len > 1e-12) {
                    nrmX[c] = (vx / len).toFloat()
                    nrmY[c] = (vy / len).toFloat()
                    nrmZ[c] = (vz / len).toFloat()
                } else {
                    nrmX[c] = dirX[c]; nrmY[c] = dirY[c]; nrmZ[c] = dirZ[c]
                }
            }
        }

        // --- 2. Tampon de sommets ------------------------------------------
        val terrainVerts = n * n * 2 * 3
        val skirtVerts = 4 * n * 2 * 3
        vertexCount = terrainVerts + skirtVerts
        vertexData = FloatArray(vertexCount * FLOATS_PER_VERTEX)
        var o = 0

        // Facettes du terrain. Sommets dupliqués et normale par face : c'est le
        // style low-poly du projet, un lissage l'effacerait.
        for (j in 0 until n) {
            for (i in 0 until n) {
                val v00 = (j + off) * verts + (i + off)
                val v10 = v00 + 1
                val v01 = v00 + verts
                val v11 = v01 + 1
                o = emitTriangle(o, v00, v10, v11, relX, relY, relZ, dirX, dirY, dirZ,
                    nrmX, nrmY, nrmZ, colR, colG, colB, mat, morphDelta)
                o = emitTriangle(o, v00, v11, v01, relX, relY, relZ, dirX, dirY, dirZ,
                    nrmX, nrmY, nrmZ, colR, colG, colB, mat, morphDelta)
            }
        }

        // Jupes : sous chaque bord, un rideau descendu le long de la verticale
        // locale. Normale radiale — le mur est éclairé comme le sol qu'il
        // prolonge, ce qui le rend invisible tant qu'il ne fait que boucher
        // une fissure.
        val depth = skirtDepthM(tile, planetRadiusM)
        // Les bords parcourent la grille UTILE : l'anneau étendu ne sert
        // qu'aux normales et ne doit produire ni triangle ni jupe.
        val corner = off * verts + off
        o = emitSkirtEdge(o, corner, 1, n, depth, relX, relY, relZ, dirX, dirY, dirZ, colR, colG, colB, mat)
        o = emitSkirtEdge(o, corner + n * verts, 1, n, depth, relX, relY, relZ, dirX, dirY, dirZ, colR, colG, colB, mat)
        o = emitSkirtEdge(o, corner, verts, n, depth, relX, relY, relZ, dirX, dirY, dirZ, colR, colG, colB, mat)
        emitSkirtEdge(o, corner + n, verts, n, depth, relX, relY, relZ, dirX, dirY, dirZ, colR, colG, colB, mat)
    }

    /**
     * Émet un triangle avec normale de facette et matériau, en corrigeant
     * l'orientation.
     *
     * ## Pourquoi corriger plutôt que fixer l'ordre une fois pour toutes
     *
     * L'orientation induite par le paramétrage (s, t) diffère d'une face du
     * cube à l'autre : la moitié des faces produirait des triangles vus de dos,
     * éliminés par le `cull face`, et la planète serait trouée. Deux signes qui
     * se compensent ont déjà piégé ce projet une fois (repère v0.6) : plutôt
     * que de déduire la parité de chaque face à la main, on compare la normale
     * à la verticale locale, définition mathématique de « vers l'extérieur »,
     * et l'on échange deux sommets si besoin.
     */
    private fun emitTriangle(
        offset: Int, a: Int, b: Int, c: Int,
        relX: DoubleArray, relY: DoubleArray, relZ: DoubleArray,
        dirX: FloatArray, dirY: FloatArray, dirZ: FloatArray,
        nrmX: FloatArray, nrmY: FloatArray, nrmZ: FloatArray,
        colR: FloatArray, colG: FloatArray, colB: FloatArray, mat: FloatArray,
        morph: FloatArray
    ): Int {
        var i1 = b
        var i2 = c

        // La normale géométrique ne sert QU'À décider du sens de parcours :
        // les normales émises sont celles des sommets, lissées par
        // différences centrées. L'ombrage par facette qu'elle produisait
        // dessinait un pavage de losanges au ras du sol.
        //
        // L'orientation est comparée à la verticale locale plutôt que déduite
        // de la parité de la face du cube : deux signes qui se compensent ont
        // déjà piégé ce projet une fois.
        val ux = relX[i1] - relX[a]; val uy = relY[i1] - relY[a]; val uz = relZ[i1] - relZ[a]
        val wx = relX[i2] - relX[a]; val wy = relY[i2] - relY[a]; val wz = relZ[i2] - relZ[a]
        val nx = uy * wz - uz * wy
        val ny = uz * wx - ux * wz
        val nz = ux * wy - uy * wx
        if (nx * dirX[a] + ny * dirY[a] + nz * dirZ[a] < 0.0) {
            val t = i1; i1 = i2; i2 = t
        }

        // Émission déroulée : une boucle sur un tableau temporaire allouerait
        // 640 objets par tuile, multipliés par des dizaines de tuiles par
        // seconde sur les fils de travail. Le matériau est celui du sommet,
        // interpolé par le GPU : le reflet de l'eau s'éteint en fondu sur la
        // frange littorale au lieu de s'arrêter au bord d'une facette.
        var o = offset
        o = emitVertex(o, relX[a], relY[a], relZ[a], colR[a], colG[a], colB[a],
            nrmX[a], nrmY[a], nrmZ[a], mat[a], morph[a])
        o = emitVertex(o, relX[i1], relY[i1], relZ[i1], colR[i1], colG[i1], colB[i1],
            nrmX[i1], nrmY[i1], nrmZ[i1], mat[i1], morph[i1])
        o = emitVertex(o, relX[i2], relY[i2], relZ[i2], colR[i2], colG[i2], colB[i2],
            nrmX[i2], nrmY[i2], nrmZ[i2], mat[i2], morph[i2])
        return o
    }

    private fun emitVertex(
        offset: Int,
        x: Double, y: Double, z: Double,
        r: Float, g: Float, b: Float,
        nx: Float, ny: Float, nz: Float,
        material: Float,
        /** Écart d'altitude vers le niveau parent. Nul pour les jupes : elles
         *  descendent sous le terrain quel que soit le niveau, et battraient
         *  au rythme des bascules si elles morphaient. */
        morph: Float
    ): Int {
        var o = offset
        vertexData[o++] = x.toFloat()
        vertexData[o++] = y.toFloat()
        vertexData[o++] = z.toFloat()
        vertexData[o++] = r; vertexData[o++] = g; vertexData[o++] = b
        vertexData[o++] = nx; vertexData[o++] = ny; vertexData[o++] = nz
        vertexData[o++] = material
        vertexData[o++] = morph
        return o
    }

    /**
     * Émet la jupe d'un bord : pour chaque segment, un quadrilatère entre le
     * bord et sa copie descendue de [depth] mètres le long de la verticale.
     *
     * ## Orientation
     *
     * Le `cull face` est actif, et une fissure se voit depuis l'**extérieur**
     * de la tuile : le mur doit donc faire face au dehors. Plutôt que de
     * déduire le sens de parcours de la parité de chaque bord et de chaque
     * face du cube — le raisonnement par intuition sur deux signes a déjà
     * piégé ce projet en v0.6 —, on compare la normale de chaque triangle à
     * sa définition : la direction du centre de la tuile vers le bord,
     * débarrassée de sa composante radiale. Si elle pointe vers l'intérieur,
     * on échange deux sommets. Même parade que pour les facettes du terrain.
     */
    private fun emitSkirtEdge(
        offset: Int, start: Int, stride: Int, segments: Int, depth: Double,
        relX: DoubleArray, relY: DoubleArray, relZ: DoubleArray,
        dirX: FloatArray, dirY: FloatArray, dirZ: FloatArray,
        colR: FloatArray, colG: FloatArray, colB: FloatArray, mat: FloatArray
    ): Int {
        var o = offset
        for (k in 0 until segments) {
            val a = start + k * stride
            val b = start + (k + 1) * stride

            // Sommets bas : bord descendu le long de la verticale locale.
            val axL = relX[a] - dirX[a] * depth
            val ayL = relY[a] - dirY[a] * depth
            val azL = relZ[a] - dirZ[a] * depth
            val bxL = relX[b] - dirX[b] * depth
            val byL = relY[b] - dirY[b] * depth
            val bzL = relZ[b] - dirZ[b] * depth

            // Direction « hors de la tuile » : du centre (origine des positions
            // relatives) vers le bord, projetée dans le plan tangent local.
            val radial = relX[a] * dirX[a] + relY[a] * dirY[a] + relZ[a] * dirZ[a]
            val outX = relX[a] - dirX[a] * radial
            val outY = relY[a] - dirY[a] * radial
            val outZ = relZ[a] - dirZ[a] * radial

            o = emitSkirtTriangle(o, relX[a], relY[a], relZ[a], relX[b], relY[b], relZ[b], axL, ayL, azL,
                outX, outY, outZ, dirX[a], dirY[a], dirZ[a], colR[a], colG[a], colB[a], mat[a])
            o = emitSkirtTriangle(o, relX[b], relY[b], relZ[b], bxL, byL, bzL, axL, ayL, azL,
                outX, outY, outZ, dirX[b], dirY[b], dirZ[b], colR[b], colG[b], colB[b], mat[b])
        }
        return o
    }

    private fun emitSkirtTriangle(
        offset: Int,
        x0: Double, y0: Double, z0: Double,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        outX: Double, outY: Double, outZ: Double,
        nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float,
        material: Float
    ): Int {
        // Normale géométrique du triangle, seulement pour tester l'orientation ;
        // la normale d'éclairage émise reste la radiale, qui fond le mur dans
        // le sol qu'il prolonge.
        val e1x = x1 - x0; val e1y = y1 - y0; val e1z = z1 - z0
        val e2x = x2 - x0; val e2y = y2 - y0; val e2z = z2 - z0
        val gnx = e1y * e2z - e1z * e2y
        val gny = e1z * e2x - e1x * e2z
        val gnz = e1x * e2y - e1y * e2x
        val flip = gnx * outX + gny * outY + gnz * outZ < 0.0

        var o = offset
        o = emitVertex(o, x0, y0, z0, r, g, b, nx, ny, nz, material, 0f)
        if (flip) {
            o = emitVertex(o, x2, y2, z2, r, g, b, nx, ny, nz, material, 0f)
            o = emitVertex(o, x1, y1, z1, r, g, b, nx, ny, nz, material, 0f)
        } else {
            o = emitVertex(o, x1, y1, z1, r, g, b, nx, ny, nz, material, 0f)
            o = emitVertex(o, x2, y2, z2, r, g, b, nx, ny, nz, material, 0f)
        }
        return o
    }

    companion object {

        /** Segments par côté : 16, soit une grille 17×17 validée par simulation. */
        const val MESH_N = 16

        const val FLOATS_PER_VERTEX = 11
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4
        const val OFFSET_POSITION = 0
        const val OFFSET_COLOR = 3
        const val OFFSET_NORMAL = 6
        const val OFFSET_MATERIAL = 9

        /**
         * Écart d'altitude vers la géométrie du niveau PARENT, en mètres —
         * lot 2.4.
         *
         * ## Pourquoi une seule valeur suffit
         *
         * Un morphing complet interpolerait la position entière du sommet
         * vers celle qu'il occupe dans la tuile parente, soit trois flottants
         * de plus par sommet : 30 % de mémoire GPU, et la capacité du pool
         * tombant de 328 à 252 tuiles alors que la descente en demande
         * jusqu'à 480.
         *
         * Or ce déplacement est presque purement **radial**. Les sommets
         * d'indice pair coïncident exactement avec ceux du parent ; les
         * impairs se déplacent vers le milieu de la corde parente, dont
         * l'écart tangentiel vaut la sagitta — un micromètre au niveau 14,
         * bien en deçà du pixel. Seule l'altitude change vraiment, et un
         * flottant suffit à la porter.
         */
        const val OFFSET_MORPH = 10

        const val MATERIAL_LAND = 0f
        const val MATERIAL_WATER = 1f

        /** Nombre de sommets émis pour une tuile, terrain plus jupes. */
        fun expectedVertexCount(): Int = MESH_N * MESH_N * 6 + 4 * MESH_N * 6

        /**
         * Profondeur de jupe, en mètres.
         *
         * `max(arête × 0,005 ; 1,5 m) + 4 m` — voir le commentaire de classe
         * pour l'origine de chaque terme. Le tout est validé par un test qui
         * mesure l'écart réel entre tuiles adjacentes de niveaux différents.
         */
        fun skirtDepthM(tile: TileId, planetRadiusM: Double): Double {
            val edgeM = (Math.PI * 0.5 / (1 shl tile.level)) * planetRadiusM
            return max(edgeM * 0.005, 1.5) + 4.0
        }

        /**
         * Position métrique du sommet de grille (i, j) d'une tuile, en double.
         *
         * C'est la définition canonique dont le mailleur et les tests dérivent :
         * la fonction est exposée pour que le test de coïncidence des bords
         * vérifie l'égalité bit à bit entre tuiles voisines.
         */
        fun gridPositionM(
            tile: TileId, i: Int, j: Int,
            profile: TerrainProfile, planetRadiusM: Double
        ): Vec3d {
            val d = CubeSphere.gridDirection(
                tile.face, tile.level, tile.x * MESH_N + i, tile.y * MESH_N + j, MESH_N
            )
            val a = profile.renderedAltitudeAt(d.toVec3())
            return d * (planetRadiusM + max(a, 0f).toDouble())
        }

        /**
         * Couleur de l'eau vue de dessus — lot 2.9.
         *
         * ## Ce qui remplace le dégradé bathymétrique
         *
         * L'eau était peinte d'un simple dégradé du bleu clair au bleu sombre
         * selon la profondeur : lisible, mais rien n'y ressemblait à de
         * l'eau. Le mélange est désormais physique — atténuation de
         * Beer-Lambert, `1 − exp(−k·d)` — entre la couleur du **fond**, qui
         * est celle du biome sous-marin, et celle de la colonne d'eau. Un
         * haut-fond laisse donc transparaître son sable et vire au turquoise,
         * une fosse tend vers le bleu profond, et la transition est continue.
         *
         * Coefficient 0,09 par mètre, calibré sur l'eau de mer claire : le
         * fond reste visible à moitié vers huit mètres, au quart à quinze, et
         * disparaît au-delà de quarante.
         *
         * L'écume s'ajoute là où la houle déferlerait, sous un mètre et demi
         * de fond, et s'évanouit à six mètres : une frange qui suit le trait
         * de côte au lieu de blanchir des kilomètres de littoral.
         */
        private fun waterColor(
            depthM: Float,
            bottomRgb: FloatArray,
            /** Reçoit R, G, B. Un seul tableau plutôt que trois : la couleur
             *  de l'eau sert aussi au mélange de rivage, où une écriture
             *  dispersée serait inutilisable. */
            out: FloatArray
        ) {
            val opacity = 1f - exp(-WATER_EXTINCTION * depthM)

            // Couleur de la colonne d'eau : bleu-vert en surface, bleu nuit
            // en profondeur — la lumière rouge s'éteint la première.
            val deep = clamp01(depthM / 900f)
            val wr = 0.055f + (0.015f - 0.055f) * deep
            val wg = 0.28f + (0.05f - 0.28f) * deep
            val wb = 0.42f + (0.16f - 0.42f) * deep

            var r = bottomRgb[0] * (1f - opacity) + wr * opacity
            var g = bottomRgb[1] * (1f - opacity) + wg * opacity
            var b = bottomRgb[2] * (1f - opacity) + wb * opacity

            val foam = clamp01((FOAM_FADE_M - depthM) / (FOAM_FADE_M - FOAM_FULL_M))
            if (foam > 0f) {
                val f = foam * 0.55f
                r += (0.90f - r) * f
                g += (0.94f - g) * f
                b += (0.96f - b) * f
            }

            out[0] = clamp01(r); out[1] = clamp01(g); out[2] = clamp01(b)
        }

        /**
         * Accès de test à [waterColor] : la formule de mélange est le cœur du
         * rendu de l'eau et mérite d'être vérifiée directement, plutôt qu'à
         * travers un maillage complet.
         */
        internal fun waterColorForTest(depthM: Float, bottomRgb: FloatArray, out: FloatArray) {
            waterColor(depthM, bottomRgb, out)
        }

        /**
         * Tampons de travail du calcul de couleur, un jeu par fil.
         *
         * Allouer trois flottants par sommet ferait un demi-million
         * d'allocations par seconde pendant une descente — exactement ce que
         * le lot B0 s'était employé à supprimer. Un état par fil ne partage
         * rien et ne coûte rien.
         */
        private val seaScratchTl = ThreadLocal.withInitial { FloatArray(3) }
        private val bottomScratchTl = ThreadLocal.withInitial { FloatArray(3) }

        /**
         * Demi-largeur de la frange de mélange terre / eau, en mètres, pour
         * un niveau de tuile donné — lot 2.9b.
         *
         * ## L'escalier que cela corrige
         *
         * Tout le rendu s'interpole désormais — couleurs, normales,
         * altitudes —, sauf le passage terre/eau, qui basculait sur un seuil
         * d'altitude. Vu de loin, une maille couvre des kilomètres et le
         * terrain y franchit le niveau de la mer d'un coup : la côte se
         * dessinait en marches d'escalier, très visible en vue orbitale.
         *
         * La frange doit couvrir la variation d'altitude d'une maille, faute
         * de quoi la transition retombe dans une seule maille et redevient
         * une marche. Recalibrée en v0.19.2 sur la pente côtière RÉELLE des
         * mondes générés (~30 %, socle isostatique franchi en une à deux
         * mailles), avec saturation à 1 400 m : 1,4 km aux niveaux 2 à 7,
         * 733 m au niveau 8, 46 m au niveau 12, plancher de deux mètres à
         * partir du niveau 16 — de près, le rivage reste franc, c'est là
         * qu'on voit une plage.
         */
        fun shoreBlendM(level: Int): Float {
            val edge = (Math.PI * 0.5 / (1 shl level)).toFloat() * 6_371_000f
            // Pente de 30 % — les côtes générées franchissent le socle
            // isostatique (+200 / −900 m) en une ou deux mailles, pas les
            // 4 % du calibrage initial : c'est ce qui redonnait des dents
            // de scie aux niveaux 5 à 8 (constaté sur appareil, v0.19.1).
            // Saturation à 1 400 m : au-delà d'une certaine maille, le
            // dénivelé côtier PAR MAILLE cesse de croître — il est borné
            // par le relief côtier total — et une frange linéaire aurait
            // noyé les plateaux continentaux entiers en turquoise.
            return max(2f, kotlin.math.min(edge / MESH_N * 0.30f, 1_400f))
        }

        /** Extinction lumineuse dans l'eau, par mètre. */
        const val WATER_EXTINCTION = 0.09f

        /** Écume pleine sous cette profondeur de fond, en mètres. */
        const val FOAM_FULL_M = 1.5f

        /** Écume évanouie au-delà, en mètres. */
        const val FOAM_FADE_M = 6f

        /**
         * Couleur d'un sommet de tuile.
         *
         * Terres : couleur du biome de la cellule grossière, modulée par
         * l'altitude fine pour que le relief se lise même à biome constant.
         * Mers : dégradé bathymétrique piloté par la profondeur fine — le
         * biome grossier ne distingue pas la fosse du haut-fond à l'échelle
         * d'une tuile côtière.
         */
        private fun colorFor(
            sampler: CoarseSampler, vertexIndex: Int, dir: Vec3, altitudeM: Float,
            jitter: Float,
            /** Couleur de biome déjà interpolée : R, G, B. */
            rgb: FloatArray,
            /** Demi-largeur de la frange de rivage, en mètres. */
            shoreBlend: Float,
            params: PlanetParams,
            outR: FloatArray, outG: FloatArray, outB: FloatArray, idx: Int
        ) {
            val biome = sampler.biomeAt(dir, vertexIndex)
            val sea = seaScratchTl.get()

            // La banquise couvre l'eau : ni bathymétrie ni frange.
            if (biome == Biome.SEA_ICE) {
                outR[idx] = biome.r; outG[idx] = biome.g; outB[idx] = biome.b
                return
            }

            // Eau franche, au-delà de la frange.
            if (altitudeM <= -shoreBlend) {
                waterColor(-altitudeM, rgb, sea)
                outR[idx] = sea[0]; outG[idx] = sea[1]; outB[idx] = sea[2]
                return
            }

            // Terre, teintée par l'altitude et mouchetée.
            val tint = (0.88f + 0.24f * clamp01(altitudeM / params.maxAltitudeM)) * jitter
            var r = clamp01(rgb[0] * tint)
            var g = clamp01(rgb[1] * tint)
            var b = clamp01(rgb[2] * tint)

            // Frange de rivage — lot 2.9b.
            //
            // Le passage terre/eau était le dernier basculement par seuil
            // d'un rendu devenu partout continu : vu de loin, une maille
            // couvre des kilomètres, le terrain y franchissait le niveau de
            // la mer d'un coup, et la côte se dessinait en marches
            // d'escalier. Le mélange s'étale désormais sur une frange large
            // de plusieurs mailles en orbite et de deux mètres au sol — assez
            // pour effacer l'escalier de loin, assez fine pour qu'une plage
            // reste une plage de près.
            if (altitudeM < shoreBlend) {
                val wet = clamp01((shoreBlend - altitudeM) / (2f * shoreBlend))
                val bottom = bottomScratchTl.get()
                bottom[0] = r; bottom[1] = g; bottom[2] = b
                waterColor(max(0.2f, shoreBlend - altitudeM), bottom, sea)
                r += (sea[0] - r) * wet
                g += (sea[1] - g) * wet
                b += (sea[2] - b) * wet
            }

            outR[idx] = clamp01(r); outG[idx] = clamp01(g); outB[idx] = clamp01(b)
        }
    }
}
