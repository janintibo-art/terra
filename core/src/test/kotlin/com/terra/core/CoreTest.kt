package com.terra.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RngTest {

    @Test
    fun `meme graine produit exactement la meme sequence`() {
        val a = Rng(12345L)
        val b = Rng(12345L)
        repeat(2000) {
            assertEquals(a.nextInt(), b.nextInt(), "divergence au tirage $it")
        }
    }

    @Test
    fun `graines differentes produisent des sequences differentes`() {
        val a = Rng(1L)
        val b = Rng(2L)
        var identical = 0
        repeat(500) { if (a.nextInt() == b.nextInt()) identical++ }
        assertTrue(identical < 5, "trop de collisions : $identical")
    }

    @Test
    fun `sequences independantes ne se correlent pas`() {
        val a = Rng(42L, sequence = 1L)
        val b = Rng(42L, sequence = 2L)
        var identical = 0
        repeat(500) { if (a.nextInt() == b.nextInt()) identical++ }
        assertTrue(identical < 5, "flux corrélés : $identical collisions")
    }

    @Test
    fun `la copie reprend exactement au meme point du flux`() {
        val original = Rng(999L)
        repeat(37) { original.nextInt() }
        val clone = original.copy()
        repeat(200) {
            assertEquals(original.nextInt(), clone.nextInt())
        }
    }

    @Test
    fun `restauration depuis un etat sauvegarde`() {
        val original = Rng(7L)
        repeat(100) { original.nextInt() }
        val restored = Rng.fromState(original.state, original.inc)
        repeat(100) {
            assertEquals(original.nextInt(), restored.nextInt())
        }
    }

    @Test
    fun `nextFloat reste dans les bornes`() {
        val rng = Rng(5L)
        repeat(50_000) {
            val v = rng.nextFloat()
            assertTrue(v >= 0f && v < 1f, "hors bornes : $v")
        }
    }

    @Test
    fun `nextInt borne couvre tout l intervalle sans le depasser`() {
        val rng = Rng(8L)
        val seen = BooleanArray(10)
        repeat(20_000) {
            val v = rng.nextInt(10)
            assertTrue(v in 0..9, "hors bornes : $v")
            seen[v] = true
        }
        assertTrue(seen.all { it }, "certaines valeurs ne sortent jamais")
    }

    @Test
    fun `distribution raisonnablement uniforme`() {
        val rng = Rng(3L)
        val buckets = IntArray(16)
        val draws = 160_000
        repeat(draws) { buckets[(rng.nextFloat() * 16f).toInt().coerceAtMost(15)]++ }
        val expected = draws / 16
        for ((i, count) in buckets.withIndex()) {
            val deviation = abs(count - expected).toFloat() / expected
            assertTrue(deviation < 0.10f, "case $i déséquilibrée : $count vs $expected")
        }
    }

    @Test
    fun `gaussienne centree et d ecart type proche de un`() {
        val rng = Rng(11L)
        var sum = 0.0
        var sumSq = 0.0
        val draws = 100_000
        repeat(draws) {
            val g = rng.nextGaussian().toDouble()
            sum += g
            sumSq += g * g
        }
        val mean = sum / draws
        val variance = sumSq / draws - mean * mean
        assertTrue(abs(mean) < 0.02, "moyenne dérivée : $mean")
        assertTrue(abs(variance - 1.0) < 0.05, "variance dérivée : $variance")
    }
}

class SeedTest {

    @Test
    fun `la derivation est stable entre deux appels`() {
        val master = Seed.master(1337L)
        assertEquals(master.derive("terrain").value, master.derive("terrain").value)
        assertEquals(
            master.derive("faune").derive("individu", 42).value,
            master.derive("faune").derive("individu", 42).value
        )
    }

    @Test
    fun `des libelles differents donnent des graines differentes`() {
        val master = Seed.master(1337L)
        val values = listOf("terrain", "climat", "faune", "flore", "culture")
            .map { master.derive(it).value }
        assertEquals(values.size, values.toSet().size, "collision entre domaines")
    }

    @Test
    fun `ajouter un domaine ne perturbe pas les autres`() {
        // Le scénario que ce système existe pour empêcher : introduire un
        // nouveau système en phase tardive ne doit pas changer les mondes déjà
        // générés.
        val master = Seed.master(2024L)
        val terrainBefore = master.derive("terrain").value
        val fauneBefore = master.derive("faune").value

        val nouveau = master.derive("civilisations")   // ajout d'un domaine
        nouveau.rng().nextInt()                        // qui consomme de l'aléa

        assertEquals(terrainBefore, master.derive("terrain").value)
        assertEquals(fauneBefore, master.derive("faune").value)
    }

    @Test
    fun `les indices voisins ne se correlent pas`() {
        val faune = Seed.master(5L).derive("faune")
        val values = (0 until 1000).map { faune.derive("individu", it).value }
        assertEquals(values.size, values.toSet().size, "collision entre indices")
    }

    @Test
    fun `une graine textuelle est reproductible et insensible a la casse`() {
        assertEquals(Seed.fromText("Gaia").value, Seed.fromText("  gaia ").value)
        assertNotEquals(Seed.fromText("Gaia").value, Seed.fromText("Gaya").value)
    }

    @Test
    fun `le code court fait huit caracteres lisibles`() {
        val code = Seed.master(999L).shortCode()
        assertEquals(8, code.length)
        assertTrue(code.all { it.isLetterOrDigit() })
    }
}

class MathXTest {

    @Test
    fun `clamp et smoothstep respectent leurs bornes`() {
        assertEquals(0f, clamp(-5f, 0f, 1f))
        assertEquals(1f, clamp(5f, 0f, 1f))
        assertEquals(0f, smoothstep(0f, 1f, -1f))
        assertEquals(1f, smoothstep(0f, 1f, 2f))
        assertTrue(abs(smoothstep(0f, 1f, 0.5f) - 0.5f) < 1e-6f)
    }

    @Test
    fun `smoothstep ne divise pas par zero`() {
        assertEquals(1f, smoothstep(1f, 1f, 2f))
        assertEquals(0f, smoothstep(1f, 1f, 0f))
    }

    @Test
    fun `lerp et remap sont coherents`() {
        assertEquals(5f, lerp(0f, 10f, 0.5f))
        assertEquals(50f, remap(0.5f, 0f, 1f, 0f, 100f))
        assertEquals(100f, remapClamped(2f, 0f, 1f, 0f, 100f))
    }
}

class Vec3Test {

    @Test
    fun `operations vectorielles de base`() {
        val a = Vec3(1f, 2f, 3f)
        val b = Vec3(4f, 5f, 6f)
        assertEquals(Vec3(5f, 7f, 9f), a + b)
        assertEquals(32f, a dot b)
        assertEquals(Vec3(-3f, 6f, -3f), a cross b)
    }

    @Test
    fun `normalisation donne une longueur unitaire`() {
        val v = Vec3(3f, 4f, 12f).normalized()
        assertTrue(abs(v.length - 1f) < 1e-6f)
    }

    @Test
    fun `normaliser le vecteur nul ne produit pas de NaN`() {
        assertTrue(Vec3.ZERO.normalized().isFinite())
    }

    @Test
    fun `conversion latitude longitude aller retour`() {
        for (latDeg in -80..80 step 20) {
            for (lonDeg in -170..170 step 40) {
                val lat = latDeg * DEG_TO_RAD
                val lon = lonDeg * DEG_TO_RAD
                val v = Sphere.toVec(lat, lon)
                assertTrue(abs(Sphere.latitude(v) - lat) < 1e-4f, "latitude $latDeg")
                assertTrue(abs(Sphere.longitude(v) - lon) < 1e-4f, "longitude $lonDeg")
            }
        }
    }

    @Test
    fun `distance geodesique entre poles vaut pi`() {
        val d = Sphere.geodesic(Vec3.UNIT_Y, -Vec3.UNIT_Y)
        assertTrue(abs(d - PI_F) < 1e-4f, "obtenu $d")
    }

    @Test
    fun `slerp reste sur la sphere`() {
        val a = Sphere.toVec(0.3f, 0.5f)
        val b = Sphere.toVec(-0.7f, 2.1f)
        for (i in 0..10) {
            val p = Sphere.slerp(a, b, i / 10f)
            assertTrue(abs(p.length - 1f) < 1e-4f, "hors sphère à t=${i / 10f}")
        }
    }

    @Test
    fun `points aleatoires uniformement repartis entre hemispheres`() {
        val rng = Rng(77L)
        var north = 0
        val draws = 20_000
        repeat(draws) { if (Sphere.randomPoint(rng).y > 0f) north++ }
        val ratio = north.toFloat() / draws
        assertTrue(abs(ratio - 0.5f) < 0.02f, "répartition biaisée : $ratio")
    }
}

class SimClockTest {

    @Test
    fun `le pas fixe avance de facon deterministe`() {
        val clock = SimClock(stepSeconds = 0.1f)
        var ticks = 0
        clock.advance(0.25f) { ticks++ }
        assertEquals(2, ticks)
        clock.advance(0.05f) { ticks++ }
        assertEquals(3, ticks)
    }

    @Test
    fun `une image tres longue ne provoque pas de rattrapage infini`() {
        val clock = SimClock(stepSeconds = 0.01f, maxStepsPerFrame = 5)
        var ticks = 0
        clock.advance(60f) { ticks++ }
        assertEquals(5, ticks, "la spirale de la mort n'est pas contenue")
    }

    @Test
    fun `la pause arrete la simulation`() {
        val clock = SimClock(stepSeconds = 0.1f)
        clock.timeScale = 0f
        var ticks = 0
        clock.advance(10f) { ticks++ }
        assertEquals(0, ticks)
    }

    @Test
    fun `l acceleration multiplie le nombre de pas`() {
        val slow = SimClock(stepSeconds = 0.1f, maxStepsPerFrame = 1000)
        val fast = SimClock(stepSeconds = 0.1f, maxStepsPerFrame = 1000)
        fast.timeScale = 10f
        var slowTicks = 0
        var fastTicks = 0
        slow.advance(0.2f) { slowTicks++ }
        fast.advance(0.2f) { fastTicks++ }
        assertTrue(fastTicks > slowTicks * 5, "accélération inopérante")
    }

    @Test
    fun `alpha reste dans zero un`() {
        val clock = SimClock(stepSeconds = 0.1f)
        repeat(50) {
            clock.advance(0.037f) { }
            assertTrue(clock.alpha in 0f..1f, "alpha hors bornes : ${clock.alpha}")
        }
    }
}
