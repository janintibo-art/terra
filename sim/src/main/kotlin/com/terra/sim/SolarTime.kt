package com.terra.sim

import com.terra.core.TAU
import kotlin.math.roundToLong

/**
 * Heure solaire locale — support de la commande console `soleil <heure>`.
 *
 * ## Le principe
 *
 * Dans [WorldTime], le soleil est fixe en direction (seule sa hauteur varie
 * avec la saison) et la planète tourne sous lui : la **longitude subsolaire**
 * — le méridien où il est midi — est donc exactement l'angle de rotation
 * propre. L'heure locale d'un méridien s'en déduit par une règle de trois, et
 * atteindre une heure voulue revient à avancer l'horloge du bon nombre de
 * ticks, jamais à la reculer : le temps de Terra ne revient pas en arrière.
 *
 * ## Pourquoi la convention est testée, pas déduite
 *
 * Le sens de rotation relie trois conventions (matrice modèle du rendu,
 * formule monde → planète du soleil, longitude géodésique) : c'est le terrain
 * de chasse favori des erreurs de signe qui se compensent (repère v0.6). Un
 * test vérifie donc la propriété physique elle-même : après `soleil 12`, le
 * méridien visé reçoit l'éclairement maximal de sa journée.
 */
object SolarTime {

    /**
     * Heure solaire locale d'un méridien, dans [0, 24).
     *
     * Convention identique au renderer : le soleil monde est ramené dans le
     * repère planète par la rotation propre inverse, et sa longitude —
     * `atan2` de ses composantes horizontales — se réduit analytiquement à
     * l'angle de rotation.
     */
    fun localHour(time: WorldTime, tick: Long, lonRad: Double): Double {
        val subsolarLonRad = time.spinDegrees(tick).toDouble() * Math.PI / 180.0
        val hour = 12.0 + (lonRad - subsolarLonRad) * (24.0 / TAU)
        return ((hour % 24.0) + 24.0) % 24.0
    }

    /**
     * Nombre de ticks à avancer pour que le méridien atteigne l'heure voulue.
     * Toujours positif ou nul, borné par une journée.
     *
     * Le sens de défilement de l'heure locale (croît-elle ou décroît-elle
     * quand le temps avance ?) dépend de l'enchaînement de trois conventions
     * de signe. Plutôt que de le déduire sur le papier — le raisonnement à
     * deux signes a déjà produit le repère inversé de la v0.6 —, on le
     * **mesure** sur un pas d'horloge : la fonction reste juste même si une
     * convention amont change un jour.
     */
    fun ticksUntilLocalHour(
        time: WorldTime,
        tick: Long,
        lonRad: Double,
        targetHour: Double
    ): Long {
        val ticksPerDay = time.minutesPerDay / time.minutesPerTick.toDouble()
        val current = localHour(time, tick, lonRad)
        val deltaForward = (((targetHour - current) % 24.0) + 24.0) % 24.0
        if (deltaForward < 1e-9 || deltaForward > 24.0 - 1e-9) return 0L

        // Mesure du sens : de combien l'heure locale bouge-t-elle sur un
        // quart de jour ? (un seul tick serait noyé dans les arrondis)
        val probe = (ticksPerDay / 4.0).roundToLong()
        val after = localHour(time, tick + probe, lonRad)
        val moved = (((after - current) % 24.0) + 24.0) % 24.0
        val hourAdvancesWithTime = moved < 12.0

        val hoursToTravel = if (hourAdvancesWithTime) deltaForward else 24.0 - deltaForward
        return (hoursToTravel / 24.0 * ticksPerDay).roundToLong()
    }
}
