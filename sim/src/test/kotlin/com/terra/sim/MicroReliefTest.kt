package com.terra.sim

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Micro-relief du sol proche — v0.8.1.
 *
 * Ce que ces tests verrouillent : le micro-relief reste une décoration de la
 * surface rendue, jamais une modification du monde simulé. `altitudeM`, les
 * empreintes et le climat ne doivent pas bouger d'un bit.
 */
class MicroReliefTest {

    companion object {
        private val world: PlanetData by lazy {
            WorldGenerator.fromName("Ormun", PlanetParams(subdivisions = 4)).generate()
        }
    }

    @Test
    fun `le micro-relief est borne par son amplitude declaree`() {
        val t = world.terrain
        val rng = Random(5)
        var maxSeen = 0f
        repeat(4000) {
            val d = randomDir(rng)
            val base = t.detailedAltitudeAt(d, t.detailAmplitudeForLevel(23))
            val rendered = t.renderedAltitudeAt(d, 23)
            val delta = abs(rendered - base)
            assertTrue(
                delta <= TerrainProfile.MICRO_TOTAL_AMPLITUDE_M * 0.5f + 1e-3f,
                "micro hors borne : $delta m"
            )
            if (delta > maxSeen) maxSeen = delta
        }
        // Garde-fou d'échantillon : si le micro était accidentellement coupé,
        // la borne serait trivialement vraie. On exige de l'avoir vu agir.
        assertTrue(maxSeen > 0.5f, "micro-relief invisible sur l'échantillon : $maxSeen m")
    }

    @Test
    fun `nul en mer et absent aux niveaux grossiers`() {
        val t = world.terrain
        val rng = Random(9)
        var seaChecked = 0
        repeat(6000) {
            val d = randomDir(rng)
            val base = t.detailedAltitudeAt(d, t.detailAmplitudeForLevel(23))
            if (base <= 0f) {
                assertEquals(base, t.renderedAltitudeAt(d, 23), 0f, "la mer doit rester plane")
                seaChecked++
            }
            // Sous le niveau 12, la surface rendue est exactement l'ancienne.
            assertEquals(
                t.detailedAltitudeAt(d, t.detailAmplitudeForLevel(8)),
                t.renderedAltitudeAt(d, 8), 0f
            )
        }
        assertTrue(seaChecked > 1000, "échantillon marin trop maigre : $seaChecked")
    }

    @Test
    fun `la moucheture reste dans sa plage et est deterministe`() {
        val t = world.terrain
        val rng = Random(2)
        repeat(2000) {
            val d = randomDir(rng)
            val j = t.colorJitterAt(d)
            assertTrue(j in 0.88f..1.12f, "moucheture hors plage : $j")
            assertEquals(j, t.colorJitterAt(d), 0f)
        }
    }

    @Test
    fun `le monde simule n a pas change`() {
        // Le contrat central : micro-relief et moucheture vivent sur le flux
        // terrain/micro et ne touchent qu'au rendu. L'empreinte — altitudes,
        // températures, pluies, biomes de la grille — doit être celle d'avant.
        // La comparaison à la référence figée appartient au test d'empreinte ;
        // ici on vérifie que la grille et la fonction de base coïncident
        // toujours (invariant n°3), micro compris ou non.
        val t = world.terrain
        for (i in 0 until world.vertexCount step 37) {
            val v = world.sphere.vertices[i]
            assertEquals(world.altitudeM[i], t.altitudeAt(v), 1e-3f, "sommet $i")
        }
    }

    private fun randomDir(rng: Random): com.terra.core.Vec3 {
        while (true) {
            val x = rng.nextFloat() * 2f - 1f
            val y = rng.nextFloat() * 2f - 1f
            val z = rng.nextFloat() * 2f - 1f
            val l = x * x + y * y + z * z
            if (l in 1e-4f..1f) {
                val inv = 1f / kotlin.math.sqrt(l)
                return com.terra.core.Vec3(x * inv, y * inv, z * inv)
            }
        }
    }
}
