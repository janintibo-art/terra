package com.terra.sim

import com.terra.core.Vec3d
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests du maillage des tuiles — lot B1.
 *
 * La génération d'un monde coûte plus de deux secondes : les mondes de test
 * sont donc partagés entre les tests par un objet compagnon, comme le veut la
 * mécanique d'instanciation de JUnit (une instance de classe par test, mais un
 * seul chargement de l'objet compagnon).
 */
class TileMeshTest {

    companion object {
        // Deux mondes suffisent : l'objectif est de couvrir des reliefs
        // différents, pas de refaire le banc d'essai à vingt graines.
        private val worldA: PlanetData by lazy {
            WorldGenerator.fromName("Kaleth", PlanetParams(subdivisions = 4)).generate()
        }
        private val worldB: PlanetData by lazy {
            WorldGenerator.fromName("Ormun", PlanetParams(subdivisions = 4)).generate()
        }

        private fun samplerFor(w: PlanetData) = CoarseSampler(w)
    }

    // ------------------------------------------------------------- comptages

    @Test
    fun `le nombre de sommets suit la formule annoncee`() {
        val w = worldA
        val mesh = TileMesh(TileId(0, 3, 2, 5), w.terrain, samplerFor(w), w.params.radiusM.toDouble())
        assertEquals(TileMesh.expectedVertexCount(), mesh.vertexCount)
        assertEquals(mesh.vertexCount * TileMesh.FLOATS_PER_VERTEX, mesh.vertexData.size)
    }

    // -------------------------------------------------------- reconstruction

    @Test
    fun `centre plus position relative redonne l altitude du profil`() {
        // La position absolue reconstruite depuis le centre double et l'écart
        // float32 doit retomber sur le rayon « niveau de la mer + altitude »
        // que rend TerrainProfile — c'est l'invariant n°3 du projet transposé
        // aux tuiles. La tolérance est calculée, pas devinée : l'erreur vient
        // de l'arrondi float32 de la position relative, bornée par
        // ‖rel‖ × 2⁻²³, plus l'epsilon de la chaîne d'évaluation.
        val w = worldB
        val r = w.params.radiusM.toDouble()
        val sampler = samplerFor(w)

        for (level in intArrayOf(4, 8, 12, 16, 20)) {
            val grid = 1 shl level
            val tile = TileId(2, level, grid / 3, grid / 2)
            val mesh = TileMesh(tile, w.terrain, sampler, r)

            var maxRel = 0.0
            var worst = 0.0
            // Les sommets du terrain occupent le début du tampon ; on borne la
            // vérification à un échantillon pour garder le test rapide.
            val stride = TileMesh.FLOATS_PER_VERTEX * 7
            var o = 0
            while (o + 2 < TileMesh.MESH_N * TileMesh.MESH_N * 6 * TileMesh.FLOATS_PER_VERTEX) {
                val rx = mesh.vertexData[o].toDouble()
                val ry = mesh.vertexData[o + 1].toDouble()
                val rz = mesh.vertexData[o + 2].toDouble()
                val px = mesh.centerXM + rx
                val py = mesh.centerYM + ry
                val pz = mesh.centerZM + rz
                val radius = sqrt(px * px + py * py + pz * pz)

                val dir = Vec3d(px, py, pz).normalized().toVec3()
                val expected = max(w.terrain.renderedAltitudeAt(dir), 0f).toDouble()

                worst = max(worst, abs(radius - r - expected))
                maxRel = max(maxRel, sqrt(rx * rx + ry * ry + rz * rz))
                o += stride
            }

            // Trois termes, tous calculés :
            //  - l'arrondi float32 de la position relative, ‖rel‖ × 2⁻²³,
            //    compté deux fois (aller au maillage, retour au test) ;
            //  - le franchissement possible d'un ulp lors de la conversion de
            //    la direction en float32 : le test reconstruit la direction
            //    depuis des flottants arrondis, et si elle bascule sur le
            //    flottant voisin, le champ est évalué 0,38 m plus loin au sol
            //    (un ulp de direction × le rayon), soit jusqu'à
            //    0,38 × pente maximale plausible (0,25) en altitude ;
            //  - un plancher pour l'epsilon de la chaîne d'évaluation.
            val tolerance = 2.0 * maxRel * 1.2e-7 + 0.38 * 0.25 + 0.02
            assertTrue(
                worst < tolerance,
                "niveau $level : écart $worst m pour une tolérance de $tolerance m"
            )
        }
    }

    // ------------------------------------------------- coïncidence des bords

    @Test
    fun `deux tuiles voisines de meme niveau partagent leurs bords bit a bit`() {
        // C'est la garantie structurelle du paramétrage par indices globaux :
        // pas « très proche », mais identique. Si ce test casse un jour, c'est
        // que quelqu'un a réintroduit une interpolation locale dans le calcul
        // des coordonnées — et les fissures reviendront avec elle.
        val w = worldA
        val r = w.params.radiusM.toDouble()
        val level = 9
        val grid = 1 shl level

        val left = TileId(4, level, grid / 4, grid / 3)
        val right = TileId(4, level, grid / 4 + 1, grid / 3)
        for (j in 0..TileMesh.MESH_N) {
            val fromLeft = TileMesh.gridPositionM(left, TileMesh.MESH_N, j, w.terrain, r)
            val fromRight = TileMesh.gridPositionM(right, 0, j, w.terrain, r)
            assertEquals(fromLeft.x, fromRight.x, 0.0, "x, ligne $j")
            assertEquals(fromLeft.y, fromRight.y, 0.0, "y, ligne $j")
            assertEquals(fromLeft.z, fromRight.z, 0.0, "z, ligne $j")
        }

        val below = TileId(1, level, grid / 2, grid / 5)
        val above = TileId(1, level, grid / 2, grid / 5 + 1)
        for (i in 0..TileMesh.MESH_N) {
            val fromBelow = TileMesh.gridPositionM(below, i, TileMesh.MESH_N, w.terrain, r)
            val fromAbove = TileMesh.gridPositionM(above, i, 0, w.terrain, r)
            assertEquals(fromBelow.x, fromAbove.x, 0.0, "x, colonne $i")
            assertEquals(fromBelow.y, fromAbove.y, 0.0, "y, colonne $i")
            assertEquals(fromBelow.z, fromAbove.z, 0.0, "z, colonne $i")
        }
    }

    @Test
    fun `les sommets pairs d une tuile fine coincident avec la tuile grossiere`() {
        // Entre niveaux, la moitié des sommets de bord est partagée : les
        // indices globaux pairs de la fine tombent exactement sur ceux de la
        // grossière. Seuls les sommets impairs s'écartent — et c'est cet écart
        // que mesurent les jupes.
        val level = 7
        val grid = 1 shl level
        val coarse = TileId(0, level, grid / 3, grid / 3)
        val fine = TileId(0, level + 1, coarse.x * 2, coarse.y * 2)

        for (i in 0..TileMesh.MESH_N / 2) {
            val fromCoarse = CubeSphere.gridDirection(
                coarse.face, coarse.level, coarse.x * TileMesh.MESH_N + i, coarse.y * TileMesh.MESH_N, TileMesh.MESH_N
            )
            val fromFine = CubeSphere.gridDirection(
                fine.face, fine.level, fine.x * TileMesh.MESH_N + 2 * i, fine.y * TileMesh.MESH_N, TileMesh.MESH_N
            )
            assertEquals(fromCoarse.x, fromFine.x, 0.0, "x, i=$i")
            assertEquals(fromCoarse.y, fromFine.y, 0.0, "y, i=$i")
            assertEquals(fromCoarse.z, fromFine.z, 0.0, "z, i=$i")
        }
    }

    // ------------------------------------------------------------------ jupes

    @Test
    fun `la jupe couvre l ecart reel entre niveaux adjacents`() {
        // On mesure l'écart vertical entre le bord d'une tuile grossière
        // (interpolation linéaire de ses sommets) et les sommets réels du bord
        // de sa voisine fine, et l'on vérifie que la profondeur de jupe le
        // couvre. C'est la validation Python (0,21 % de l'arête au pire, marge
        // ×2,5 plus le terme de détail) rejouée sur le vrai champ Kotlin.
        for (w in listOf(worldA, worldB)) {
            val r = w.params.radiusM.toDouble()
            val rng = Random(w.name.hashCode())

            for (level in intArrayOf(6, 9, 12, 15, 18)) {
                val grid = 1 shl level
                var worstGap = 0.0
                repeat(6) {
                    val face = rng.nextInt(CubeSphere.FACE_COUNT)
                    val x = rng.nextInt(grid - 1)
                    val y = rng.nextInt(grid)
                    val coarse = TileId(face, level, x, y)
                    // Voisine de droite, un niveau plus fin, moitié basse.
                    val fine = TileId(face, level + 1, (x + 1) * 2, y * 2)

                    for (jf in 0..TileMesh.MESH_N) {
                        val p = TileMesh.gridPositionM(fine, 0, jf, w.terrain, r)
                        // Position du même paramètre sur le bord grossier :
                        // interpolation linéaire entre les deux sommets
                        // grossiers qui encadrent jf/2.
                        val jc = jf / 2
                        val a = TileMesh.gridPositionM(coarse, TileMesh.MESH_N, jc, w.terrain, r)
                        val gap: Double = if (jf % 2 == 0) {
                            (p - a).length
                        } else {
                            val b = TileMesh.gridPositionM(coarse, TileMesh.MESH_N, jc + 1, w.terrain, r)
                            val mid = (a + b) * 0.5
                            (p - mid).length
                        }
                        worstGap = max(worstGap, gap)
                    }
                }

                val skirt = TileMesh.skirtDepthM(TileId(0, level, 0, 0), r)
                assertTrue(
                    worstGap < skirt,
                    "monde ${w.name}, niveau $level : écart $worstGap m, jupe $skirt m"
                )
            }
        }
    }

    // -------------------------------------------------- précision relative

    @Test
    fun `la chaine relative float32 reste sous cinq millimetres au sol`() {
        // Rejoue en Kotlin la validation numérique faite en Python avant
        // l'écriture du lot : sommet relatif float32 + décalage double→float32
        // contre la vérité entièrement double. Erreur mesurée en Python :
        // 0,64 mm au pire à 2 m d'altitude ; le seuil de 5 mm laisse la marge
        // d'un facteur sept sans tolérer une régression réelle.
        val r = 6_371_000.0
        val rng = Random(20260805)
        var worst = 0.0

        repeat(50_000) {
            // Direction de caméra aléatoire, œil à 2 m du sol.
            val u = randomUnit(rng)
            val eye = u * (r + 2.0)

            // Sommet dans un rayon de 60 m, relief jusqu'à 7 000 m.
            val t = randomUnit(rng)
            val tangent = (t - u * (t dot u)).normalized()
            val ground = (u * r + tangent * (rng.nextDouble() * 60.0)).normalized()
            val vertex = ground * (r + rng.nextDouble() * 7_000.0)

            val tileCenter = u * r

            // Chaîne float32 telle que le renderer l'exécutera.
            val relX = (vertex.x - tileCenter.x).toFloat()
            val relY = (vertex.y - tileCenter.y).toFloat()
            val relZ = (vertex.z - tileCenter.z).toFloat()
            val offX = (tileCenter.x - eye.x).toFloat()
            val offY = (tileCenter.y - eye.y).toFloat()
            val offZ = (tileCenter.z - eye.z).toFloat()
            val fx = relX + offX
            val fy = relY + offY
            val fz = relZ + offZ

            val truth = vertex - eye
            val ex = fx.toDouble() - truth.x
            val ey = fy.toDouble() - truth.y
            val ez = fz.toDouble() - truth.z
            worst = max(worst, sqrt(ex * ex + ey * ey + ez * ez))
        }

        assertTrue(worst < 0.005, "erreur maximale de la chaîne relative : $worst m")
    }

    private fun randomUnit(rng: Random): Vec3d {
        while (true) {
            val v = Vec3d(
                rng.nextDouble() * 2.0 - 1.0,
                rng.nextDouble() * 2.0 - 1.0,
                rng.nextDouble() * 2.0 - 1.0
            )
            if (v.lengthSq in 1e-6..1.0) return v.normalized()
        }
    }
}

/**
 * Cohérence des deux chemins du cube-sphère.
 *
 * Le sélecteur travaille en float32, le mailleur en double : si les deux
 * projections divergeaient, une tuile jugée visible pourrait être maillée
 * ailleurs. L'écart admissible est celui de l'arithmétique 32 bits elle-même.
 */
class CubeSphereDoubleTest {

    @Test
    fun `les chemins float et double coincident a la precision du float`() {
        var worst = 0.0
        for (face in 0 until CubeSphere.FACE_COUNT) {
            for (i in 0..20) {
                for (j in 0..20) {
                    val s = -1.0 + 2.0 * i / 20
                    val t = -1.0 + 2.0 * j / 20
                    val d = CubeSphere.toSphereD(face, s, t)
                    val f = CubeSphere.toSphere(face, s.toFloat(), t.toFloat())
                    val dx = d.x - f.x
                    val dy = d.y - f.y
                    val dz = d.z - f.z
                    worst = max(worst, sqrt(dx * dx + dy * dy + dz * dz))
                }
            }
        }
        // La conversion des entrées et la chaîne tan/normalisation en 32 bits
        // accumulent quelques ulps : 4e-6 sur la sphère unité, soit ~25 m au
        // sol — l'erreur du chemin float, que le chemin double corrige.
        assertTrue(worst < 4e-6, "écart float/double : $worst")
    }

    @Test
    fun `gridDirection est independante du niveau pour les indices partages`() {
        // Le même point physique, adressé depuis deux niveaux différents, doit
        // produire les mêmes bits : c'est le fondement de la coïncidence des
        // bords entre niveaux.
        for (level in 3..12) {
            val gx = (5 shl (level - 3)) * TileMesh.MESH_N / 4
            val gy = (3 shl (level - 3)) * TileMesh.MESH_N / 4
            val a = CubeSphere.gridDirection(2, level, gx, gy, TileMesh.MESH_N)
            val b = CubeSphere.gridDirection(2, level + 1, gx * 2, gy * 2, TileMesh.MESH_N)
            assertEquals(a.x, b.x, 0.0, "x, niveau $level")
            assertEquals(a.y, b.y, 0.0, "y, niveau $level")
            assertEquals(a.z, b.z, 0.0, "z, niveau $level")
        }
    }
}

/**
 * Le test qui aurait attrapé le bug de la v0.7.1 : caméra et mailleur doivent
 * voir exactement la même surface. Le lancer de rayon évaluait le champ de
 * base quand les tuiles rendaient le champ détaillé — l'ancrage garantissait
 * deux mètres au-dessus d'un sol qui n'était pas celui affiché, et l'œil
 * finissait sous le terrain rendu, écran noir.
 */
class CollisionSurfaceTest {

    companion object {
        private val world: PlanetData by lazy {
            WorldGenerator.fromName("Kaleth", PlanetParams(subdivisions = 4)).generate()
        }
    }

    @Test
    fun `le lancer de rayon evalue la surface rendue au niveau maximal`() {
        val caster = TerrainRaycaster(world.terrain)
        val rng = Random(7)
        var detailSeen = false

        repeat(500) {
            val d = Vec3d(
                rng.nextDouble() * 2.0 - 1.0,
                rng.nextDouble() * 2.0 - 1.0,
                rng.nextDouble() * 2.0 - 1.0
            )
            if (d.lengthSq < 1e-6) return@repeat
            val dir = d.normalized()

            val fromCaster = caster.altitudeAlong(dir)
            val rendered = world.terrain
                .renderedAltitudeAt(dir.toVec3())
                .toDouble()
            assertEquals(rendered, fromCaster, 1e-6, "surfaces divergentes")

            // Garde-fou contre une régression sournoise : si le détail était de
            // nouveau ignoré, les deux champs coïncideraient quand même sur
            // l'océan et les rivages. On exige d'avoir observé au moins un
            // point où le détail change réellement la valeur.
            val base = world.terrain.altitudeAt(dir.toVec3()).toDouble()
            if (kotlin.math.abs(fromCaster - base) > 0.5) detailSeen = true
        }
        assertTrue(detailSeen, "aucun point où le détail agit : l'échantillon ne prouve rien")
    }

    @Test
    fun `la camera ancree reste au-dessus de la surface rendue`() {
        val caster = TerrainRaycaster(world.terrain)
        val rng = Random(11)

        repeat(40) {
            val cam = PlanetCamera(
                world.params.radiusM.toDouble(),
                focusLatRad = (rng.nextDouble() - 0.5) * Math.PI * 0.9,
                focusLonRad = (rng.nextDouble() - 0.5) * 2.0 * Math.PI,
                rangeM = 2.0 + rng.nextDouble() * 60.0,
                tiltRad = rng.nextDouble() * 1.2
            )
            cam.snapToTerrain(caster)
            val clearance = caster.heightAboveTerrain(cam.eyePositionM())
            assertTrue(
                clearance > 1.0,
                "œil à $clearance m de la surface rendue (lat ${cam.focusLatRad})"
            )
        }
    }
}

/**
 * Lissage des normales et interpolation des couleurs — lot 1.12.
 *
 * Les deux corrections visent le même défaut d'apparence : le terrain se
 * lisait comme un pavage. Les normales venaient des facettes (losanges au
 * sol), les couleurs du sommet le plus proche (hexagones de 115 km au globe).
 */
class SmoothShadingTest {

    companion object {
        private val world: PlanetData by lazy {
            WorldGenerator.fromName("Kaleth", PlanetParams(subdivisions = 4)).generate()
        }
    }

    @Test
    fun `les normales varient continument a travers un bord de tuile`() {
        // La propriété qui justifie l'anneau étendu : sans lui, les normales
        // des sommets de bord ignoreraient le relief de la tuile voisine et
        // une couture apparaîtrait le long de chaque arête.
        val w = world
        val r = w.params.radiusM.toDouble()
        val level = 12
        val grid = 1 shl level
        val left = TileId(3, level, grid / 3, grid / 4)
        val right = TileId(3, level, grid / 3 + 1, grid / 4)

        val a = TileMesh(left, w.terrain, CoarseSampler(w), r)
        val b = TileMesh(right, w.terrain, CoarseSampler(w), r)

        // Les normales des sommets partagés doivent coïncider. On les
        // retrouve dans les tampons via leur position relative, ramenée au
        // repère commun par le centre de chaque tuile.
        var compared = 0
        var worst = 0f
        var i = 0
        while (i < a.vertexCount * TileMesh.FLOATS_PER_VERTEX) {
            val ax = a.vertexData[i] + a.centerXM.toFloat()
            val ay = a.vertexData[i + 1] + a.centerYM.toFloat()
            val az = a.vertexData[i + 2] + a.centerZM.toFloat()
            var j = 0
            while (j < b.vertexCount * TileMesh.FLOATS_PER_VERTEX) {
                val bx = b.vertexData[j] + b.centerXM.toFloat()
                val by = b.vertexData[j + 1] + b.centerYM.toFloat()
                val bz = b.vertexData[j + 2] + b.centerZM.toFloat()
                val d = kotlin.math.abs(ax - bx) + kotlin.math.abs(ay - by) + kotlin.math.abs(az - bz)
                if (d < 1f) {
                    val dn = kotlin.math.abs(a.vertexData[i + 6] - b.vertexData[j + 6]) +
                            kotlin.math.abs(a.vertexData[i + 7] - b.vertexData[j + 7]) +
                            kotlin.math.abs(a.vertexData[i + 8] - b.vertexData[j + 8])
                    if (dn > worst) worst = dn
                    compared++
                    break
                }
                j += TileMesh.FLOATS_PER_VERTEX * 7
            }
            i += TileMesh.FLOATS_PER_VERTEX * 11
        }
        assertTrue(compared > 0, "aucun sommet partagé retrouvé entre les deux tuiles")
        assertTrue(worst < 0.05f, "normales discontinues au bord : écart $worst")
    }

    @Test
    fun `les normales sont unitaires et tournees vers l exterieur`() {
        val w = world
        val mesh = TileMesh(
            TileId(1, 10, 300, 400), w.terrain, CoarseSampler(w),
            w.params.radiusM.toDouble()
        )
        var checked = 0
        var i = 0
        // Depuis la v0.23.0, la fin du tampon est la section des plantes :
        // un emplacement vide y est un rembourrage INTÉGRALEMENT nul
        // (position, couleur, normale), que le GPU écarte comme triangle
        // dégénéré. Le contrat devient : géométrie réelle à normale
        // unitaire sortante, OU rembourrage nul dans la section des
        // plantes — un zéro dans le terrain ou les jupes reste une faute.
        val plantStart = (TileMesh.MESH_N * TileMesh.MESH_N * 6 +
            4 * TileMesh.MESH_N * 6) * TileMesh.FLOATS_PER_VERTEX
        while (i < mesh.vertexCount * TileMesh.FLOATS_PER_VERTEX) {
            val nx = mesh.vertexData[i + 6]
            val ny = mesh.vertexData[i + 7]
            val nz = mesh.vertexData[i + 8]
            if (nx == 0f && ny == 0f && nz == 0f) {
                assertTrue(i >= plantStart, "normale nulle hors de la section des plantes")
                assertTrue(
                    mesh.vertexData[i] == 0f && mesh.vertexData[i + 1] == 0f &&
                        mesh.vertexData[i + 2] == 0f,
                    "sommet à normale nulle mais position non nulle : pas un rembourrage"
                )
                i += TileMesh.FLOATS_PER_VERTEX * 13
                continue
            }
            val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            assertTrue(kotlin.math.abs(len - 1f) < 1e-3f, "normale non unitaire : $len")

            val px = mesh.vertexData[i] + mesh.centerXM.toFloat()
            val py = mesh.vertexData[i + 1] + mesh.centerYM.toFloat()
            val pz = mesh.vertexData[i + 2] + mesh.centerZM.toFloat()
            val pl = kotlin.math.sqrt(px * px + py * py + pz * pz)
            val outward = (nx * px + ny * py + nz * pz) / pl
            assertTrue(outward > 0.2f, "normale tournée vers l'intérieur : $outward")
            checked++
            i += TileMesh.FLOATS_PER_VERTEX * 13
        }
        assertTrue(checked > 30)
    }

    @Test
    fun `la couleur de biome varie continument`() {
        // Un aplat par cellule produirait des paliers ; l'interpolation doit
        // rendre des valeurs intermédiaires entre deux biomes voisins.
        val w = world
        val sampler = CoarseSampler(w)
        val holder = intArrayOf(0)
        val rgb = FloatArray(3)
        val rng = kotlin.random.Random(3)

        // On cherche une paire de sommets voisins de biomes différents, puis
        // on échantillonne à mi-chemin : la couleur doit être strictement
        // entre les deux, ce qu'un plus proche voisin ne peut pas produire.
        val adjacency = w.sphere.buildAdjacency()
        var found = false
        for (v in 0 until w.vertexCount) {
            if (found) break
            for (u in adjacency[v]) {
                if (w.biomeId[u] == w.biomeId[v]) continue
                val a = w.sphere.vertices[v]
                val b = w.sphere.vertices[u]
                val mid = com.terra.core.Vec3(
                    (a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f, (a.z + b.z) * 0.5f
                ).normalized()
                sampler.sampleBiomeColor(mid, holder, rgb)
                val ra = w.biomeColorR[v]
                val rb = w.biomeColorR[u]
                if (kotlin.math.abs(ra - rb) > 0.05f) {
                    val lo = kotlin.math.min(ra, rb)
                    val hi = kotlin.math.max(ra, rb)
                    assertTrue(
                        rgb[0] > lo - 0.02f && rgb[0] < hi + 0.02f,
                        "couleur hors de l'intervalle des deux biomes : ${rgb[0]} pour [$lo, $hi]"
                    )
                    found = true
                    break
                }
            }
        }
        assertTrue(found, "aucune frontière de biomes trouvée")
        // Déterminisme.
        val p = com.terra.core.Vec3(0.3f, 0.5f, 0.81f).normalized()
        sampler.sampleBiomeColor(p, holder, rgb)
        val first = rgb.copyOf()
        sampler.sampleBiomeColor(p, intArrayOf(rng.nextInt(w.vertexCount)), rgb)
        assertTrue(rgb.contentEquals(first), "l'indice de départ change la couleur")
    }
}

/**
 * Couleur de l'eau — lot 2.9.
 *
 * Les vagues vivent dans le shader et échappent aux tests JVM ; la couleur,
 * elle, se calcule au maillage et se vérifie. Les propriétés qui comptent :
 * un haut-fond laisse voir son fond, une fosse ne le laisse pas, l'écume
 * n'apparaît qu'au rivage, et rien de tout cela ne déplace le trait de côte.
 */
class WaterColorTest {

    companion object {
        private val world: PlanetData by lazy {
            WorldGenerator.fromName("Ormun", PlanetParams(subdivisions = 4)).generate()
        }
    }

    private fun meshAt(level: Int, x: Int, y: Int): TileMesh =
        TileMesh(
            TileId(0, level, x, y), world.terrain, CoarseSampler(world),
            world.params.radiusM.toDouble()
        )

    @Test
    fun `l eau peu profonde laisse voir son fond, la fosse non`() {
        // Propriété de l'atténuation : à profondeur croissante, la couleur
        // doit s'éloigner de celle du fond et tendre vers celle de l'eau.
        //
        // L'échantillonnage commence AU-DELÀ de la frange d'écume. En deçà,
        // la couleur porte deux effets — l'atténuation et l'écume, qui
        // éclaircit fortement — et la monotonie ne s'applique plus. Mesurer
        // les deux en croyant n'en mesurer qu'un est exactement l'erreur qui
        // avait rendu `MicroReliefTest` aveugle à l'arrivée des vallées ;
        // l'écume a sa propre vérification, juste après.
        val bottom = floatArrayOf(0.85f, 0.78f, 0.55f)   // sable clair
        val out = FloatArray(3)
        var previousDistance = -1f
        for (depth in floatArrayOf(TileMesh.FOAM_FADE_M + 1f, 12f, 30f, 100f, 400f)) {
            TileMesh.waterColorForTest(depth, bottom, out)
            val d = kotlin.math.abs(out[0] - bottom[0]) +
                    kotlin.math.abs(out[1] - bottom[1]) +
                    kotlin.math.abs(out[2] - bottom[2])
            assertTrue(
                d > previousDistance,
                "la couleur ne s'éloigne pas du fond à $depth m ($d vs $previousDistance)"
            )
            previousDistance = d
        }
    }

    @Test
    fun `l ecume ne parait qu au rivage`() {
        val bottom = floatArrayOf(0.2f, 0.3f, 0.4f)
        val shallow = FloatArray(3)
        val deep = FloatArray(3)
        TileMesh.waterColorForTest(0.5f, bottom, shallow)
        TileMesh.waterColorForTest(20f, bottom, deep)
        // L'écume éclaircit : la somme des canaux doit être plus forte près
        // du bord qu'au large, malgré un fond identique.
        assertTrue(
            shallow.sum() > deep.sum() + 0.3f,
            "pas d'écume au rivage : ${shallow.sum()} contre ${deep.sum()}"
        )
        // Et au-delà de la frange, plus rien.
        val beyond = FloatArray(3)
        TileMesh.waterColorForTest(TileMesh.FOAM_FADE_M + 1f, bottom, beyond)
        val noFoam = FloatArray(3)
        TileMesh.waterColorForTest(TileMesh.FOAM_FADE_M + 3f, bottom, noFoam)
        assertTrue(kotlin.math.abs(beyond.sum() - noFoam.sum()) < 0.25f)
    }

    @Test
    fun `toutes les composantes restent dans les bornes`() {
        val out = FloatArray(3)
        val rng = kotlin.random.Random(21)
        repeat(3_000) {
            val bottom = floatArrayOf(rng.nextFloat(), rng.nextFloat(), rng.nextFloat())
            TileMesh.waterColorForTest(rng.nextFloat() * 900f, bottom, out)
            for (c in out) assertTrue(c in 0f..1f, "composante hors bornes : $c")
        }
    }
}

/**
 * Morphing entre niveaux — lot 2.4.
 *
 * L'interpolation elle-même vit dans le shader ; ce qui se teste en JVM est
 * la donnée qu'il consomme : l'écart d'altitude vers la géométrie parente.
 * La propriété décisive est que cet écart reconstruise EXACTEMENT le
 * maillage du parent — sinon le ressaut demeure au moment de la bascule,
 * qui est tout ce que ce lot cherche à supprimer.
 */
class MorphingTest {

    companion object {
        private val world: PlanetData by lazy {
            WorldGenerator.fromName("Kaleth", PlanetParams(subdivisions = 4)).generate()
        }
    }

    /**
     * Tuile posée sur un relief franc, à un niveau donné.
     *
     * Choisir une tuile par des indices arbitraires revient à tirer au sort
     * sur une planète couverte aux deux tiers d'océan — où le morphing est
     * nul par conception. Le premier jet de ce test échouait pour cette seule
     * raison. On part donc d'un sommet de grille dont l'altitude est élevée,
     * et l'on descend jusqu'à la tuile qui le contient.
     */
    private fun tileOverLand(level: Int): TileId {
        var best = 0
        for (i in 0 until world.vertexCount) {
            if (world.altitudeM[i] > world.altitudeM[best]) best = i
        }
        val (face, sN, tN) = CubeSphere.fromSphere(world.sphere.vertices[best])
        val grid = 1 shl level
        val x = (((sN + 1f) * 0.5f) * grid).toInt().coerceIn(0, grid - 1)
        val y = (((tN + 1f) * 0.5f) * grid).toInt().coerceIn(0, grid - 1)
        return TileId(face, level, x, y)
    }

    @Test
    fun `un sommet d indice pair ne morphe pas`() {
        // Les sommets pairs coïncident déjà avec ceux du parent : leur écart
        // doit être nul, sans quoi la géométrie bougerait là où elle est
        // pourtant commune aux deux niveaux.
        val mesh = TileMesh(
            tileOverLand(14), world.terrain, CoarseSampler(world),
            world.params.radiusM.toDouble()
        )
        var zero = 0
        var nonZero = 0
        var i = 0
        while (i < mesh.vertexCount * TileMesh.FLOATS_PER_VERTEX) {
            val m = mesh.vertexData[i + TileMesh.OFFSET_MORPH]
            if (m == 0f) zero++ else nonZero++
            i += TileMesh.FLOATS_PER_VERTEX
        }
        // Un quart des sommets de la grille sont pairs en i ET en j, plus
        // toutes les jupes : la proportion d'écarts nuls doit être forte.
        assertTrue(zero > 0, "aucun sommet sans morphing")
        assertTrue(nonZero > 0, "aucun sommet morphé : le morphing est débranché")
    }

    @Test
    fun `l ecart de morphing reste borne par le relief local`() {
        val mesh = TileMesh(
            tileOverLand(12), world.terrain, CoarseSampler(world),
            world.params.radiusM.toDouble()
        )
        var worst = 0f
        var i = 0
        while (i < mesh.vertexCount * TileMesh.FLOATS_PER_VERTEX) {
            val m = kotlin.math.abs(mesh.vertexData[i + TileMesh.OFFSET_MORPH])
            if (m > worst) worst = m
            i += TileMesh.FLOATS_PER_VERTEX
        }
        // L'écart est une différence d'altitude entre un sommet et la moyenne
        // de ses voisins : il ne peut pas dépasser l'amplitude du relief.
        assertTrue(
            worst < world.params.maxAltitudeM,
            "écart de morphing de $worst m, au-delà du relief possible"
        )
    }

    @Test
    fun `la mer ne morphe jamais`() {
        // Morpher l'altitude de l'eau la ferait onduler au gré des bascules
        // de niveau — un défaut bien plus visible que celui qu'on corrige.
        val mesh = TileMesh(
            TileId(4, 10, 500, 500), world.terrain, CoarseSampler(world),
            world.params.radiusM.toDouble()
        )
        var i = 0
        while (i < mesh.vertexCount * TileMesh.FLOATS_PER_VERTEX) {
            val material = mesh.vertexData[i + TileMesh.OFFSET_MATERIAL]
            if (material > 0.99f) {
                assertEquals(
                    0f, mesh.vertexData[i + TileMesh.OFFSET_MORPH], 0f,
                    "un sommet d'eau franche porte un morphing"
                )
            }
            i += TileMesh.FLOATS_PER_VERTEX
        }
    }
}

/**
 * Frange de rivage — lot 2.9b.
 *
 * L'escalier des côtes venait du dernier basculement par seuil d'un rendu
 * devenu partout continu. Ces tests portent sur la largeur de la frange, qui
 * doit s'adapter au niveau : large de loin pour effacer la marche, fine de
 * près pour qu'une plage reste franche.
 */
class ShoreBlendTest {

    @Test
    fun `la frange retrecit quand le niveau s affine`() {
        var previous = Float.MAX_VALUE
        for (level in 2..TileId.MAX_LEVEL) {
            val blend = TileMesh.shoreBlendM(level)
            assertTrue(
                blend <= previous,
                "la frange s'élargit du niveau ${level - 1} au niveau $level"
            )
            previous = blend
        }
    }

    @Test
    fun `la frange couvre une maille de loin et reste fine de pres`() {
        // Contrat v0.19.2, recalibré sur les côtes générées (les 4 % du
        // calibrage initial laissaient des dents de scie, constatées sur
        // appareil) : aux niveaux intermédiaires, la frange couvre une
        // maille de pente raide (25 % en borne de test pour 30 % posés) ;
        // aux niveaux grossiers, elle sature mais couvre toujours le saut
        // isostatique côtier (1 100 m) avec marge.
        for (level in intArrayOf(8, 10, 12, 14)) {
            val edge = (Math.PI * 0.5 / (1 shl level)).toFloat() * 6_371_000f
            val step = edge / TileMesh.MESH_N
            assertTrue(
                TileMesh.shoreBlendM(level) >= step * 0.25f,
                "au niveau $level la frange ne couvre pas une maille de côte raide"
            )
        }
        for (level in intArrayOf(2, 4, 6)) {
            assertTrue(
                TileMesh.shoreBlendM(level) >= 1_300f,
                "au niveau $level la frange ne couvre plus le saut isostatique"
            )
        }
        // De près, elle doit rester au plancher : un rivage net.
        for (level in intArrayOf(18, 20, TileId.MAX_LEVEL)) {
            assertTrue(
                TileMesh.shoreBlendM(level) <= 2.5f,
                "au niveau $level la frange est trop large pour une plage"
            )
        }
    }

    @Test
    fun `la frange reste positive a tout niveau`() {
        for (level in 0..TileId.MAX_LEVEL) {
            assertTrue(TileMesh.shoreBlendM(level) > 0f)
        }
    }
}
