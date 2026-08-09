package com.terra.sim

import kotlin.math.abs
import kotlin.math.min

/**
 * Météo locale visible — lot 2.15.
 *
 * La simulation sait déjà où il pleut, quelle température il fait et
 * quelle saison court. Ce module traduit ces données en un ÉTAT visible
 * au sol : rien, pluie, ou neige, avec une intensité. Toute la décision
 * vit ici, en Kotlin pur testable ; le rendu ne fait qu'obéir.
 *
 * Deux principes :
 *
 *  - La forme dépend de la température du MOMENT, saison comprise : une
 *    plaine tempérée reçoit de la pluie en été et de la neige en hiver.
 *    C'est ce qui rend le cycle des saisons enfin visible au sol.
 *  - L'intensité dépend des précipitations ANNUELLES de la cellule, sur
 *    une échelle comparée aux extrêmes du monde et non à une constante :
 *    une planète sèche montre quand même ses averses relatives.
 */
object LocalWeather {

    /** Aucune précipitation en dessous, en mm/an : les déserts restent secs. */
    const val DRY_THRESHOLD_MM = 250f

    /** Précipitations donnant l'intensité maximale, en mm/an. */
    const val WET_SATURATION_MM = 2200f

    /** Bascule pluie/neige, en °C — l'air à 1,5 °C laisse encore tomber la neige. */
    const val SNOW_TEMP_C = 1.5f

    enum class Form { NONE, RAIN, SNOW }

    data class State(val form: Form, val intensity: Float) {
        val active: Boolean get() = form != Form.NONE && intensity > 0.01f
    }

    val CLEAR = State(Form.NONE, 0f)

    /**
     * État météo d'un point du sol.
     *
     * @param precipMmYear précipitations annuelles de la cellule
     * @param temperatureC température du moment, modulation saisonnière incluse
     * @param overOcean vrai en mer : pas de particules sur l'eau, on ne
     *        voit pas la pluie tomber sur l'océan depuis la surface, et
     *        cela évite d'habiller le globe entier de précipitations.
     */
    fun stateAt(
        precipMmYear: Float,
        temperatureC: Float,
        overOcean: Boolean
    ): State {
        if (overOcean) return CLEAR
        if (precipMmYear <= DRY_THRESHOLD_MM) return CLEAR
        val t = (precipMmYear - DRY_THRESHOLD_MM) /
            (WET_SATURATION_MM - DRY_THRESHOLD_MM)
        val intensity = min(1f, t)
        val form = if (temperatureC <= SNOW_TEMP_C) Form.SNOW else Form.RAIN
        return State(form, intensity)
    }

    /**
     * Nombre de particules à dessiner pour un état donné, borné par le
     * budget. La neige est plus clairsemée que la pluie à intensité égale
     * — des flocons rares se voient, une averse doit strier l'écran.
     */
    fun particleCount(state: State, budget: Int): Int {
        if (!state.active) return 0
        val share = when (state.form) {
            Form.RAIN -> state.intensity
            Form.SNOW -> state.intensity * 0.55f
            Form.NONE -> 0f
        }
        return (budget * share).toInt().coerceIn(0, budget)
    }

    /**
     * Vitesse de chute, en mètres par seconde : une goutte tombe vite et
     * droit, un flocon lentement. Sert au rendu à animer la colonne de
     * particules sans état persistant — la position se déduit du temps.
     */
    fun fallSpeedMS(form: Form): Float = when (form) {
        Form.RAIN -> 9f
        Form.SNOW -> 1.1f
        Form.NONE -> 0f
    }
}
