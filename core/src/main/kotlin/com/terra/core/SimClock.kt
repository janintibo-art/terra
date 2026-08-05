package com.terra.core

/**
 * Horloge de simulation à pas fixe — lot 0.5.
 *
 * ## Pourquoi un pas fixe
 *
 * Si la simulation avance de `deltaTemps` variable à chaque image, deux
 * exécutions du même monde divergent dès que la cadence d'affichage varie : le
 * déterminisme est perdu, et avec lui la reproductibilité et les tests de rejeu.
 *
 * Le pas fixe résout cela : la simulation avance toujours par tranches
 * identiques. Le rendu, lui, interpole entre le dernier état et le suivant via
 * [alpha], ce qui reste fluide même si la cadence n'est pas un multiple du pas.
 *
 * ## Garde-fou
 *
 * [maxStepsPerFrame] évite la « spirale de la mort » : si le téléphone rame, la
 * simulation ne tente pas de rattraper indéfiniment, ce qui aggraverait le
 * ralentissement. Elle accepte de prendre du retard, et le rattrapage massif est
 * traité séparément par le mécanisme de temps profond (Phase 5).
 */
class SimClock(
    /** Durée d'un pas de simulation, en secondes de temps réel. */
    val stepSeconds: Float = 1f / 30f,
    /** Nombre maximal de pas rattrapés en une seule image. */
    val maxStepsPerFrame: Int = 5
) {

    /** Nombre total de pas exécutés depuis la création du monde. */
    var tick: Long = 0L
        private set

    /** Multiplicateur de vitesse : 0 = pause, 1 = temps réel, 100 = accéléré. */
    var timeScale: Float = 1f

    private var accumulator: Float = 0f

    /** Position dans le pas courant, dans [0, 1). Sert à interpoler le rendu. */
    val alpha: Float get() = clamp01(accumulator / stepSeconds)

    /** Temps de simulation écoulé, en secondes. */
    val elapsedSeconds: Double get() = tick.toDouble() * stepSeconds

    /**
     * Consomme le temps réel écoulé et exécute les pas de simulation dus.
     *
     * @param realDeltaSeconds temps réel depuis le dernier appel
     * @param step action exécutée pour chaque pas ; reçoit le numéro du tick
     * @return nombre de pas effectivement exécutés
     */
    fun advance(realDeltaSeconds: Float, step: (Long) -> Unit): Int {
        if (timeScale <= 0f) return 0

        // Une image anormalement longue (reprise de veille, blocage) ne doit pas
        // injecter un delta géant dans l'accumulateur.
        val dt = clamp(realDeltaSeconds, 0f, 0.25f) * timeScale
        accumulator += dt

        var steps = 0
        while (accumulator >= stepSeconds && steps < maxStepsPerFrame) {
            accumulator -= stepSeconds
            tick++
            step(tick)
            steps++
        }

        // Retard irrécupérable : on abandonne le reliquat plutôt que de
        // s'enfoncer. Le monde perd du temps, mais reste réactif.
        if (accumulator > stepSeconds * maxStepsPerFrame) {
            accumulator = stepSeconds * maxStepsPerFrame
        }
        return steps
    }

    /** Avance d'un nombre exact de pas, sans notion de temps réel (rattrapage, tests). */
    fun runExact(steps: Int, step: (Long) -> Unit) {
        repeat(steps) {
            tick++
            step(tick)
        }
    }

    fun reset() {
        tick = 0L
        accumulator = 0f
    }

    /** Restaure l'horloge depuis une sauvegarde. */
    fun restore(tick: Long) {
        this.tick = tick
        accumulator = 0f
    }
}
