package com.terra.sim

/**
 * Ce que le temps fait VRAIMENT, du point de vue de l'observateur.
 *
 * Le multiplicateur affiché sur les boutons (×1, ×20, ×200) n'est pas la
 * vitesse à laquelle le monde avance : en descente, `PlanetCamera` dilate le
 * temps selon l'altitude, pour qu'un cycle jour/nuit ne balaye pas le
 * paysage en un clin d'œil au ras du sol. Le rapport atteint 2 880 entre
 * l'orbite et le sol — assez pour qu'un même bouton fasse passer un jour
 * planétaire en 0,2 seconde ou en 12 minutes, sans que rien ne l'annonce.
 *
 * Ce calcul vit dans `:sim` plutôt que dans le HUD parce qu'il est testable
 * ici, et parce qu'un chiffre affiché à l'utilisateur mérite le même filet
 * que le reste de la simulation.
 */
object TimeReadout {

    /**
     * Vitesse réellement appliquée à l'horloge : le multiplicateur choisi,
     * multiplié par la dilatation liée à l'altitude (1 hors descente).
     */
    fun effectiveScale(timeScale: Float, dilation: Double): Double =
        timeScale.toDouble() * dilation

    /**
     * Durée réelle d'un jour planétaire, en secondes, ou une valeur infinie
     * si le temps est en pause — l'appelant l'affiche alors comme un arrêt
     * plutôt que comme une durée absurde.
     */
    fun dayRealSeconds(time: WorldTime, timeScale: Float, dilation: Double, stepSeconds: Float): Double {
        val scale = effectiveScale(timeScale, dilation)
        if (scale <= 0.0) return Double.POSITIVE_INFINITY
        // Ticks par jour × secondes réelles par tick, ralenties d'autant que
        // l'horloge est accélérée.
        val ticksPerDay = time.minutesPerDay / time.minutesPerTick.toDouble()
        return ticksPerDay * stepSeconds / scale
    }

    /**
     * Durée formatée pour le HUD : secondes, minutes ou heures selon
     * l'ordre de grandeur. Les seuils sont ceux de la lisibilité — au-delà
     * de 90 s on ne compte plus en secondes, au-delà de 90 min plus en
     * minutes.
     */
    fun formatDuration(seconds: Double): String {
        if (!seconds.isFinite()) return "—"
        return when {
            seconds < 90.0 -> "${fmt1(seconds)} s"
            seconds < 5_400.0 -> "${fmt0(seconds / 60.0)} min"
            else -> "${fmt1(seconds / 3_600.0)} h"
        }
    }

    /**
     * Vitesse effective formatée. Sous ×1 on passe en fraction : « ×1/14 »
     * se lit mieux que « ×0,07 » quand on cherche à comprendre pourquoi le
     * soleil ne bouge pas.
     */
    fun formatScale(scale: Double): String = when {
        scale <= 0.0 -> "pause"
        scale >= 10.0 -> "×${fmt0(scale)}"
        scale >= 1.0 -> "×${fmt1(scale)}"
        else -> "×1/${fmt0(1.0 / scale)}"
    }

    private fun fmt0(v: Double): String = Math.round(v).toString()

    private fun fmt1(v: Double): String {
        val r = Math.round(v * 10.0)
        return "${r / 10}," + (r % 10).toString()
    }
}
