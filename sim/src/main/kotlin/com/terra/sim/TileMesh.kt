package com.terra.sim

import com.terra.core.Vec3
import com.terra.core.Vec3d
import com.terra.core.clamp01
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
        val verts = n + 1

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

        // Indice de départ pour la marche du CoarseSampler : chaque sommet part
        // de la cellule trouvée pour le précédent, ce qui réduit la recherche à
        // une ou deux étapes au lieu d'une trentaine.
        var hint = -1
        var idx = 0
        for (j in 0..n) {
            for (i in 0..n) {
                val d = CubeSphere.gridDirection(tile.face, tile.level, baseGx + i, baseGy + j, n)
                val df = d.toVec3()
                val a = profile.renderedAltitudeAt(df, tile.level)

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

                hint = sampler.nearestVertex(df, hint)
                val jitter = if (a > 0f) profile.colorJitterAt(df) else 1f
                colorFor(sampler, hint, df, a, jitter, profile.params, colR, colG, colB, idx)
                idx++
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
                val v00 = j * verts + i
                val v10 = j * verts + i + 1
                val v01 = (j + 1) * verts + i
                val v11 = (j + 1) * verts + i + 1
                o = emitTriangle(o, v00, v10, v11, relX, relY, relZ, alt, dirX, dirY, dirZ, colR, colG, colB)
                o = emitTriangle(o, v00, v11, v01, relX, relY, relZ, alt, dirX, dirY, dirZ, colR, colG, colB)
            }
        }

        // Jupes : sous chaque bord, un rideau descendu le long de la verticale
        // locale. Normale radiale — le mur est éclairé comme le sol qu'il
        // prolonge, ce qui le rend invisible tant qu'il ne fait que boucher
        // une fissure.
        val depth = skirtDepthM(tile, planetRadiusM)
        o = emitSkirtEdge(o, 0, 1, verts, depth, relX, relY, relZ, alt, dirX, dirY, dirZ, colR, colG, colB)                    // bord t=0
        o = emitSkirtEdge(o, (verts - 1) * verts, 1, verts, depth, relX, relY, relZ, alt, dirX, dirY, dirZ, colR, colG, colB) // bord t=1
        o = emitSkirtEdge(o, 0, verts, verts, depth, relX, relY, relZ, alt, dirX, dirY, dirZ, colR, colG, colB)               // bord s=0
        o = emitSkirtEdge(o, verts - 1, verts, verts, depth, relX, relY, relZ, alt, dirX, dirY, dirZ, colR, colG, colB)       // bord s=1
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
        relX: DoubleArray, relY: DoubleArray, relZ: DoubleArray, alt: FloatArray,
        dirX: FloatArray, dirY: FloatArray, dirZ: FloatArray,
        colR: FloatArray, colG: FloatArray, colB: FloatArray
    ): Int {
        var i1 = b
        var i2 = c

        val ux = relX[i1] - relX[a]; val uy = relY[i1] - relY[a]; val uz = relZ[i1] - relZ[a]
        val wx = relX[i2] - relX[a]; val wy = relY[i2] - relY[a]; val wz = relZ[i2] - relZ[a]
        var nx = uy * wz - uz * wy
        var ny = uz * wx - ux * wz
        var nz = ux * wy - uy * wx

        val outward = nx * dirX[a] + ny * dirY[a] + nz * dirZ[a]
        if (outward < 0.0) {
            val t = i1; i1 = i2; i2 = t
            nx = -nx; ny = -ny; nz = -nz
        }
        val nl = sqrt(nx * nx + ny * ny + nz * nz)
        val fnx: Float; val fny: Float; val fnz: Float
        if (nl > 1e-12) {
            fnx = (nx / nl).toFloat(); fny = (ny / nl).toFloat(); fnz = (nz / nl).toFloat()
        } else {
            fnx = dirX[a]; fny = dirY[a]; fnz = dirZ[a]
        }

        // Une facette est aquatique seulement si ses trois sommets le sont : le
        // trait de côte reste net au lieu de baver sur la mer.
        val water = alt[a] <= 0f && alt[i1] <= 0f && alt[i2] <= 0f
        val material = if (water) MATERIAL_WATER else MATERIAL_LAND

        // Émission déroulée : une boucle sur un tableau temporaire allouerait
        // 640 objets par tuile, multipliés par des dizaines de tuiles par
        // seconde sur les fils de travail.
        var o = offset
        o = emitVertex(o, relX[a], relY[a], relZ[a], colR[a], colG[a], colB[a], fnx, fny, fnz, material)
        o = emitVertex(o, relX[i1], relY[i1], relZ[i1], colR[i1], colG[i1], colB[i1], fnx, fny, fnz, material)
        o = emitVertex(o, relX[i2], relY[i2], relZ[i2], colR[i2], colG[i2], colB[i2], fnx, fny, fnz, material)
        return o
    }

    private fun emitVertex(
        offset: Int,
        x: Double, y: Double, z: Double,
        r: Float, g: Float, b: Float,
        nx: Float, ny: Float, nz: Float,
        material: Float
    ): Int {
        var o = offset
        vertexData[o++] = x.toFloat()
        vertexData[o++] = y.toFloat()
        vertexData[o++] = z.toFloat()
        vertexData[o++] = r; vertexData[o++] = g; vertexData[o++] = b
        vertexData[o++] = nx; vertexData[o++] = ny; vertexData[o++] = nz
        vertexData[o++] = material
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
        offset: Int, start: Int, stride: Int, verts: Int, depth: Double,
        relX: DoubleArray, relY: DoubleArray, relZ: DoubleArray, alt: FloatArray,
        dirX: FloatArray, dirY: FloatArray, dirZ: FloatArray,
        colR: FloatArray, colG: FloatArray, colB: FloatArray
    ): Int {
        var o = offset
        val n = verts - 1
        for (k in 0 until n) {
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

            val water = alt[a] <= 0f && alt[b] <= 0f
            val material = if (water) MATERIAL_WATER else MATERIAL_LAND

            o = emitSkirtTriangle(o, relX[a], relY[a], relZ[a], relX[b], relY[b], relZ[b], axL, ayL, azL,
                outX, outY, outZ, dirX[a], dirY[a], dirZ[a], colR[a], colG[a], colB[a], material)
            o = emitSkirtTriangle(o, relX[b], relY[b], relZ[b], bxL, byL, bzL, axL, ayL, azL,
                outX, outY, outZ, dirX[b], dirY[b], dirZ[b], colR[b], colG[b], colB[b], material)
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
        o = emitVertex(o, x0, y0, z0, r, g, b, nx, ny, nz, material)
        if (flip) {
            o = emitVertex(o, x2, y2, z2, r, g, b, nx, ny, nz, material)
            o = emitVertex(o, x1, y1, z1, r, g, b, nx, ny, nz, material)
        } else {
            o = emitVertex(o, x1, y1, z1, r, g, b, nx, ny, nz, material)
            o = emitVertex(o, x2, y2, z2, r, g, b, nx, ny, nz, material)
        }
        return o
    }

    companion object {

        /** Segments par côté : 16, soit une grille 17×17 validée par simulation. */
        const val MESH_N = 16

        const val FLOATS_PER_VERTEX = 10
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4
        const val OFFSET_POSITION = 0
        const val OFFSET_COLOR = 3
        const val OFFSET_NORMAL = 6
        const val OFFSET_MATERIAL = 9

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
            val a = profile.renderedAltitudeAt(d.toVec3(), tile.level)
            return d * (planetRadiusM + max(a, 0f).toDouble())
        }

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
            params: PlanetParams,
            outR: FloatArray, outG: FloatArray, outB: FloatArray, idx: Int
        ) {
            val biome = sampler.biomeAt(dir, vertexIndex)
            if (altitudeM < 0f) {
                val t = clamp01(-altitudeM / params.maxDepthM)
                if (biome == Biome.SEA_ICE) {
                    outR[idx] = biome.r; outG[idx] = biome.g; outB[idx] = biome.b
                } else {
                    outR[idx] = 0.11f + (0.035f - 0.11f) * t
                    outG[idx] = 0.36f + (0.085f - 0.36f) * t
                    outB[idx] = 0.56f + (0.240f - 0.56f) * t
                }
            } else {
                val tint = (0.88f + 0.24f * clamp01(altitudeM / params.maxAltitudeM)) * jitter
                outR[idx] = clamp01(biome.r * tint)
                outG[idx] = clamp01(biome.g * tint)
                outB[idx] = clamp01(biome.b * tint)
            }
        }
    }
}
