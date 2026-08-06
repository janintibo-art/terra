package com.terra.sim

import com.terra.core.Vec3
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Incision des vallées — lot 1.10b.
 *
 * Les propriétés vérifiées sont celles qui protègent le reste du projet :
 * l'incision est bornée par construction, elle ne déplace pas le trait de
 * côte, et surtout **elle ne touche pas au monde simulé** — elle enjolive le
 * terrain rendu, rien d'autre.
 */
class ValleyIncisionTest {

    companion object {
        private val world: PlanetData by lazy {
            WorldGenerator.fromName("Kaleth", PlanetParams(subdivisions = 4)).generate()
        }
    }

    private fun randomDir(rng: Random): Vec3 {
        while (true) {
            val x = rng.nextFloat() * 2f - 1f
            val y = rng.nextFloat() * 2f - 1f
            val z = rng.nextFloat() * 2f - 1f
            val l = x * x + y * y + z * z
            if (l in 1e-4f..1f) {
                val inv = 1f / kotlin.math.sqrt(l)
                return Vec3(x * inv, y * inv, z * inv)
            }
        }
    }

    @Test
    fun `le creusement respecte sa borne et agit reellement`() {
        val t = world.terrain
        val rng = Random(4)
        var maxCut = 0f
        var carved = 0
        var samples = 0
        repeat(20_000) {
            val d = randomDir(rng)
            val alt = t.detailedAltitudeAt(d, TerrainProfile.DETAIL_AMPLITUDE_M)
            if (alt <= 0f) return@repeat
            samples++
            val cut = t.valleyDepthAt(d, alt)
            assertTrue(cut >= 0f, "creusement négatif : $cut")
            assertTrue(
                cut <= TerrainProfile.VALLEY_DEPTH_MAX_M,
                "creusement de $cut m au-delà de la borne"
            )
            if (cut > maxCut) maxCut = cut
            if (cut > 1f) carved++
        }
        assertTrue(samples > 2_000, "échantillon terrestre trop maigre")
        // Garde-fou : une incision débranchée respecterait trivialement la
        // borne. On exige qu'elle creuse vraiment, sur une fraction du sol
        // conforme au calibrage (7 % attendu, bornes larges).
        assertTrue(maxCut > 10f, "creusement maximal de $maxCut m seulement")
        val share = carved.toFloat() / samples
        assertTrue(share in 0.01f..0.35f, "vallées sur ${share * 100} % du sol")
    }

    @Test
    fun `les vallees n ouvrent jamais la mer`() {
        // Une vallée qui passerait sous le niveau de la mer deviendrait un
        // bras de mer et déplacerait le trait de côte par rapport à la
        // grille — donc par rapport au climat et aux biomes.
        val t = world.terrain
        val rng = Random(5)
        repeat(20_000) {
            val d = randomDir(rng)
            val base = t.detailedAltitudeAt(d, TerrainProfile.DETAIL_AMPLITUDE_M)
            if (base <= 0f) return@repeat
            assertTrue(
                t.renderedAltitudeAt(d) > 0f,
                "la surface rendue passe sous la mer : ${t.renderedAltitudeAt(d)}"
            )
        }
    }

    @Test
    fun `pres du rivage le terrain reste intact`() {
        val t = world.terrain
        val rng = Random(6)
        var checked = 0
        repeat(30_000) {
            val d = randomDir(rng)
            val alt = t.detailedAltitudeAt(d, TerrainProfile.DETAIL_AMPLITUDE_M)
            if (alt <= 0f || alt > TerrainProfile.VALLEY_MIN_ALTITUDE_M) return@repeat
            assertEquals(0f, t.valleyDepthAt(d, alt), 0f, "vallée sous 25 m d'altitude")
            checked++
        }
        assertTrue(checked > 50, "échantillon littoral trop maigre : $checked")
    }

    @Test
    fun `le monde simule est inchange`() {
        // Le contrat du lot : l'incision enjolive le terrain RENDU. La
        // grille, le climat, les biomes et l'empreinte n'en savent rien —
        // c'est ce qui permet de la brancher après l'hydrologie sans boucle.
        val t = world.terrain
        for (i in 0 until world.vertexCount step 29) {
            val v = world.sphere.vertices[i]
            assertEquals(world.altitudeM[i], t.altitudeAt(v), 1e-3f, "sommet $i")
        }
    }

    @Test
    fun `l incision est deterministe`() {
        val t = world.terrain
        val rng = Random(7)
        repeat(2_000) {
            val d = randomDir(rng)
            val alt = t.detailedAltitudeAt(d, TerrainProfile.DETAIL_AMPLITUDE_M)
            if (alt <= 0f) return@repeat
            assertEquals(t.valleyDepthAt(d, alt), t.valleyDepthAt(d, alt), 0f)
        }
    }
}
