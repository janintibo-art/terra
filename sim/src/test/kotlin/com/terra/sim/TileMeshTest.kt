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
                val expected = max(
                    w.terrain.detailedAltitudeAt(dir, w.terrain.detailAmplitudeForLevel(level)),
                    0f
                ).toDouble()

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
        val w = worldB
        val r = w.params.radiusM.toDouble()
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
