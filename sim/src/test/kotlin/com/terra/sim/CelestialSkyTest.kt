package com.terra.sim

import com.terra.core.Seed
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ciel nocturne — lot 2.12. Étoiles et orbite lunaire, dérivées de la graine. */
class CelestialSkyTest {

    private val seed = Seed.fromText("gaia")
    private val time = WorldTime()

    @Test
    fun `le champ d etoiles est unitaire, borne et propre au monde`() {
        val stars = CelestialSky.generateStars(seed)
        assertEquals(CelestialSky.STAR_COUNT * 4, stars.size)
        for (i in 0 until CelestialSky.STAR_COUNT) {
            val x = stars[i * 4]; val y = stars[i * 4 + 1]; val z = stars[i * 4 + 2]
            val len = sqrt(x * x + y * y + z * z)
            assertTrue(abs(len - 1f) < 1e-3f, "étoile $i non unitaire : $len")
            assertTrue(stars[i * 4 + 3] in 0f..1f, "magnitude hors bornes")
        }
        assertTrue(stars.contentEquals(CelestialSky.generateStars(seed)), "ciel non déterministe")
        assertTrue(
            !stars.contentEquals(CelestialSky.generateStars(Seed.fromText("kaleth"))),
            "deux mondes partagent le même ciel"
        )
    }

    @Test
    fun `la lune est unitaire, periodique et inclinee dans les bornes`() {
        val period = CelestialSky.moonPeriodDays(seed)
        assertTrue(
            period >= CelestialSky.MOON_PERIOD_MIN_DAYS &&
                period <= CelestialSky.MOON_PERIOD_MIN_DAYS + CelestialSky.MOON_PERIOD_SPAN_DAYS
        )
        val periodTicks = (period.toDouble() * time.minutesPerDay /
            time.minutesPerTick).toLong()
        var maxY = 0f
        for (d in 0 until 40) {
            val tick = d.toLong() * time.minutesPerDay
            val m = CelestialSky.moonDirection(seed, time, tick)
            val len = sqrt(m.x * m.x + m.y * m.y + m.z * m.z)
            assertTrue(abs(len - 1f) < 1e-3f, "lune non unitaire au jour $d")
            if (abs(m.y) > maxY) maxY = abs(m.y)
            // Périodicité : une lunaison exacte plus tard, même direction.
            val p = CelestialSky.moonDirection(seed, time, tick + periodTicks)
            assertTrue(
                abs(p.x - m.x) < 5e-3f && abs(p.y - m.y) < 5e-3f && abs(p.z - m.z) < 5e-3f,
                "orbite non périodique au jour $d"
            )
        }
        // L'inclinaison borne l'excursion hors du plan équatorial :
        // |y| ≤ sin(inclinaison max), avec la marge de l'échantillonnage.
        assertTrue(
            maxY <= kotlin.math.sin(CelestialSky.MOON_INCLINATION_MAX_RAD) + 1e-3f,
            "excursion polaire $maxY au-delà de l'inclinaison maximale"
        )
        assertTrue(maxY > 0.001f, "orbite parfaitement équatoriale : tirage suspect")
    }
}
