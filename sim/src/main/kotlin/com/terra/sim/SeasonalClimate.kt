package com.terra.sim

import com.terra.core.DEG_TO_RAD
import kotlin.math.abs
import kotlin.math.sin

/**
 * Saisons thermiques — lot 1.12.
 *
 * Le monde généré reste une MOYENNE annuelle : rien ici ne touche aux
 * tableaux hachés par l'empreinte, et GENERATION_VERSION ne bouge pas. La
 * saison est une modulation pure, évaluée à la volée :
 *
 *     T(cellule, t) = temperatureC[cellule] + deltaC(cellule, t)
 *
 * L'amplitude est calibrée sur dix-sept stations terrestres réelles, de
 * Singapour (±1 °C) à Iakoutsk (±29 °C) — erreur médiane 13 %, toutes les
 * stations dans un facteur 2 (`validation/saisons_calibrage.py`). Deux
 * enseignements du calibrage : l'excursion d'insolation croît en sin φ dès
 * les basses latitudes (une première mouture en sin²φ écrasait la bande
 * subtropicale), et les rétroactions continentales — neige, air sec —
 * exigent le terme quadratique aux hautes latitudes.
 *
 * Le pic saisonnier est en retard sur le solstice : ~27 jours au cœur des
 * continents, ~55 jours en mer, interpolé par la continentalité. Le retard
 * s'applique en évaluant la déclinaison à (tick − retard) : la forme
 * sinusoïdale, la période et l'antisymétrie des hémisphères sont héritées
 * de [WorldTime.sunDeclinationDeg], déjà testée.
 */
object SeasonalClimate {

    // Demi-amplitudes en °C à l'inclinaison de référence, ajustées par
    // recherche par coordonnées sur les stations (médiane 13 %, pire 47 %).
    const val A_MARINE_0 = 0.82f
    const val A_MARINE_S = 6.38f
    const val A_CONT_0 = 0.74f
    const val A_CONT_S = 13.10f
    const val A_CONT_S2 = 20.99f

    /**
     * La SURFACE océanique varie moitié moins que l'air côtier : l'eau
     * brasse sa chaleur sur des dizaines de mètres d'épaisseur.
     */
    const val OCEAN_SURFACE_DAMP = 0.49f

    /** Retard du pic thermique sur le solstice, en jours. */
    const val LAG_DAYS_OCEAN = 55f
    const val LAG_DAYS_SPAN = 28f   // mer 55 j → cœur continental 27 j

    /** Inclinaison de calibrage : celle de la Terre. */
    const val REF_TILT_DEG = 23.4f

    /**
     * Demi-amplitude saisonnière en °C : de combien la température locale
     * s'écarte de la moyenne annuelle au pic de l'été.
     *
     * @param sinLatAbs |sin(latitude)|, la coordonnée y absolue du sommet.
     * @param continentality01 continentalité [0, 1] calculée au climat.
     * @param oceanSurface vrai pour une cellule marine.
     * @param tiltDeg inclinaison axiale du monde ; l'amplitude suit
     *   sin(inclinaison) — nulle pour une planète droite, saisons féroces
     *   pour une planète couchée.
     */
    fun amplitudeC(
        sinLatAbs: Float,
        continentality01: Float,
        oceanSurface: Boolean,
        tiltDeg: Float
    ): Float {
        val s = sinLatAbs.coerceIn(0f, 1f)
        val c = continentality01.coerceIn(0f, 1f)
        val marine = A_MARINE_0 + A_MARINE_S * s
        val continental = A_CONT_0 + A_CONT_S * s + A_CONT_S2 * s * s
        var a = marine + (continental - marine) * c
        if (oceanSurface) a *= OCEAN_SURFACE_DAMP
        val tiltFactor = sin(abs(tiltDeg) * DEG_TO_RAD) / sin(REF_TILT_DEG * DEG_TO_RAD)
        return a * tiltFactor
    }

    /** Retard du pic thermique sur le solstice, en jours. */
    fun lagDays(continentality01: Float): Float =
        LAG_DAYS_OCEAN - LAG_DAYS_SPAN * continentality01.coerceIn(0f, 1f)

    /**
     * Écart saisonnier signé, en °C, à ajouter à la moyenne annuelle.
     *
     * @param sinLat sin(latitude) SIGNÉ — l'hémisphère sud vit la saison
     *   inverse, et c'est la déclinaison qui porte le calendrier.
     */
    fun deltaC(
        sinLat: Float,
        continentality01: Float,
        oceanSurface: Boolean,
        time: WorldTime,
        tick: Long
    ): Float {
        val tilt = time.axialTiltDeg
        if (abs(tilt) < 0.01f) return 0f

        val amp = amplitudeC(abs(sinLat), continentality01, oceanSurface, tilt)

        // Déclinaison évaluée dans le passé, du retard thermique local. Le
        // repli sur l'année précédente évite un tick négatif au premier
        // printemps : la déclinaison est périodique, l'ajout d'une année
        // exacte ne change rien d'autre.
        val lagTicks = (lagDays(continentality01) *
            time.minutesPerDay / time.minutesPerTick).toLong()
        val yearTicks = (time.daysPerYear.toDouble() * time.minutesPerDay /
            time.minutesPerTick).toLong().coerceAtLeast(1L)
        var lagged = tick - lagTicks
        while (lagged < 0) lagged += yearTicks

        // decl/tilt parcourt [−1, +1] sur l'année : c'est la phase. Le signe
        // de sin(latitude) inverse la saison dans l'hémisphère sud.
        val phase = time.sunDeclinationDeg(lagged) / tilt
        val hemisphere = if (sinLat >= 0f) 1f else -1f
        return amp * phase * hemisphere
    }
}
