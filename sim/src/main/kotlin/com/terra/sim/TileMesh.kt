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

    /**
     * Couche d'eau (lot 2.9-a) : sommets à 5 flottants (position relative,
     * profondeur, morph de profondeur), téléversés dans le MÊME VBO à la
     * suite du terrain. Vide pour une tuile sans mer.
     */
    val waterData: FloatArray
    val waterVertexCount: Int

    val sizeBytes: Int get() = (vertexData.size + waterData.size) * 4

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
        val ao = FloatArray(verts * verts) { 1f }
        // Altitude RENDUE (vraie sous la mer, écrêtée sur la banquise) et
        // profondeur d'eau par sommet — lot 2.9-a.
        val rAlt = FloatArray(verts * verts)
        val wDepth = FloatArray(verts * verts)
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
        val tintScratch = FloatArray(3)
        var idx = 0
        for (j in -1..n + 1) {
            for (i in -1..n + 1) {
                val d = CubeSphere.gridDirection(tile.face, tile.level, baseGx + i, baseGy + j, n)
                val df = d.toVec3()
                val a = profile.renderedAltitudeAt(df)

                // Lot 2.9-a : le fond marin EXISTE. Le terrain est rendu à
                // son altitude vraie sous la mer ; la surface de l'eau est
                // une couche séparée émise en fin de construction. Seule la
                // banquise reste écrêtée au niveau de la mer : c'est une
                // surface SOLIDE posée sur l'eau, pas une colonne d'eau —
                // l'écrêter EST son rendu, et elle n'émet pas d'eau dessous.
                hint = sampler.nearestVertex(df, hint)
                val iced = sampler.biomeAt(df, hint) == Biome.SEA_ICE
                val renderAlt = if (a < 0f && !iced) a else max(a, 0f)
                rAlt[idx] = renderAlt
                wDepth[idx] = if (iced) 0f else max(0f, -a)
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
                sampler.sampleBiomeColor(df, colorHint, rgb)
                // Teinte de sol (lot 2.17 a) : appliquée à la couleur de
                // biome AVANT le rivage, pour que les hauts-fonds héritent
                // d'un fond varié comme la terre émergée.
                if (a > -shoreBlend) {
                    profile.groundTintAt(df, tintScratch)
                    rgb[0] *= tintScratch[0]
                    rgb[1] *= tintScratch[1]
                    rgb[2] *= tintScratch[2]
                }
                val jitter = 1f   // le modelé vit désormais dans groundTintAt
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

                // Matériau (lot 2.9-a) : la mer n'est plus un matériau du
                // terrain — le terrain y est le FOND, mat = 0, et la houle,
                // le Fresnel et l'écume du shader de tuile s'y éteignent
                // d'eux-mêmes, sans toucher au shader. La banquise garde son
                // matériau d'eau (le reflet de la glace) ; les lacs restent
                // strictement à l'identique jusqu'au lot 2.9-c.
                val seaness = if (iced) clamp01((shoreBlend - a) / (2f * shoreBlend)) else 0f
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
                    !iOdd && !jOdd -> rAlt[c]
                    iOdd && !jOdd -> (rAlt[c - 1] + rAlt[c + 1]) * 0.5f
                    !iOdd && jOdd -> (rAlt[c - verts] + rAlt[c + verts]) * 0.5f
                    else -> (rAlt[c - verts - 1] + rAlt[c - verts + 1] +
                            rAlt[c + verts - 1] + rAlt[c + verts + 1]) * 0.25f
                }
                // Lot 2.9-a : le cas spécial « la mer reste plane » est mort
                // avec la surface de mer du terrain — le fond marin morphe
                // comme n'importe quel relief. La banquise, écrêtée à zéro
                // dans rAlt, a un parent à zéro : plate par construction.
                morphDelta[c] = parentAlt - rAlt[c]
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

        // --- 1 ter bis. Occlusion ambiante (lot 2.13) -----------------------
        //
        // Un fond de vallon reçoit moins de lumière du ciel qu'une crête :
        // le relief alentour lui en cache une partie. Mesure locale sans
        // lancer de rayon — la CONCAVITÉ, altitude du sommet comparée à la
        // moyenne de ses quatre voisins de grille.
        //
        // La normalisation est le point délicat, et la v0.28.0 s'y était
        // trompée : elle divisait la concavité par la LONGUEUR de la maille
        // (des centaines de mètres) quand la concavité est une VARIATION
        // D'ALTITUDE (quelques mètres). Deux ordres de grandeur d'écart,
        // 0,4 % d'assombrissement — invisible à l'œil comme au test, qui a
        // mis trois tours à le prouver. Une grandeur ne se normalise que
        // par une grandeur de MÊME DIMENSION.
        //
        // La référence est donc la RUGOSITÉ MESURÉE de la tuile : la moyenne
        // de ses propres |concavités|. Auto-normalisée, comme les quartiles
        // du test — aucune constante métrique, donc aucune à se tromper, et
        // l'effet garde la même force à tous les niveaux du quadtree comme
        // sur tous les reliefs.
        // Pas de grille en mètres : le dénominateur qui rend la mesure
        // adimensionnée. La v0.29.3 normalisait par la rugosité MOYENNE DE
        // LA TUILE — juste en amplitude, mais discontinue : une tuile de
        // dunes et sa voisine de plaine assombrissaient différemment le
        // même relief à leur frontière commune, d'où des coutures
        // diagonales franches (constatées sur appareil).
        //
        // concavité / pas est une COURBURE : sans dimension, elle ne dépend
        // que du terrain et de l'échelle d'échantillonnage, jamais du
        // contenu de la tuile. Deux tuiles voisines de même niveau la
        // calculent donc identique sur leur bord partagé — la continuité
        // est structurelle, pas obtenue par réglage. Et un relief
        // autosimilaire donne la même courbure à tous les niveaux : l'effet
        // ne varie pas avec le zoom.
        val stepM = ((tile.s1 - tile.s0).toDouble() * planetRadiusM / n).toFloat()
            .coerceAtLeast(0.01f)
        for (j in 0..n) {
            for (i in 0..n) {
                val c = (j + off) * verts + (i + off)
                if (mat[c] > 0.4f) { ao[c] = 1f; continue }
                val cw = alt[c - 1]
                val ce = alt[c + 1]
                val cs = alt[c - verts]
                val cn = alt[c + verts]
                val curvature = ((cw + ce + cs + cn) * 0.25f - alt[c]) / stepM
                // Gain 12 : une plaine lisse s'assombrit de 1 %, des dunes
                // de 38 % — la borne haute, atteinte quand la courbure vaut
                // 3 % (validation Python). Un creux garde 62 % de la
                // lumière du ciel, une crête en gagne 8 %.
                ao[c] = (1f - (curvature * AO_GAIN).coerceIn(-0.08f, 0.38f))
                    .coerceIn(0.62f, 1.08f)
            }
        }

        // --- 1 quater. Roche des pentes (lot 2.17 a) ------------------------
        //
        // Au-delà d'une pente de repos, l'herbe et la terre ne tiennent
        // plus : la couleur glisse vers la roche. Le seuil est un ANGLE, lu
        // dans la normale par sommet déjà calculée — pas une constante
        // d'altitude : un talus de vallée rocheux l'est autant qu'une paroi
        // de montagne. Aux niveaux grossiers, la normale est lissée sur des
        // kilomètres et les pentes s'effacent d'elles-mêmes : l'orbite garde
        // les couleurs de biome pures, le premier plan gagne ses
        // affleurements — l'échelle fait le travail, aucun seuil de niveau.
        for (j in 0..n) {
            for (i in 0..n) {
                val c = (j + off) * verts + (i + off)
                if (alt[c] <= 0f || mat[c] > 0.4f) continue
                val cosUp = nrmX[c] * dirX[c] + nrmY[c] * dirY[c] + nrmZ[c] * dirZ[c]
                val rock = rockBlend(cosUp)
                if (rock <= 0f) continue
                colR[c] += (ROCK_R - colR[c]) * rock
                colG[c] += (ROCK_G - colG[c]) * rock
                colB[c] += (ROCK_B - colB[c]) * rock
            }
        }

        // Lissage 3×3 du champ d'occlusion, AVANT application.
        //
        // Le laplacien à quatre voisins amplifie d'un facteur 2 le motif
        // alterné d'une maille sur deux — précisément la fréquence où vit
        // le grain du micro-relief. Il en résultait un QUADRILLAGE régulier
        // sur le terrain, aligné sur la grille (constaté sur appareil,
        // v0.31.2) : un repliement de spectre, pas du relief.
        //
        // La moyenne 3×3 annule ce damier (gain ~1/9) tout en préservant
        // une vraie vallée, large de plusieurs mailles (gain ~1) : c'est
        // exactement la séparation d'échelles recherchée. Un tampon séparé
        // évite de propager les valeurs déjà lissées.
        val aoSmooth = FloatArray(verts * verts) { 1f }
        for (j in 0..n) {
            for (i in 0..n) {
                val c = (j + off) * verts + (i + off)
                var sum = 0f
                var count = 0
                for (dj in -1..1) {
                    for (di in -1..1) {
                        val k = c + dj * verts + di
                        if (k < 0 || k >= ao.size) continue
                        sum += ao[k]
                        count++
                    }
                }
                aoSmooth[c] = if (count > 0) sum / count else ao[c]
            }
        }

        // Application aux couleurs : pas d'attribut nouveau, donc format de
        // sommet, taille de tampon et pool GPU inchangés — l'ombrage est
        // cuit dans l'albédo, ce qui convient à une lumière AMBIANTE (elle
        // ne dépend pas de la position du soleil, seulement du relief).
        for (j in 0..n) {
            for (i in 0..n) {
                val c = (j + off) * verts + (i + off)
                val k = aoSmooth[c]
                if (k == 1f) continue
                colR[c] = clamp01(colR[c] * k)
                colG[c] = clamp01(colG[c] * k)
                colB[c] = clamp01(colB[c] * k)
            }
        }

        // --- 2. Tampon de sommets ------------------------------------------
        val terrainVerts = n * n * 2 * 3
        val skirtVerts = 4 * n * 2 * 3
        vertexCount = terrainVerts + skirtVerts + PLANT_SLOTS * VERTS_PER_PLANT
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
        o = emitSkirtEdge(o, corner + n, verts, n, depth, relX, relY, relZ, dirX, dirY, dirZ, colR, colG, colB, mat)

        // --- 3. Végétation (lot 3-avancé) -----------------------------------
        //
        // Chaque plante est deux quads « cerf-volant » croisés — pied au
        // sol, ventre à mi-hauteur, pointe au sommet — émis sur leurs deux
        // faces, l'élagage arrière étant actif. Pas de texture dans ce
        // moteur : la silhouette et le dégradé de couleur (pied brun,
        // houppier du biome assombri) font l'arbre. Le pied est posé par
        // renderedAltitudeAt : la plante touche EXACTEMENT le terrain rendu,
        // par l'invariant n°3. Voir PLANT_LATTICE_LEVEL pour le treillis.
        emitPlants(o, tile, profile, sampler, planetRadiusM, alt, verts, off)

        // --- 4. Couche d'eau calquée (lot 2.9-a) ---------------------------
        //
        // Mêmes cellules de grille que le terrain : la validation
        // (eau_transparence.py) montre que la PROFONDEUR l'exige — avec la
        // pente côtière réelle de 30 %, une eau plus grossière balaierait
        // des dizaines de mètres de profondeur par maille, et tout le
        // dégradé littoral tiendrait dans un triangle. Seules les cellules
        // dont au moins un coin est en eau émettent leurs deux triangles :
        // une tuile continentale ne paie rien.
        //
        // Surface au rayon de la mer + 5 cm : le biais écarte le z-fighting
        // du rivage (bande d'affleurement < 2 m à pente 30 %), invisible
        // sous une houle de ±0,66 m. Pas de jupes : à rayon constant,
        // l'écart entre niveaux voisins est la sagitta de la maille
        // grossière — 12 cm au niveau 8, vus de plus de 100 km : moins d'un
        // pixel partout.
        //
        // Le canal de morph porte l'écart de PROFONDEUR vers le parent : la
        // position, à rayon constant, n'a rien à morpher, mais la couleur
        // (fonction de la profondeur) doit basculer aussi continûment que
        // le terrain qu'elle recouvre.
        var waterCells = 0
        for (j in 0 until n) {
            for (i in 0 until n) {
                val c = (j + off) * verts + (i + off)
                if (wDepth[c] > 0f || wDepth[c + 1] > 0f ||
                    wDepth[c + verts] > 0f || wDepth[c + verts + 1] > 0f) waterCells++
            }
        }
        waterVertexCount = waterCells * 6
        waterData = FloatArray(waterVertexCount * WATER_FLOATS_PER_VERTEX)
        if (waterCells > 0) {
            // Profondeur au niveau parent : même règle d'interpolation que
            // l'altitude — sommets pairs inchangés, impairs au milieu.
            val depthMorph = FloatArray(verts * verts)
            for (j in 0..n) {
                for (i in 0..n) {
                    val c = (j + off) * verts + (i + off)
                    val iOdd = (i and 1) == 1
                    val jOdd = (j and 1) == 1
                    val parentDepth = when {
                        !iOdd && !jOdd -> wDepth[c]
                        iOdd && !jOdd -> (wDepth[c - 1] + wDepth[c + 1]) * 0.5f
                        !iOdd && jOdd -> (wDepth[c - verts] + wDepth[c + verts]) * 0.5f
                        else -> (wDepth[c - verts - 1] + wDepth[c - verts + 1] +
                                wDepth[c + verts - 1] + wDepth[c + verts + 1]) * 0.25f
                    }
                    depthMorph[c] = parentDepth - wDepth[c]
                }
            }
            val rw = planetRadiusM + WATER_SURFACE_BIAS_M
            var wo = 0
            for (j in 0 until n) {
                for (i in 0 until n) {
                    val v00 = (j + off) * verts + (i + off)
                    val v10 = v00 + 1
                    val v01 = v00 + verts
                    val v11 = v01 + 1
                    if (wDepth[v00] <= 0f && wDepth[v10] <= 0f &&
                        wDepth[v01] <= 0f && wDepth[v11] <= 0f) continue
                    // Même ordre de parcours que les facettes du terrain :
                    // même orientation, même élagage arrière.
                    wo = emitWaterVertex(wo, v00, rw, cx, cy, cz, dirX, dirY, dirZ, wDepth, depthMorph)
                    wo = emitWaterVertex(wo, v10, rw, cx, cy, cz, dirX, dirY, dirZ, wDepth, depthMorph)
                    wo = emitWaterVertex(wo, v11, rw, cx, cy, cz, dirX, dirY, dirZ, wDepth, depthMorph)
                    wo = emitWaterVertex(wo, v00, rw, cx, cy, cz, dirX, dirY, dirZ, wDepth, depthMorph)
                    wo = emitWaterVertex(wo, v11, rw, cx, cy, cz, dirX, dirY, dirZ, wDepth, depthMorph)
                    wo = emitWaterVertex(wo, v01, rw, cx, cy, cz, dirX, dirY, dirZ, wDepth, depthMorph)
                }
            }
        }
    }

    /** Écrit un sommet d'eau : position au rayon de la mer, profondeur, morph. */
    private fun emitWaterVertex(
        o: Int, v: Int, rw: Double,
        cx: Double, cy: Double, cz: Double,
        dirX: FloatArray, dirY: FloatArray, dirZ: FloatArray,
        wDepth: FloatArray, depthMorph: FloatArray
    ): Int {
        waterData[o] = (dirX[v] * rw - cx).toFloat()
        waterData[o + 1] = (dirY[v] * rw - cy).toFloat()
        waterData[o + 2] = (dirZ[v] * rw - cz).toFloat()
        waterData[o + 3] = wDepth[v]
        waterData[o + 4] = depthMorph[v]
        return o + WATER_FLOATS_PER_VERTEX
    }

    private fun emitPlants(
        startOffset: Int,
        tile: TileId,
        profile: TerrainProfile,
        sampler: CoarseSampler,
        planetRadiusM: Double,
        alt: FloatArray, verts: Int, gridOff: Int
    ) {
        var o = startOffset
        if (tile.level < PLANT_MIN_LEVEL) return

        // Emprise de la tuile sur le treillis canonique, en cases.
        val lat = PLANT_LATTICE_LEVEL
        val cellsPerFace = (PLANT_LATTICE_N.toLong() shl lat).toDouble()
        val tileSpan = 1.0 / (1L shl tile.level).toDouble()      // fraction de face
        val x0 = tile.x * tileSpan * cellsPerFace
        val y0 = tile.y * tileSpan * cellsPerFace
        val x1 = (tile.x + 1) * tileSpan * cellsPerFace
        val y1 = (tile.y + 1) * tileSpan * cellsPerFace
        // Pas de 2 par axe au niveau 14 (196 cases pour 49 places) ;
        // pas de 1 partout ailleurs.
        val stride = if (tile.level < lat) 1 shl (lat - tile.level) else 1
        val hint = intArrayOf(0)
        var emitted = 0
        val cxEnd = Math.ceil(x1).toLong()
        val cyEnd = Math.ceil(y1).toLong()
        var cy = Math.floor(y0).toLong()
        while (cy < cyEnd && emitted < PLANT_SLOTS) {
            var cx = Math.floor(x0).toLong()
            while (cx < cxEnd && emitted < PLANT_SLOTS) {
                if ((cx % stride == 0L) && (cy % stride == 0L)) {
                    val next = emitOnePlant(o, tile, cx, cy, x0, x1, y0, y1,
                        profile, sampler, hint, planetRadiusM, alt, verts, gridOff)
                    if (next != o) emitted++
                    o = next
                }
                cx++
            }
            cy++
        }
    }

    private fun emitOnePlant(
        startOffset: Int,
        tile: TileId,
        cellX: Long, cellY: Long,
        x0: Double, x1: Double, y0: Double, y1: Double,
        profile: TerrainProfile,
        sampler: CoarseSampler,
        hint: IntArray,
        planetRadiusM: Double,
        alt: FloatArray, verts: Int, gridOff: Int
    ): Int {
        val o = startOffset
        // Les sels de hachage viennent de la CASE canonique : la même
        // plante renaît identique dans toute tuile qui la contient.
        val sx = (tile.face.toLong() * 0x9E3779B1L + cellX * 0x85EBCA77L +
            cellY * 0xC2B2AE3DL).toInt()
        val ju = profile.micro01(sx * 31 + 1)
        val jv = profile.micro01(sx * 31 + 2)
        val px = cellX + 0.15 + 0.70 * ju
        val py = cellY + 0.15 + 0.70 * jv
        // La plante n'appartient qu'à la tuile qui contient sa POSITION :
        // ni doublon ni trou aux frontières.
        if (px < x0 || px >= x1 || py < y0 || py >= y1) return o

        val d = CubeSphere.gridDirectionF(
            tile.face, PLANT_LATTICE_LEVEL, px.toFloat(), py.toFloat(), PLANT_LATTICE_N
        )
        // Le pied se pose sur la SURFACE DE LA TUILE — interpolation
        // bilinéaire de sa propre grille d'altitudes — et non sur le
        // terrain continu exact : à distance, le sol dessiné est une tuile
        // grossière qui s'écarte du terrain exact de plusieurs mètres entre
        // ses nœuds, et une plante posée sur l'exact FLOTTE au-dessus du
        // visible (constaté sur appareil, v0.26.1). La plante appartient à
        // sa tuile ; quand la tuile change de niveau, son pied suit la
        // surface — le même saut que le sol sous elle, donc invisible.
        val n = MESH_N
        val gu = (((px - x0) / (x1 - x0)) * n).toFloat().coerceIn(0f, n.toFloat())
        val gv = (((py - y0) / (y1 - y0)) * n).toFloat().coerceIn(0f, n.toFloat())
        val i0 = gu.toInt().coerceAtMost(n - 1)
        val j0 = gv.toInt().coerceAtMost(n - 1)
        val fu = gu - i0
        val fv = gv - j0
        val c00 = (j0 + gridOff) * verts + (i0 + gridOff)
        val a = (alt[c00] * (1f - fu) + alt[c00 + 1] * fu) * (1f - fv) +
            (alt[c00 + verts] * (1f - fu) + alt[c00 + verts + 1] * fu) * fv
        if (a <= 0f || profile.lakeDepthAt(d) > 0f) return o

        val near = sampler.nearestVertex(d, hint[0]); hint[0] = near
        val biome = sampler.biomeAt(d, near)
        val density = plantDensity(biome)
        if (density <= 0f) return o
        if (profile.micro01(sx * 31 + 3) > density) return o

        // Pente : au-delà de ~27 %, ni arbre ni touffe — mesurée sur le
        // terrain rendu à deux mètres d'écart, comme la roche des pentes.
        val stepRad = (2.0 / planetRadiusM).toFloat()
        val east = eastOf(d)
        val north = northOf(d, east)
        val aFine = profile.renderedAltitudeAt(d)
        val aE = profile.renderedAltitudeAt(com.terra.core.Vec3(
            d.x + east.x * stepRad, d.y + east.y * stepRad, d.z + east.z * stepRad))
        val aN = profile.renderedAltitudeAt(com.terra.core.Vec3(
            d.x + north.x * stepRad, d.y + north.y * stepRad, d.z + north.z * stepRad))
        val gx2 = (aE - aFine) / 2f; val gy2 = (aN - aFine) / 2f
        if (gx2 * gx2 + gy2 * gy2 > 0.27f * 0.27f) return o

        val tree = biome == Biome.RAINFOREST || biome == Biome.TEMPERATE_FOREST ||
            biome == Biome.BOREAL_FOREST || biome == Biome.WETLAND ||
            biome == Biome.SAVANNA
        val size = profile.micro01(sx * 31 + 4)
        val height = if (tree) 2.5f + 4.5f * size else 0.35f + 0.45f * size
        val halfW = height * (if (tree) 0.28f else 0.55f)

        val shade = 0.62f + 0.25f * profile.micro01(sx * 31 + 5)
        val topR = clamp01(biome.r * shade); val topG = clamp01(biome.g * shade)
        val topB = clamp01(biome.b * shade)
        // Pied nettement plus sombre que la cime : le bas d'un arbre est
        // occlus par son propre feuillage, et ce dégradé vertical est ce
        // qui donne du volume à une silhouette sans épaisseur (lot 2.13).
        val baseR = clamp01(topR * 0.42f + 0.06f)
        val baseG = clamp01(topG * 0.42f + 0.05f)
        val baseB = clamp01(topB * 0.42f + 0.04f)

        val r = planetRadiusM + a.toDouble()
        val fx = (d.x * r - centerXM).toFloat()
        val fy = (d.y * r - centerYM).toFloat()
        val fz = (d.z * r - centerZM).toFloat()

        var off = o
        off = emitKite(off, fx, fy, fz, east, d, height, halfW,
            baseR, baseG, baseB, topR, topG, topB)
        off = emitKite(off, fx, fy, fz, north, d, height, halfW,
            baseR, baseG, baseB, topR, topG, topB)
        return off
    }

    /** Quad cerf-volant sur ses deux faces : pied, ventre gauche/droit, pointe. */
    private fun emitKite(
        startOffset: Int,
        fx: Float, fy: Float, fz: Float,
        side: com.terra.core.Vec3, up: com.terra.core.Vec3,
        height: Float, halfW: Float,
        baseR: Float, baseG: Float, baseB: Float,
        topR: Float, topG: Float, topB: Float
    ): Int {
        var o = startOffset
        val midH = height * 0.45f
        val ax = fx; val ay = fy; val az = fz
        val bx = fx + up.x * midH - side.x * halfW
        val by = fy + up.y * midH - side.y * halfW
        val bz = fz + up.z * midH - side.z * halfW
        val cx = fx + up.x * height; val cy = fy + up.y * height; val cz = fz + up.z * height
        val dx = fx + up.x * midH + side.x * halfW
        val dy = fy + up.y * midH + side.y * halfW
        val dz = fz + up.z * midH + side.z * halfW
        val midR = (baseR + topR) * 0.5f; val midG = (baseG + topG) * 0.5f
        val midB = (baseB + topB) * 0.5f
        // Face avant : A-B-C, A-C-D ; face arrière en ordre inverse.
        o = plantVertex(o, ax, ay, az, baseR, baseG, baseB, up)
        o = plantVertex(o, bx, by, bz, midR, midG, midB, up)
        o = plantVertex(o, cx, cy, cz, topR, topG, topB, up)
        o = plantVertex(o, ax, ay, az, baseR, baseG, baseB, up)
        o = plantVertex(o, cx, cy, cz, topR, topG, topB, up)
        o = plantVertex(o, dx, dy, dz, midR, midG, midB, up)
        o = plantVertex(o, ax, ay, az, baseR, baseG, baseB, up)
        o = plantVertex(o, cx, cy, cz, topR, topG, topB, up)
        o = plantVertex(o, bx, by, bz, midR, midG, midB, up)
        o = plantVertex(o, ax, ay, az, baseR, baseG, baseB, up)
        o = plantVertex(o, dx, dy, dz, midR, midG, midB, up)
        o = plantVertex(o, cx, cy, cz, topR, topG, topB, up)
        return o
    }

    private fun plantVertex(
        o: Int, x: Float, y: Float, z: Float,
        r: Float, g: Float, b: Float, up: com.terra.core.Vec3
    ): Int {
        vertexData[o] = x; vertexData[o + 1] = y; vertexData[o + 2] = z
        vertexData[o + 3] = r; vertexData[o + 4] = g; vertexData[o + 5] = b
        // Normale radiale : la plante s'éclaire comme le sol qui la porte —
        // pas de normale de feuillage crédible sans vraie géométrie.
        vertexData[o + 6] = up.x; vertexData[o + 7] = up.y; vertexData[o + 8] = up.z
        vertexData[o + 9] = MATERIAL_LAND
        vertexData[o + 10] = 0f   // pas de morphing : la plante naît avec sa tuile
        return o + FLOATS_PER_VERTEX
    }

    private fun eastOf(d: com.terra.core.Vec3): com.terra.core.Vec3 {
        val el = sqrt(d.x * d.x + d.z * d.z)
        return if (el < 1e-4f) com.terra.core.Vec3(1f, 0f, 0f)
        else com.terra.core.Vec3(-d.z / el, 0f, d.x / el)
    }

    private fun northOf(d: com.terra.core.Vec3, e: com.terra.core.Vec3): com.terra.core.Vec3 =
        com.terra.core.Vec3(
            d.y * e.z - d.z * e.y, d.z * e.x - d.x * e.z, d.x * e.y - d.y * e.x
        )

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

            // Assombrissement de la jupe. Elle garde la normale RADIALE —
            // c'est ce qui la fond dans le sol qu'elle prolonge — mais un
            // mur vertical ainsi éclairé reçoit la lumière comme un sol
            // horizontal : au soleil rasant il devient plus CLAIR que le
            // terrain voisin, d'où les bandes pâles et les traits blancs
            // constatés sur appareil. Physiquement, ce rebord est dans
            // l'ombre de la lèvre qui le surplombe : on l'assombrit donc,
            // et s'il affleure il passe pour une ombre de ressaut au lieu
            // d'un mur lumineux.
            val shade = SKIRT_SHADE
            o = emitSkirtTriangle(o, relX[a], relY[a], relZ[a], relX[b], relY[b], relZ[b], axL, ayL, azL,
                outX, outY, outZ, dirX[a], dirY[a], dirZ[a],
                colR[a] * shade, colG[a] * shade, colB[a] * shade, mat[a])
            o = emitSkirtTriangle(o, relX[b], relY[b], relZ[b], bxL, byL, bzL, axL, ayL, azL,
                outX, outY, outZ, dirX[b], dirY[b], dirZ[b],
                colR[b] * shade, colG[b] * shade, colB[b] * shade, mat[b])
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

        // ---- Couche d'eau (lot 2.9-a) — tous chiffres de eau_transparence.py
        const val WATER_FLOATS_PER_VERTEX = 5
        const val WATER_STRIDE_BYTES = WATER_FLOATS_PER_VERTEX * 4
        const val WATER_OFFSET_POSITION = 0
        const val WATER_OFFSET_DEPTH = 3
        const val WATER_OFFSET_MORPH = 4

        /** Biais radial de la surface : écarte le z-fighting du rivage. */
        const val WATER_SURFACE_BIAS_M = 0.05

        /** Fond marin de référence (sable), assombri avec la profondeur. */
        const val SEAFLOOR_R = 0.55f
        const val SEAFLOOR_G = 0.50f
        const val SEAFLOOR_B = 0.38f

        // Longueurs d'extinction par canal, en mètres — le rouge meurt en
        // quelques mètres, le bleu porte le plus loin. La lumière fait
        // l'aller-retour jusqu'au fond : 2d dans l'exponentielle.
        const val WATER_LAMBDA_R = 3.5f
        const val WATER_LAMBDA_G = 14.0f
        const val WATER_LAMBDA_B = 32.0f
        const val WATER_DEEP_R = 0.015f
        const val WATER_DEEP_G = 0.11f
        const val WATER_DEEP_B = 0.24f

        /**
         * Couleur de l'eau par absorption — RÉFÉRENCE du shader d'eau.
         *
         * Le fragment GLSL porte exactement cette expression ; comme GLES2
         * n'offre aucun filet de test, la formule vit aussi ici, testée en
         * CI contre les bornes calculées du script de validation. Toute
         * retouche se fait DES DEUX CÔTÉS, l'audit shader (passe 8) et le
         * commentaire du fragment le rappellent.
         */
        fun waterAbsorptionColor(depthM: Float, out: FloatArray) {
            val tr = exp(-2f * depthM / WATER_LAMBDA_R)
            val tg = exp(-2f * depthM / WATER_LAMBDA_G)
            val tb = exp(-2f * depthM / WATER_LAMBDA_B)
            out[0] = SEAFLOOR_R * tr + WATER_DEEP_R * (1f - tr)
            out[1] = SEAFLOOR_G * tg + WATER_DEEP_G * (1f - tg)
            out[2] = SEAFLOOR_B * tb + WATER_DEEP_B * (1f - tb)
        }

        /**
         * Densité de plantes par biome : la fraction des 48 emplacements
         * réellement peuplée. Les forêts saturent, la savane clairsème ses
         * arbres, les milieux froids ou arides portent des touffes rares,
         * la roche et la glace rien.
         */
        fun plantDensity(biome: Biome): Float = when (biome) {
            Biome.RAINFOREST -> 1.0f
            Biome.TEMPERATE_FOREST -> 0.9f
            Biome.BOREAL_FOREST -> 0.8f
            Biome.WETLAND -> 0.6f
            Biome.GRASSLAND -> 0.5f
            Biome.SAVANNA -> 0.35f
            Biome.STEPPE -> 0.25f
            Biome.TUNDRA -> 0.12f
            Biome.SEMI_DESERT -> 0.08f
            else -> 0f
        }

        /**
         * Gain de l'occlusion ambiante, appliqué à la COURBURE (concavité
         * divisée par le pas de grille — sans dimension). Voir le bloc
         * d'occlusion : cette adimensionnalité est ce qui rend l'ombrage
         * continu d'une tuile à l'autre et invariant par changement de
         * niveau.
         */
        const val AO_GAIN = 12f

        /**
         * Assombrissement des jupes. Un rebord de tuile est physiquement à
         * l'ombre de la lèvre qui le surplombe ; à 55 % il se lit comme une
         * ombre de ressaut plutôt que comme un mur, dans le pire cas où il
         * affleure.
         */
        const val SKIRT_SHADE = 0.55f

        /** Couleur de roche des pentes — gris-brun neutre, éclairé par la
         *  normale comme le reste du terrain. */
        const val ROCK_R = 0.44f
        const val ROCK_G = 0.40f
        const val ROCK_B = 0.36f

        /**
         * Part de roche selon le cosinus de la pente (normale · verticale).
         *
         * Seuils calculés en angles : rien jusqu'à 14° (1 − cos = 0,0297),
         * roche pleine à 32° (1 − cos = 0,1520) — la fourchette des pentes
         * de repos des sols meubles (éboulis ~30-37°, l'herbe décroche
         * avant). Linéaire entre les deux : un flanc raide se raye de roche
         * avant que la paroi ne l'affiche pleine.
         */
        fun rockBlend(cosUp: Float): Float =
            clamp01(((1f - cosUp) - 0.0297f) / (0.1520f - 0.0297f))

        /**
         * Végétation minimale — lot 3-avancé (v0.23.0).
         *
         * Budget FIXE d'emplacements par tuile : le pool GPU recycle des
         * tampons de taille unique, un maillage à taille variable l'aurait
         * cassé. Un emplacement vide émet un triangle dégénéré (aire
         * nulle), que le GPU écarte sans coût de remplissage. 48
         * emplacements × 24 sommets (deux quads croisés × deux faces,
         * l'élagage arrière étant actif) = 1 152 sommets, soit +60 % de
         * pool — assumé, et mesurable au HUD.
         */
        const val PLANT_SLOTS = 49
        const val VERTS_PER_PLANT = 24

        /** Niveau à partir duquel les plantes existent (arête ≤ 610 m). */
        const val PLANT_MIN_LEVEL = 14

        /**
         * Niveau du TREILLIS CANONIQUE des plantes : chaque plante du monde
         * vit à une case fixe d'une grille 7×7 par tuile de niveau 15, et
         * chaque tuile — quel que soit SON niveau — émet les plantes
         * canoniques qui tombent dans son emprise. Conséquences voulues :
         * même arbre, même position, même taille à tout niveau de détail
         * (aucun saut au changement de tuile) ; densité au sol CONSTANTE
         * (une tuile de niveau 17 porte ~3 plantes, pas 49) ; et au niveau
         * 14, où 196 cases se disputent 49 emplacements, un pas de 2 sur
         * chaque axe garde l'échantillon uniforme.
         */
        const val PLANT_LATTICE_LEVEL = 15
        const val PLANT_LATTICE_N = 7

        /** Nombre de sommets émis pour une tuile : terrain, jupes, plantes. */
        fun expectedVertexCount(): Int =
            MESH_N * MESH_N * 6 + 4 * MESH_N * 6 + PLANT_SLOTS * VERTS_PER_PLANT

        /**
         * Profondeur de jupe, en mètres.
         *
         * `max(arête × 0,005 ; 1,5 m) + 4 m` — voir le commentaire de classe
         * pour l'origine de chaque terme. Le tout est validé par un test qui
         * mesure l'écart réel entre tuiles adjacentes de niveaux différents.
         */
        fun skirtDepthM(tile: TileId, planetRadiusM: Double): Double {
            val edgeM = (Math.PI * 0.5 / (1 shl tile.level)) * planetRadiusM
            // La jupe doit couvrir l'écart d'altitude entre cette tuile et
            // sa voisine d'un niveau plus grossier — soit la variation du
            // terrain sur une MAILLE, à la pente côtière raide mesurée en
            // v0.19.2 (30 %). L'ancienne loi (0,5 % de l'ARÊTE, plancher
            // 5,5 m) se trompait deux fois : trop courte aux niveaux
            // grossiers, et trente fois trop longue au ras du sol — un mur
            // de cinq mètres à côté du piéton, visible dès qu'il affleure.
            val stepM = edgeM / MESH_N
            // Plancher de 20 cm : sous cette taille la jupe ne sert plus à
            // rien, mais zéro rouvrirait les fissures que le lot 2.5 ferme.
            return max(stepM * 0.30, 0.20)
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

            // La banquise couvre l'eau : ni bathymétrie ni frange.
            if (biome == Biome.SEA_ICE) {
                outR[idx] = biome.r; outG[idx] = biome.g; outB[idx] = biome.b
                return
            }

            // Fond marin (lot 2.9-a) : plus de teinte d'eau — l'eau est une
            // couche séparée dessinée par-dessus. Le fond est un sable qui
            // s'assombrit avec la profondeur (la lumière qui l'atteint a
            // déjà traversé la colonne). Invisible sous l'eau opaque de ce
            // sous-lot, il devient le décor de la transparence au 2.9-b.
            // Échelle de 600 m : nuit complète vers 2 000 m, cohérente avec
            // les λ d'absorption du shader d'eau (plus rien ne remonte
            // au-delà de ~120 m de toute façon).
            if (altitudeM <= 0f) {
                val k = 0.06f + 0.94f * exp(altitudeM / 600f)
                outR[idx] = SEAFLOOR_R * k
                outG[idx] = SEAFLOOR_G * k
                outB[idx] = SEAFLOOR_B * k
                return
            }

            // Terre, teintée par l'altitude et mouchetée.
            val tint = (0.88f + 0.24f * clamp01(altitudeM / params.maxAltitudeM)) * jitter
            var r = clamp01(rgb[0] * tint)
            var g = clamp01(rgb[1] * tint)
            var b = clamp01(rgb[2] * tint)

            // Lot 2.9-a : la frange de rivage du TERRAIN disparaît — c'est
            // désormais la couche d'eau qui dessine la côte. Sa grille est
            // celle du terrain : la profondeur s'interpole vers zéro au
            // trait de côte dans chaque cellule, ce qui lisse la ligne
            // exactement comme la frange le faisait, de l'orbite au sol.
            // La plage au-dessus de l'eau redevient du sable SEC ; l'ourlet
            // mouillé est rendu par l'écume de la couche d'eau.

            outR[idx] = clamp01(r); outG[idx] = clamp01(g); outB[idx] = clamp01(b)
        }
    }
}
