package com.terra.sim

import com.terra.core.DEG_TO_RAD
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolarTimeTest {

    private val time = WorldTime()

    @Test
    fun `sauter vers une heure aboutit exactement a cette heure`() {
        val rng = Random(3)
        repeat(200) {
            val tick = rng.nextLong(0, 5_000_000)
            val lon = (rng.nextDouble() - 0.5) * 2.0 * Math.PI
            val target = rng.nextDouble() * 24.0

            val jump = SolarTime.ticksUntilLocalHour(time, tick, lon, target)
            assertTrue(jump >= 0, "le temps ne recule jamais : $jump")
            assertTrue(jump <= 1441, "le saut dépasse une journée : $jump")

            val reached = SolarTime.localHour(time, tick + jump, lon)
            // Un tick vaut une minute planétaire : la cible est atteinte à une
            // demi-minute près, soit 1/120 d'heure — l'arrondi du saut, rien
            // d'autre. Comparaison circulaire pour ne pas confondre 23,99 et 0.
            val gap = abs(((reached - target + 12.0).mod(24.0)) - 12.0)
            assertTrue(gap < 1.0 / 120.0 + 1e-9, "cible $target, atteint $reached")
        }
    }

    @Test
    fun `a midi local le meridien recoit l eclairement maximal de sa journee`() {
        // La propriété physique elle-même, indépendante de toute convention de
        // signe : après « soleil 12 », le point visé doit être plus éclairé
        // qu'à tout autre moment de sa journée. C'est ce test qui protège la
        // commande contre le piège des signes qui se compensent (v0.6).
        val latRad = 0.35
        for (lonDeg in intArrayOf(-150, -60, 0, 45, 170)) {
            val lon = lonDeg * DEG_TO_RAD.toDouble()
            val noonTick = 100_000L +
                    SolarTime.ticksUntilLocalHour(time, 100_000L, lon, 12.0)

            val atNoon = illumination(noonTick, latRad, lon)
            var maxOverDay = Double.NEGATIVE_INFINITY
            val ticksPerDay = (time.minutesPerDay / time.minutesPerTick).toLong()
            var t = noonTick
            while (t < noonTick + ticksPerDay) {
                val v = illumination(t, latRad, lon)
                if (v > maxOverDay) maxOverDay = v
                t += 15
            }
            assertTrue(
                atNoon >= maxOverDay - 1e-3,
                "lon $lonDeg° : midi $atNoon, maximum du jour $maxOverDay"
            )
        }
    }

    /**
     * Éclairement d'un point : produit scalaire entre sa direction et le
     * soleil ramené dans le repère planète — la formule exacte du renderer.
     */
    private fun illumination(tick: Long, latRad: Double, lonRad: Double): Double {
        val sun = time.sunDirection(tick)
        val spin = time.spinDegrees(tick) * DEG_TO_RAD
        val c = cos(spin).toDouble()
        val s = sin(spin).toDouble()
        val sunLx = sun[0] * c - sun[2] * s
        val sunLz = sun[0] * s + sun[2] * c

        val px = cos(latRad) * cos(lonRad)
        val py = sin(latRad)
        val pz = cos(latRad) * sin(lonRad)
        return px * sunLx + py * sun[1] + pz * sunLz
    }

    @Test
    fun `une heure deja atteinte ne fait pas sauter le temps`() {
        val lon = 0.7
        val tick = 42_000L
        val current = SolarTime.localHour(time, tick, lon)
        assertEquals(0L, SolarTime.ticksUntilLocalHour(time, tick, lon, current))
    }
}

class TimeDilationTest {

    @Test
    fun `plein regime en orbite et plancher au sol`() {
        val cam = PlanetCamera(6_371_000.0)

        cam.rangeM = 16_000_000.0
        assertEquals(1.0, cam.timeDilationFactor(), 1e-12)

        cam.rangeM = 2.0
        assertEquals(PlanetCamera.MIN_TIME_DILATION, cam.timeDilationFactor(), 1e-12)
    }

    @Test
    fun `la dilatation est monotone en la distance`() {
        val cam = PlanetCamera(6_371_000.0)
        var previous = 0.0
        for (range in doubleArrayOf(2.0, 50.0, 700.0, 27_000.0, 300_000.0, 2_000_000.0, 20_000_000.0)) {
            cam.rangeM = range
            val f = cam.timeDilationFactor()
            assertTrue(f >= previous, "non monotone à $range m : $f < $previous")
            assertTrue(f in PlanetCamera.MIN_TIME_DILATION..1.0)
            previous = f
        }
    }

    @Test
    fun `a 27 km le jour dure environ une heure reelle`() {
        // Le chiffre annoncé à l'utilisateur, vérifié : 48 s / facteur ≈ 59 min.
        val cam = PlanetCamera(6_371_000.0, rangeM = 27_000.0)
        val dayRealSeconds = 48.0 / cam.timeDilationFactor()
        assertTrue(
            dayRealSeconds in 3_000.0..4_000.0,
            "jour réel à 27 km : $dayRealSeconds s"
        )
    }

    // ------------------------------------- saut de calendrier (lot 3.4)

    @Test
    fun leSautDeSaisonAtteintLeJourVoulu() {
        val time = WorldTime()
        val ticksPerDay = (time.minutesPerDay / time.minutesPerTick).toLong()
        for (depart in listOf(0L, 37L * ticksPerDay, 359L * ticksPerDay)) {
            for (cible in listOf(1, 91, 181, 271, 360)) {
                val apres = depart + time.ticksUntilDayOfYear(depart, cible)
                assertEquals(
                    cible, time.dayOfYear(apres),
                    "depuis le jour ${time.dayOfYear(depart)} vers $cible"
                )
            }
        }
    }

    @Test
    fun leSautDeSaisonNeRemonteJamaisLeTemps() {
        // Le temps de Terra ne revient pas en arrière : rien dans la
        // simulation n'est prévu pour cela avant le rattrapage de la Phase 5.
        val time = WorldTime()
        val tick = 200L * (time.minutesPerDay / time.minutesPerTick).toLong()
        for (cible in 1..360) {
            assertTrue(
                time.ticksUntilDayOfYear(tick, cible) >= 0L,
                "saut négatif vers le jour $cible"
            )
        }
    }

    @Test
    fun leSautDeSaisonPreserveLHeureLocale() {
        // Un saut d'un nombre ENTIER de jours : deux captures prises à deux
        // saisons gardent la même lumière et la même ombre portée, donc se
        // comparent.
        val time = WorldTime()
        val ticksPerDay = (time.minutesPerDay / time.minutesPerTick).toLong()
        val midi = 143L * ticksPerDay + ticksPerDay / 2
        val apres = midi + time.ticksUntilDayOfYear(midi, 271)
        assertEquals(time.dayFraction(midi), time.dayFraction(apres), 1e-6f)
    }
}
