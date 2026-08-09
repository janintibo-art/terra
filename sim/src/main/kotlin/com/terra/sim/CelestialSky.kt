package com.terra.sim

import com.terra.core.Rng
import com.terra.core.Seed
import com.terra.core.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ciel nocturne — lot 2.12. Étoiles et lune, dérivées de la graine :
 * chaque monde a son ciel, reconstruit à l'identique sur tout appareil.
 *
 * Les étoiles sont un semis uniforme sur la sphère céleste (elles ne
 * bougent pas dans le repère monde : c'est la rotation propre de la
 * planète qui fait défiler le ciel, comme pour le soleil). La lune est un
 * corps en orbite circulaire inclinée — sa PHASE n'est pas simulée : la
 * vraie géométrie l'offre, le renderer éclaire la sphère lunaire par la
 * direction du soleil et le croissant apparaît tout seul.
 */
object CelestialSky {

    const val STAR_COUNT = 1100

    /** Jours planétaires d'une lunaison, bornes du tirage. */
    const val MOON_PERIOD_MIN_DAYS = 22f
    const val MOON_PERIOD_SPAN_DAYS = 12f

    /** Inclinaison maximale de l'orbite lunaire sur l'équateur, en radians. */
    const val MOON_INCLINATION_MAX_RAD = 0.24f

    /**
     * Champ d'étoiles : STAR_COUNT × 4 flottants — direction unitaire et
     * magnitude [0, 1]. La magnitude suit u² : beaucoup de poussière, peu
     * de phares, comme un vrai ciel.
     */
    fun generateStars(seed: Seed): FloatArray {
        val rng = Rng(seed.derive("ciel/etoiles").value)
        val out = FloatArray(STAR_COUNT * 4)
        for (i in 0 until STAR_COUNT) {
            // Semis uniforme : y uniforme dans [−1, 1], azimut uniforme.
            val y = rng.nextFloat() * 2f - 1f
            val a = rng.nextFloat() * (2f * com.terra.core.PI_F)
            val r = sqrt((1f - y * y).coerceAtLeast(0f))
            val u = rng.nextFloat()
            out[i * 4] = r * cos(a)
            out[i * 4 + 1] = y
            out[i * 4 + 2] = r * sin(a)
            out[i * 4 + 3] = u * u
        }
        return out
    }

    /** Période de lunaison en jours planétaires, propre au monde. */
    fun moonPeriodDays(seed: Seed): Float {
        val rng = Rng(seed.derive("ciel/lune").value)
        return MOON_PERIOD_MIN_DAYS + rng.nextFloat() * MOON_PERIOD_SPAN_DAYS
    }

    /**
     * Direction de la lune dans le repère monde, unitaire, au tick donné.
     *
     * Orbite circulaire dans un plan incliné : base (A, B) orthonormée
     * construite depuis la graine — A dans le plan équatorial, B relevé de
     * l'inclinaison — et angle orbital linéaire en temps. Périodique par
     * construction : après une lunaison exacte, la direction est identique.
     */
    fun moonDirection(seed: Seed, time: WorldTime, tick: Long): Vec3 {
        val rng = Rng(seed.derive("ciel/lune").value)
        rng.nextFloat()                                   // la période, déjà tirée
        val node = rng.nextFloat() * (2f * com.terra.core.PI_F)
        val incl = (rng.nextFloat() * 2f - 1f) * MOON_INCLINATION_MAX_RAD
        val phase0 = rng.nextFloat()

        // Base du plan orbital : A équatorial au nœud, B = A tourné de 90°
        // dans le plan puis relevé de l'inclinaison autour de A.
        val ax = cos(node); val az = sin(node)
        val bx = -sin(node) * cos(incl)
        val by = sin(incl)
        val bz = cos(node) * cos(incl)

        val periodTicks = (moonPeriodDays(seed).toDouble() *
            time.minutesPerDay / time.minutesPerTick).coerceAtLeast(1.0)
        val theta = ((tick / periodTicks + phase0.toDouble()) % 1.0) *
            2.0 * Math.PI
        val ct = cos(theta).toFloat()
        val st = sin(theta).toFloat()
        return Vec3(
            ax * ct + bx * st,
            by * st,
            az * ct + bz * st
        )
    }
}
