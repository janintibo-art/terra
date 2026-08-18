package com.terra.sim

import com.terra.core.DEG_TO_RAD
import com.terra.core.TAU
import kotlin.math.cos
import kotlin.math.sin

/**
 * Temps planétaire — branche enfin [com.terra.core.SimClock] sur le monde.
 *
 * Jusqu'à la v0.2 aucun temps ne s'écoulait dans Terra : le soleil tournait à
 * une vitesse arbitraire, sans rapport avec quoi que ce soit. Ici, un tick de
 * simulation vaut une durée planétaire précise, dont découlent la rotation de la
 * planète, la position du soleil, la saison et la date.
 *
 * Toute la Phase 5 (temps profond, rattrapage hors écran, tests de rejeu)
 * s'appuiera sur cette conversion tick → date.
 */
class WorldTime(
    /** Minutes de temps planétaire écoulées à chaque pas de simulation. */
    val minutesPerTick: Float = 1f,
    /** Durée du jour, en minutes planétaires. Terre : 1440. */
    val minutesPerDay: Int = 1440,
    /** Durée de l'année, en jours. Terre : 365. */
    val daysPerYear: Int = 360,
    /** Inclinaison de l'axe, en degrés. Détermine l'amplitude des saisons. */
    val axialTiltDeg: Float = 23.4f
) {

    fun totalMinutes(tick: Long): Double = tick.toDouble() * minutesPerTick

    fun totalDays(tick: Long): Double = totalMinutes(tick) / minutesPerDay

    /** Année en cours, à partir de 1. */
    fun year(tick: Long): Long = (totalDays(tick) / daysPerYear).toLong() + 1

    /** Jour dans l'année, à partir de 1. */
    fun dayOfYear(tick: Long): Int =
        (totalDays(tick).toLong() % daysPerYear).toInt() + 1

    /** Position dans la journée, dans [0, 1). 0 = minuit. */
    fun dayFraction(tick: Long): Float {
        val d = totalDays(tick)
        return (d - kotlin.math.floor(d)).toFloat()
    }

    /** Position dans l'année, dans [0, 1). 0 = équinoxe de printemps boréal. */
    fun yearFraction(tick: Long): Float {
        val y = totalDays(tick) / daysPerYear
        return (y - kotlin.math.floor(y)).toFloat()
    }

    /**
     * Ticks à avancer pour atteindre un jour de l'année — support de la
     * commande console `saison` (lot 3.4).
     *
     * Toujours vers l'AVANT, comme [SolarTime.ticksUntilLocalHour] : le
     * temps de Terra ne revient pas en arrière, et rien dans la simulation
     * n'est prévu pour cela avant le rattrapage de la Phase 5.
     *
     * Le saut porte sur un nombre ENTIER de jours, ce qui préserve l'heure
     * locale : on change de saison sans changer de moment de la journée, et
     * deux captures prises à deux saisons restent comparables — même
     * lumière, même ombre portée.
     */
    fun ticksUntilDayOfYear(tick: Long, targetDay: Int): Long {
        val ticksPerDay = minutesPerDay.toDouble() / minutesPerTick
        val target = (((targetDay - 1) % daysPerYear) + daysPerYear) % daysPerYear
        val current = dayOfYear(tick) - 1
        val forward = (((target - current) % daysPerYear) + daysPerYear) % daysPerYear
        return kotlin.math.round(forward * ticksPerDay).toLong()
    }

    /** Heure et minute planétaires, pour l'affichage. */
    fun clockTime(tick: Long): Pair<Int, Int> {
        val minuteOfDay = (totalMinutes(tick).toLong() % minutesPerDay).toInt()
        return Pair(minuteOfDay / 60, minuteOfDay % 60)
    }

    /** Angle de rotation propre de la planète, en degrés. */
    fun spinDegrees(tick: Long): Float = dayFraction(tick) * 360f

    /**
     * Déclinaison solaire : angle du soleil au-dessus du plan équatorial.
     *
     * C'est elle qui crée les saisons. À l'équinoxe elle vaut zéro et les deux
     * hémisphères reçoivent autant de lumière ; au solstice elle atteint
     * l'inclinaison de l'axe, plongeant un pôle dans la nuit permanente et
     * l'autre dans le jour continu.
     */
    fun sunDeclinationDeg(tick: Long): Float =
        axialTiltDeg * sin(TAU * yearFraction(tick))

    /**
     * Direction du soleil dans le repère du monde, vecteur unitaire.
     * La planète tourne sous un soleil fixe en direction, dont seule la hauteur
     * varie avec la saison.
     */
    fun sunDirection(tick: Long): FloatArray {
        val decl = sunDeclinationDeg(tick) * DEG_TO_RAD
        return floatArrayOf(cos(decl), sin(decl), 0f)
    }

    /** Saison de l'hémisphère nord. Le sud vit la saison opposée. */
    fun seasonNorth(tick: Long): Season {
        val f = yearFraction(tick)
        return when {
            f < 0.25f -> Season.SPRING
            f < 0.50f -> Season.SUMMER
            f < 0.75f -> Season.AUTUMN
            else -> Season.WINTER
        }
    }

    enum class Season(val label: String) {
        SPRING("printemps"),
        SUMMER("été"),
        AUTUMN("automne"),
        WINTER("hiver")
    }

    /** Résumé lisible affiché dans le HUD. */
    fun format(tick: Long): String {
        val (h, m) = clockTime(tick)
        return "An %d · jour %d · %02d:%02d · %s".format(
            year(tick), dayOfYear(tick), h, m, seasonNorth(tick).label
        )
    }
}
