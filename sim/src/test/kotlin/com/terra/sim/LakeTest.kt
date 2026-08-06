package com.terra.sim

import com.terra.core.Vec3
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lacs — lot 1.11.
 *
 * Les propriétés testées sont celles qui protègent le reste : la surface d'un
 * lac est plane, elle ne descend jamais sous la mer, elle appartient à
 * l'unique surface du terrain (donc la caméra s'y pose au lieu d'atterrir au
 * fond), et le monde simulé reste inchangé.
 */
class LakeTest {

    companion object {
        private val worlds: List<PlanetData> by lazy {
            listOf("Kaleth", "Ormun").map {
                WorldGenerator.fromName(it, PlanetParams(subdivisions = 4)).generate()
            }
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
    fun `des lacs existent, en proportion terrestre`() {
        // Sur Terre, les lacs couvrent environ 1,8 % des terres émergées. Le
        // seuil est calibré pour cet ordre de grandeur ; les bornes sont
        // larges, car le relief varie beaucoup d'un monde à l'autre.
        for (w in worlds) {
            var land = 0
            var lakes = 0
            for (i in 0 until w.vertexCount) {
                if (w.altitudeM[i] <= 0f) continue
                land++
                if (w.hydrology.fillDepthM[i] >= TerrainProfile.LAKE_MIN_DEPTH_M) lakes++
            }
            assertTrue(land > 500, "${w.name} : trop peu de terres")
            val share = lakes.toFloat() / land
            assertTrue(share > 0.001f, "${w.name} : aucun lac (${lakes} cellules)")
            assertTrue(share < 0.15f, "${w.name} : ${share * 100} % de lacs, trop")
        }
    }

    @Test
    fun `la surface d un lac est plane`() {
        // C'est la définition même d'un plan d'eau. On échantillonne autour
        // d'une cellule de lac : partout où l'eau est présente, le niveau
        // rendu doit coïncider avec le niveau publié, à l'interpolation près.
        val w = worlds[0]
        val t = w.terrain
        var tested = 0
        for (i in 0 until w.vertexCount) {
            if (w.hydrology.fillDepthM[i] < TerrainProfile.LAKE_MIN_DEPTH_M) continue
            val v = w.sphere.vertices[i]
            val level = t.lakeSurfaceAt(v)
            if (level == TerrainProfile.NO_LAKE) continue
            val expected = w.hydrology.erodedM[i] + w.hydrology.fillDepthM[i]
            assertEquals(expected, level, 1f, "niveau du lac au sommet $i")
            tested++
            if (tested > 40) break
        }
        assertTrue(tested > 5, "échantillon de lacs trop maigre : $tested")
    }

    @Test
    fun `un lac ne descend jamais sous la mer`() {
        // Sinon ce serait un golfe, et le trait de côte de la grille cesserait
        // de correspondre à celui du rendu — donc au climat et aux biomes.
        for (w in worlds) {
            for (i in 0 until w.vertexCount) {
                if (w.hydrology.fillDepthM[i] < TerrainProfile.LAKE_MIN_DEPTH_M) continue
                val level = w.hydrology.erodedM[i] + w.hydrology.fillDepthM[i]
                assertTrue(level > 0f, "${w.name} : lac à $level m au sommet $i")
            }
        }
    }

    @Test
    fun `l eau du lac fait partie de la surface rendue`() {
        // La leçon de la v0.10.4 : une seule surface. Si le lac vivait dans
        // une couche séparée, la caméra s'ancrerait au fond pendant que
        // l'écran afficherait l'eau.
        val w = worlds[0]
        val t = w.terrain
        val rng = Random(9)
        var checked = 0
        repeat(40_000) {
            val d = randomDir(rng)
            val lake = t.lakeSurfaceAt(d)
            if (lake == TerrainProfile.NO_LAKE) return@repeat
            val rendered = t.renderedAltitudeAt(d)
            assertTrue(
                rendered >= lake - 0.5f,
                "surface rendue à $rendered m sous un lac à $lake m"
            )
            checked++
        }
        assertTrue(checked > 20, "aucun point de lac rencontré : $checked")
    }

    @Test
    fun `hors lac rien ne change`() {
        val w = worlds[0]
        val t = w.terrain
        val rng = Random(10)
        repeat(5_000) {
            val d = randomDir(rng)
            if (t.lakeSurfaceAt(d) != TerrainProfile.NO_LAKE) return@repeat
            assertEquals(0f, t.lakeDepthAt(d), 0f, "profondeur d'eau hors lac")
        }
    }

    @Test
    fun `le monde simule est inchange`() {
        val w = worlds[0]
        for (i in 0 until w.vertexCount step 31) {
            assertEquals(
                w.altitudeM[i], w.terrain.altitudeAt(w.sphere.vertices[i]), 1e-3f,
                "sommet $i"
            )
        }
    }
}
