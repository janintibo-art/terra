package com.terra.sim

import com.terra.core.Vec3d
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Couche d'eau — lot 2.9-a.
 *
 * ## Stratégie
 *
 * Le mailleur décide de l'eau au sommet de GRILLE (direction bit à bit
 * reproductible par [CubeSphere.gridDirection]) : ces tests recomposent donc
 * la même grille et comparent en ÉGALITÉ EXACTE — profondeurs, morph,
 * comptage de cellules — sur le modèle naïf-contre-optimisé du
 * TileSelectorTest. Seule la position, stockée en float32, se compare à une
 * tolérance calculée.
 *
 * Les tuiles de test sont cherchées près de l'ÉQUATEUR : sous 20° de
 * latitude, le modèle garantit plus de 8 °C (validation des gyres), donc
 * jamais de banquise — l'écrêtage de la glace ne peut pas brouiller ce que
 * l'on mesure. C'est la garantie forte qui autorise TileMeshTest à rester
 * tolérant sur les frontières de biome.
 */
class TileWaterTest {

    companion object {
        private val world: PlanetData by lazy {
            WorldGenerator.fromName("Kaleth", PlanetParams(subdivisions = 4)).generate()
        }
        private val sampler: CoarseSampler by lazy { CoarseSampler(world) }

        private const val LEVEL = 8
        private const val N = TileMesh.MESH_N

        /** Direction d'un sommet de grille, exactement comme le mailleur. */
        private fun gridDir(tile: TileId, i: Int, j: Int) =
            CubeSphere.gridDirection(tile.face, tile.level, tile.x * N + i, tile.y * N + j, N)
                .toVec3()

        /** Profondeur d'eau au sommet de grille, règle du mailleur. */
        private fun depthAt(tile: TileId, i: Int, j: Int): Float {
            val df = gridDir(tile, i, j)
            if (sampler.biomeAt(df) == Biome.SEA_ICE) return 0f
            return max(0f, -world.terrain.renderedAltitudeAt(df))
        }

        /** Cellules en eau de la tuile, comptées naïvement. */
        private fun naiveWaterCells(tile: TileId): Int {
            val d = Array(N + 1) { j -> FloatArray(N + 1) { i -> depthAt(tile, i, j) } }
            var cells = 0
            for (j in 0 until N) for (i in 0 until N) {
                if (d[j][i] > 0f || d[j][i + 1] > 0f ||
                    d[j + 1][i] > 0f || d[j + 1][i + 1] > 0f) cells++
            }
            return cells
        }

        /** Latitude du centre de la tuile, en degrés (axe polaire Y). */
        private fun centerLatDeg(tile: TileId): Double {
            val c = CubeSphere.gridDirection(tile.face, tile.level, tile.x * N + N / 2, tile.y * N + N / 2, N)
            return Math.toDegrees(kotlin.math.asin(c.y))
        }

        /** Première tuile équatoriale dont le centre est en mer franche. */
        private val oceanTile: TileId by lazy {
            findTile { _, centerAlt -> centerAlt < -100f } ?: fail(
                "aucune tuile océanique équatoriale trouvée — improbable sur " +
                    "un monde à ~70 % d'océan, vérifier la recherche"
            )
        }

        /** Première tuile équatoriale qui n'émet aucune eau. */
        private val dryTile: TileId by lazy {
            findTile { tile, centerAlt -> centerAlt > 300f && naiveWaterCells(tile) == 0 }
                ?: fail("aucune tuile équatoriale entièrement sèche trouvée")
        }

        private fun findTile(accept: (TileId, Float) -> Boolean): TileId? {
            val grid = 1 shl LEVEL
            for (face in 0 until 6) {
                var y = 0
                while (y < grid) {
                    var x = 0
                    while (x < grid) {
                        val tile = TileId(face, LEVEL, x, y)
                        if (abs(centerLatDeg(tile)) < 15.0) {
                            val centerAlt = world.terrain.renderedAltitudeAt(
                                gridDir(tile, N / 2, N / 2)
                            )
                            if (accept(tile, centerAlt)) return tile
                        }
                        x += 4
                    }
                    y += 4
                }
            }
            return null
        }
    }

    @Test
    fun `la couche d eau compte exactement ses cellules`() {
        val mesh = TileMesh(oceanTile, world.terrain, sampler, world.params.radiusM.toDouble())
        val cells = naiveWaterCells(oceanTile)
        assertTrue(cells > 0, "la tuile océanique doit contenir de l'eau")
        assertEquals(cells * 6, mesh.waterVertexCount,
            "chaque cellule en eau émet deux triangles, aucune autre")
        assertEquals(mesh.waterVertexCount * TileMesh.WATER_FLOATS_PER_VERTEX,
            mesh.waterData.size)
        assertEquals((mesh.vertexData.size + mesh.waterData.size) * 4, mesh.sizeBytes,
            "sizeBytes doit couvrir terrain ET eau : c'est lui qui dimensionne le VBO")
    }

    @Test
    fun `une tuile seche n emet aucune eau`() {
        val mesh = TileMesh(dryTile, world.terrain, sampler, world.params.radiusM.toDouble())
        assertEquals(0, mesh.waterVertexCount,
            "une tuile continentale ne doit rien payer pour la couche d'eau")
        assertEquals(0, mesh.waterData.size)
    }

    @Test
    fun `profondeur et morph des sommets d eau sont exacts au bit pres`() {
        // On rejoue l'ordre d'émission du mailleur (cellules en ligne, deux
        // triangles v00-v10-v11 puis v00-v11-v01) : profondeur et morph de
        // chaque sommet doivent être EXACTEMENT ceux de la grille — mêmes
        // directions bit à bit, même arithmétique, aucune tolérance.
        val r = world.params.radiusM.toDouble()
        val mesh = TileMesh(oceanTile, world.terrain, sampler, r)
        val d = Array(N + 1) { j -> FloatArray(N + 1) { i -> depthAt(oceanTile, i, j) } }
        fun parentDepth(i: Int, j: Int): Float {
            val iOdd = (i and 1) == 1
            val jOdd = (j and 1) == 1
            return when {
                !iOdd && !jOdd -> d[j][i]
                iOdd && !jOdd -> (d[j][i - 1] + d[j][i + 1]) * 0.5f
                !iOdd && jOdd -> (d[j - 1][i] + d[j + 1][i]) * 0.5f
                else -> (d[j - 1][i - 1] + d[j - 1][i + 1] +
                        d[j + 1][i - 1] + d[j + 1][i + 1]) * 0.25f
            }
        }
        var o = 0
        val f = TileMesh.WATER_FLOATS_PER_VERTEX
        for (j in 0 until N) for (i in 0 until N) {
            if (d[j][i] <= 0f && d[j][i + 1] <= 0f &&
                d[j + 1][i] <= 0f && d[j + 1][i + 1] <= 0f) continue
            val order = arrayOf(
                intArrayOf(i, j), intArrayOf(i + 1, j), intArrayOf(i + 1, j + 1),
                intArrayOf(i, j), intArrayOf(i + 1, j + 1), intArrayOf(i, j + 1)
            )
            for ((vi, vj) in order.map { it[0] to it[1] }) {
                assertEquals(d[vj][vi], mesh.waterData[o + TileMesh.WATER_OFFSET_DEPTH], 0f,
                    "profondeur du sommet ($vi, $vj)")
                assertEquals(parentDepth(vi, vj) - d[vj][vi],
                    mesh.waterData[o + TileMesh.WATER_OFFSET_MORPH], 0f,
                    "morph de profondeur du sommet ($vi, $vj)")
                o += f
            }
        }
        assertEquals(mesh.waterData.size, o, "tous les sommets d'eau ont été vérifiés")
    }

    @Test
    fun `la surface d eau est au rayon de la mer plus le biais`() {
        val r = world.params.radiusM.toDouble()
        val mesh = TileMesh(oceanTile, world.terrain, sampler, r)
        assertTrue(mesh.waterVertexCount > 0)
        var worst = 0.0
        var maxRel = 0.0
        var o = 0
        while (o < mesh.waterData.size) {
            val rx = mesh.waterData[o].toDouble()
            val ry = mesh.waterData[o + 1].toDouble()
            val rz = mesh.waterData[o + 2].toDouble()
            val px = mesh.centerXM + rx
            val py = mesh.centerYM + ry
            val pz = mesh.centerZM + rz
            worst = max(worst, abs(sqrt(px * px + py * py + pz * pz) - r - TileMesh.WATER_SURFACE_BIAS_M))
            maxRel = max(maxRel, sqrt(rx * rx + ry * ry + rz * rz))
            o += TileMesh.WATER_FLOATS_PER_VERTEX
        }
        // Tolérance calculée : arrondi float32 de la position relative,
        // ‖rel‖ × 2⁻²³, compté deux fois (écriture puis relecture), plus un
        // plancher d'epsilon de chaîne. Aucune direction n'est reconstruite
        // ici : pas de terme d'ulp directionnel.
        val tolerance = 2.0 * maxRel * 1.2e-7 + 0.001
        assertTrue(worst < tolerance,
            "surface d'eau à $worst m du rayon attendu (tolérance $tolerance m)")
    }

    @Test
    fun `le fond marin est rendu a l altitude vraie sous l equateur`() {
        // Le pendant fort du test assoupli de TileMeshTest : ici, pas de
        // banquise possible (< 15° de latitude), donc AUCUNE ambiguïté — le
        // terrain sous la mer doit être à son altitude vraie, négative.
        val r = world.params.radiusM.toDouble()
        val mesh = TileMesh(oceanTile, world.terrain, sampler, r)
        var checked = 0
        var worst = 0.0
        var maxRel = 0.0
        val stride = TileMesh.FLOATS_PER_VERTEX * 7
        var o = 0
        val terrainFloats = TileMesh.MESH_N * TileMesh.MESH_N * 6 * TileMesh.FLOATS_PER_VERTEX
        while (o + 2 < terrainFloats) {
            val rx = mesh.vertexData[o].toDouble()
            val ry = mesh.vertexData[o + 1].toDouble()
            val rz = mesh.vertexData[o + 2].toDouble()
            val px = mesh.centerXM + rx
            val py = mesh.centerYM + ry
            val pz = mesh.centerZM + rz
            val radius = sqrt(px * px + py * py + pz * pz)
            val dir = Vec3d(px, py, pz).normalized().toVec3()
            val aTrue = world.terrain.renderedAltitudeAt(dir).toDouble()
            if (aTrue < -1.0) {
                worst = max(worst, abs(radius - r - aTrue))
                maxRel = max(maxRel, sqrt(rx * rx + ry * ry + rz * rz))
                checked++
            }
            o += stride
        }
        assertTrue(checked > 0, "la tuile océanique doit exposer du fond marin")
        // Même tolérance calculée que le test d'altitude du terrain : arrondi
        // float32 aller-retour, ulp de direction reconstruite (0,38 m au sol
        // × pente plausible 0,25), plancher de chaîne.
        val tolerance = 2.0 * maxRel * 1.2e-7 + 0.38 * 0.25 + 0.02
        assertTrue(worst < tolerance,
            "fond marin à $worst m de l'altitude vraie ($checked sommets, tolérance $tolerance m)")
    }

    @Test
    fun `l absorption respecte les bornes calculees du script de validation`() {
        // Bornes reprises de validation/eau_transparence.py — calculées,
        // pas devinées : la convergence du bleu s'exige à 120 m, pas à 30 m
        // où l'exponentielle laisse encore +0,021 de fond.
        val c = FloatArray(3)
        TileMesh.waterAbsorptionColor(0.3f, c)
        assertTrue(c[0] > 0.45f, "à 30 cm le fond doit dominer (r = ${c[0]})")
        TileMesh.waterAbsorptionColor(3f, c)
        assertTrue(c[1] > c[0] * 2.5f, "à 3 m le vert doit dominer le rouge")
        TileMesh.waterAbsorptionColor(30f, c)
        assertTrue(abs(c[0] - TileMesh.WATER_DEEP_R) < 0.001f && c[2] > c[1] && c[1] > c[0],
            "à 30 m : rouge éteint, hiérarchie bleu > vert > rouge")
        TileMesh.waterAbsorptionColor(120f, c)
        assertTrue(abs(c[2] - TileMesh.WATER_DEEP_B) < 0.001f,
            "à 120 m la couleur d'eau pure doit être atteinte (b = ${c[2]})")
    }
}
