package com.terra.sim

import com.terra.core.Seed
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IcosphereTest {

    @Test
    fun `le nombre de sommets et de faces suit la formule d Euler`() {
        for (level in 0..5) {
            val s = Icosphere(level)
            assertEquals(Icosphere.expectedVertexCount(level), s.vertexCount, "sommets niveau $level")
            assertEquals(Icosphere.expectedFaceCount(level), s.faceCount, "faces niveau $level")
        }
    }

    @Test
    fun `niveau cinq donne bien 10242 sommets`() {
        val s = Icosphere(5)
        assertEquals(10_242, s.vertexCount)
        assertEquals(20_480, s.faceCount)
    }

    @Test
    fun `tous les sommets sont sur la sphere unite`() {
        val s = Icosphere(4)
        for (v in s.vertices) {
            assertTrue(abs(v.length - 1f) < 1e-4f, "sommet hors sphère : longueur ${v.length}")
        }
    }

    @Test
    fun `aucun sommet duplique`() {
        val s = Icosphere(4)
        val keys = s.vertices.map {
            Triple(
                Math.round(it.x * 100_000f),
                Math.round(it.y * 100_000f),
                Math.round(it.z * 100_000f)
            )
        }
        assertEquals(keys.size, keys.toSet().size, "sommets dupliqués détectés")
    }

    @Test
    fun `les indices de face sont tous valides`() {
        val s = Icosphere(4)
        for (idx in s.faces) {
            assertTrue(idx in 0 until s.vertexCount, "indice invalide : $idx")
        }
    }

    @Test
    fun `la repartition des sommets est quasi uniforme`() {
        // Contrôle de l'absence de concentration polaire : chaque tranche de
        // latitude doit recevoir un nombre de sommets proportionnel à sa surface.
        val s = Icosphere(5)
        val bands = IntArray(10)
        for (v in s.vertices) {
            val band = (((v.y + 1f) / 2f) * 10f).toInt().coerceIn(0, 9)
            bands[band]++
        }
        val expected = s.vertexCount / 10
        for ((i, count) in bands.withIndex()) {
            val deviation = abs(count - expected).toFloat() / expected
            assertTrue(deviation < 0.15f, "bande $i mal répartie : $count vs $expected")
        }
    }

    @Test
    fun `l adjacence est symetrique et de degre cinq ou six`() {
        val s = Icosphere(3)
        val adj = s.buildAdjacency()
        for (i in adj.indices) {
            assertTrue(adj[i].size in 5..6, "degré anormal au sommet $i : ${adj[i].size}")
            for (j in adj[i]) {
                assertTrue(adj[j].contains(i), "adjacence non symétrique entre $i et $j")
            }
        }
        // Un polyèdre géodésique compte exactement douze sommets de degré cinq.
        assertEquals(12, adj.count { it.size == 5 })
    }
}

class NoiseTest {

    @Test
    fun `meme graine produit exactement le meme champ`() {
        val seed = Seed.master(4242L).derive("terrain")
        val a = Noise(seed)
        val b = Noise(seed)
        val rng = Seed.master(1L).rng()
        repeat(5000) {
            val x = rng.nextFloatSigned() * 20f
            val y = rng.nextFloatSigned() * 20f
            val z = rng.nextFloatSigned() * 20f
            assertEquals(a.perlin(x, y, z), b.perlin(x, y, z))
        }
    }

    @Test
    fun `des graines differentes produisent des champs differents`() {
        val a = Noise(Seed.master(1L))
        val b = Noise(Seed.master(2L))
        var identical = 0
        for (i in 0 until 1000) {
            val t = i * 0.37f
            if (a.perlin(t, t * 1.3f, t * 0.7f) == b.perlin(t, t * 1.3f, t * 0.7f)) identical++
        }
        assertTrue(identical < 20, "champs trop semblables : $identical coïncidences")
    }

    @Test
    fun `le bruit reste borne et fini`() {
        val n = Noise(Seed.master(9L))
        val rng = Seed.master(2L).rng()
        repeat(20_000) {
            val v = n.perlin(
                rng.nextFloatSigned() * 100f,
                rng.nextFloatSigned() * 100f,
                rng.nextFloatSigned() * 100f
            )
            assertTrue(v.isFinite(), "valeur non finie")
            assertTrue(abs(v) < 1.2f, "amplitude anormale : $v")
        }
    }

    @Test
    fun `le bruit est continu`() {
        // Deux points très proches doivent donner des valeurs très proches :
        // sans cela le terrain serait bruité pixel par pixel.
        val n = Noise(Seed.master(3L))
        val rng = Seed.master(4L).rng()
        repeat(2000) {
            val x = rng.nextFloatSigned() * 50f
            val y = rng.nextFloatSigned() * 50f
            val z = rng.nextFloatSigned() * 50f
            val d = abs(n.perlin(x, y, z) - n.perlin(x + 0.001f, y, z))
            assertTrue(d < 0.02f, "discontinuité détectée : $d")
        }
    }

    @Test
    fun `fbm et ridged restent finis`() {
        val n = Noise(Seed.master(6L))
        val rng = Seed.master(7L).rng()
        repeat(5000) {
            val x = rng.nextFloatSigned() * 10f
            val y = rng.nextFloatSigned() * 10f
            val z = rng.nextFloatSigned() * 10f
            assertTrue(n.fbm(x, y, z, 6).isFinite())
            assertTrue(n.ridged(x, y, z, 5).isFinite())
            assertTrue(n.billow(x, y, z, 4).isFinite())
        }
    }
}

class WorldGeneratorTest {

    private fun world(seed: Long, subdivisions: Int = 4): PlanetData =
        WorldGenerator(
            Seed.master(seed),
            PlanetParams(subdivisions = subdivisions)
        ).generate()

    @Test
    fun `la generation est deterministe`() {
        val a = world(1234L)
        val b = world(1234L)
        for (i in 0 until a.vertexCount) {
            assertEquals(a.altitudeM[i], b.altitudeM[i], "altitude divergente au sommet $i")
            assertEquals(a.temperatureC[i], b.temperatureC[i], "température divergente au sommet $i")
            assertEquals(a.biomeId[i], b.biomeId[i], "biome divergent au sommet $i")
        }
    }

    @Test
    fun `aucune valeur invalide dans le monde genere`() {
        val w = world(77L)
        for (i in 0 until w.vertexCount) {
            assertTrue(w.altitudeM[i].isFinite(), "altitude non finie au sommet $i")
            assertTrue(w.temperatureC[i].isFinite(), "température non finie au sommet $i")
            assertTrue(w.precipMm[i].isFinite(), "précipitations non finies au sommet $i")
            assertTrue(w.renderRadius(i).isFinite(), "rayon non fini au sommet $i")
        }
    }

    @Test
    fun `la fraction oceanique demandee est respectee sur toutes les graines`() {
        // C'est le test qui empêche le retour de la « planète continent unique »
        // observée en version 0.1.
        for (seed in listOf(1L, 42L, 1337L, 99999L, -5L, 2024L, 7L, 314159L)) {
            val w = world(seed)
            val actual = w.stats.oceanFractionActual
            assertTrue(
                abs(actual - 0.66f) < 0.03f,
                "graine $seed : fraction océanique de $actual au lieu de 0,66"
            )
        }
    }

    @Test
    fun `les altitudes restent dans les bornes physiques`() {
        val w = world(555L)
        val p = w.params
        assertTrue(w.stats.highestAltitudeM <= p.maxAltitudeM + 1f)
        assertTrue(w.stats.deepestDepthM >= -p.maxDepthM - 1f)
    }

    @Test
    fun `les temperatures restent plausibles`() {
        val w = world(888L)
        assertTrue(w.stats.hottestC < 45f, "trop chaud : ${w.stats.hottestC} °C")
        assertTrue(w.stats.coldestC > -85f, "trop froid : ${w.stats.coldestC} °C")
    }

    @Test
    fun `les poles sont plus froids que l equateur`() {
        val w = world(321L)
        var equatorSum = 0.0; var equatorCount = 0
        var poleSum = 0.0; var poleCount = 0
        for (i in 0 until w.vertexCount) {
            val y = abs(w.position(i).y)
            if (y < 0.15f) { equatorSum += w.temperatureC[i]; equatorCount++ }
            if (y > 0.90f) { poleSum += w.temperatureC[i]; poleCount++ }
        }
        val equator = equatorSum / equatorCount
        val pole = poleSum / poleCount
        assertTrue(equator - pole > 25.0, "gradient thermique insuffisant : $equator vs $pole")
    }

    @Test
    fun `une variete suffisante de biomes apparait`() {
        for (seed in listOf(1L, 42L, 1337L, 2024L)) {
            val w = world(seed)
            assertTrue(
                w.stats.distinctBiomes >= 8,
                "graine $seed : seulement ${w.stats.distinctBiomes} biomes distincts"
            )
        }
    }

    @Test
    fun `des graines differentes produisent des mondes differents`() {
        val a = world(1L)
        val b = world(2L)
        var identical = 0
        for (i in 0 until a.vertexCount) {
            if (a.biomeId[i] == b.biomeId[i]) identical++
        }
        val ratio = identical.toFloat() / a.vertexCount
        assertTrue(ratio < 0.75f, "mondes trop semblables : $ratio de sommets identiques")
    }

    @Test
    fun `les biomes marins et terrestres sont coherents avec l altitude`() {
        val w = world(4444L)
        for (i in 0 until w.vertexCount) {
            val submerged = w.altitudeM[i] < 0f
            assertEquals(
                submerged, w.biome(i).isWater,
                "incohérence au sommet $i : altitude ${w.altitudeM[i]}, biome ${w.biome(i).label}"
            )
        }
    }

    @Test
    fun `le rayon de rendu vaut un au niveau de la mer`() {
        val w = world(606L)
        var highestRadius = 1f
        for (i in 0 until w.vertexCount) {
            val r = w.renderRadius(i)
            if (w.altitudeM[i] <= 0f) {
                assertEquals(1f, r, "océan déformé au sommet $i")
            } else {
                // Pas de comparaison stricte : une altitude de quelques
                // millimètres donne un rayon qui, en flottant 32 bits, vaut
                // encore exactement 1. Ce qui compte est qu'aucune terre ne
                // passe sous le niveau de la mer, et que le relief existe.
                assertTrue(r >= 1f, "terre enfoncée au sommet $i : rayon $r")
                if (r > highestRadius) highestRadius = r
            }
        }
        assertTrue(highestRadius > 1.01f, "aucun relief visible : max $highestRadius")
    }

    @Test
    fun `la generation d un monde complet reste rapide`() {
        val started = System.nanoTime()
        val w = WorldGenerator(Seed.master(1L), PlanetParams(subdivisions = 5)).generate()
        val ms = (System.nanoTime() - started) / 1_000_000L
        assertEquals(10_242, w.vertexCount)
        assertTrue(ms < 12_000, "génération trop lente : $ms ms")
    }

    @Test
    fun `les etapes de progression sont annoncees dans l ordre`() {
        val seen = mutableListOf<WorldGenerator.Stage>()
        WorldGenerator(Seed.master(1L), PlanetParams(subdivisions = 3))
            .generate { stage, _ -> if (seen.lastOrNull() != stage) seen.add(stage) }
        assertEquals(WorldGenerator.Stage.GEOMETRY, seen.first())
        assertEquals(WorldGenerator.Stage.DONE, seen.last())
        assertTrue(seen.contains(WorldGenerator.Stage.CLIMATE))
    }
}

class BiomeTest {

    @Test
    fun `le domaine marin est toujours classe comme eau`() {
        for (depth in listOf(-10f, -150f, -1000f, -5000f)) {
            for (temp in listOf(-20f, 0f, 15f, 30f)) {
                assertTrue(
                    Biome.classify(depth, temp, 1000f).isWater,
                    "profondeur $depth à $temp °C classée comme terre"
                )
            }
        }
    }

    @Test
    fun `le domaine terrestre n est jamais classe comme eau`() {
        for (alt in listOf(0f, 50f, 500f, 2000f, 5000f, 7000f)) {
            for (temp in listOf(-30f, -5f, 10f, 25f, 40f)) {
                for (precip in listOf(0f, 300f, 900f, 2500f, 3600f)) {
                    assertTrue(
                        !Biome.classify(alt, temp, precip).isWater,
                        "altitude $alt, $temp °C, $precip mm classée comme eau"
                    )
                }
            }
        }
    }

    @Test
    fun `les extremes climatiques donnent les biomes attendus`() {
        assertEquals(Biome.DESERT, Biome.classify(400f, 32f, 80f))
        assertEquals(Biome.RAINFOREST, Biome.classify(200f, 27f, 3000f))
        assertEquals(Biome.GLACIER, Biome.classify(500f, -25f, 200f))
        assertEquals(Biome.SEA_ICE, Biome.classify(-500f, -10f, 300f))
        assertEquals(Biome.DEEP_OCEAN, Biome.classify(-4000f, 4f, 300f))
    }

    @Test
    fun `toutes les couleurs sont dans l intervalle valide`() {
        for (b in Biome.values()) {
            assertTrue(b.r in 0f..1f && b.g in 0f..1f && b.b in 0f..1f, "couleur invalide : ${b.label}")
        }
    }
}
