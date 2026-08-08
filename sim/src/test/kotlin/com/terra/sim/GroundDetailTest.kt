package com.terra.sim

import com.terra.core.DEG_TO_RAD
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Micro-détail du sol — lot 2.17 a.
 *
 * Rendu pur : aucun tableau haché par l'empreinte n'est touché, et les
 * empreintes figées le prouvent au push. Les seuils des tests sont dérivés
 * des amplitudes posées : moucheture ±13 %, grain ±7 %, dérive de teinte
 * ±10 % — l'écart-type attendu du canal rouge dépasse largement 2 %, et la
 * différence rouge − bleu doit balayer ±5 % au fil des taches.
 */
class GroundDetailTest {

    companion object {
        private val world by lazy {
            WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4)).generate()
        }
    }

    private fun sampleDirs(count: Int): List<com.terra.core.Vec3> {
        // Spirale de Fibonacci : couverture uniforme et déterministe.
        val out = ArrayList<com.terra.core.Vec3>(count)
        val golden = 2.399963f
        for (k in 0 until count) {
            val y = 1f - 2f * (k + 0.5f) / count
            val r = sqrt(1f - y * y)
            val a = golden * k
            out.add(com.terra.core.Vec3(r * kotlin.math.cos(a), y, r * kotlin.math.sin(a)))
        }
        return out
    }

    @Test
    fun `la teinte de sol est bornee par construction`() {
        val tint = FloatArray(3)
        for (d in sampleDirs(500)) {
            world.terrain.groundTintAt(d, tint)
            for (c in 0..2) {
                assertTrue(tint[c] in 0.76f..1.24f, "canal $c hors bornes : ${tint[c]}")
            }
        }
    }

    @Test
    fun `la teinte varie vraiment, en luminosite et en couleur`() {
        val tint = FloatArray(3)
        var sum = 0.0; var sumSq = 0.0
        var maxDrift = -1f; var minDrift = 1f
        val dirs = sampleDirs(600)
        for (d in dirs) {
            world.terrain.groundTintAt(d, tint)
            sum += tint[0]; sumSq += tint[0] * tint[0]
            val drift = tint[0] - tint[2]      // herbe sèche : rouge − bleu > 0
            if (drift > maxDrift) maxDrift = drift
            if (drift < minDrift) minDrift = drift
        }
        val mean = sum / dirs.size
        val std = sqrt(sumSq / dirs.size - mean * mean)
        assertTrue(std > 0.02, "écart-type de $std : la moucheture est morte")
        assertTrue(maxDrift > 0.05f, "aucune tache sèche (max rouge−bleu $maxDrift)")
        assertTrue(minDrift < -0.05f, "aucune tache grasse (min rouge−bleu $minDrift)")
    }

    @Test
    fun `la roche suit l angle de pente, aux seuils calcules`() {
        assertEquals(0f, TileMesh.rockBlend(1f), "plat : pas de roche")
        assertEquals(0f, TileMesh.rockBlend(cos(10f * DEG_TO_RAD)), "10° : sous le seuil")
        assertEquals(1f, TileMesh.rockBlend(cos(40f * DEG_TO_RAD)), "40° : roche pleine")
        val at20 = TileMesh.rockBlend(cos(20f * DEG_TO_RAD))
        val at28 = TileMesh.rockBlend(cos(28f * DEG_TO_RAD))
        assertTrue(at20 > 0f && at20 < 1f, "20° devrait être en transition ($at20)")
        assertTrue(at28 > at20, "la roche doit croître avec la pente")
    }

    @Test
    fun `les couleurs de tuile restent valides et deterministes`() {
        val sampler = CoarseSampler(world)
        val r = world.params.radiusM.toDouble()
        // Une tuile fine (niveau 12) et une grossière (niveau 4) : le détail
        // doit vivre aux deux échelles sans jamais sortir de [0, 1].
        for (tile in listOf(TileId(0, 3, 2, 5), TileId(2, 12, 1500, 900))) {
            val a = TileMesh(tile, world.terrain, sampler, r)
            val b = TileMesh(tile, world.terrain, sampler, r)
            assertTrue(a.vertexData.contentEquals(b.vertexData),
                "tuile $tile non déterministe")
            var v = TileMesh.OFFSET_COLOR
            while (v < a.vertexData.size) {
                for (c in 0..2) {
                    val x = a.vertexData[v + c]
                    assertTrue(x in 0f..1f, "couleur hors bornes dans $tile : $x")
                }
                v += TileMesh.FLOATS_PER_VERTEX
            }
        }
    }
}
