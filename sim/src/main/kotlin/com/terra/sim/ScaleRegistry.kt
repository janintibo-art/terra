package com.terra.sim

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Registres d'échelle — lot 2.7-a.
 *
 * ## Ce que les registres SONT, et ce qu'ils ne sont pas
 *
 * La caméra est volontairement continue : pan proportionnel à la distance,
 * inclinaison et dilatation temporelle en formules sans seuil. C'est ce qui
 * rend la navigation utilisable sur sept ordres de grandeur, et on n'y touche
 * pas. Les registres ne re-quantifient donc AUCUNE vitesse ni aucun geste —
 * la feuille de route parlait d'« ajustement de la vitesse de déplacement »,
 * mais la loi continue du pan fait déjà ce travail, mieux qu'un palier.
 *
 * Un registre est une ÉTIQUETTE de navigation : elle dit quelle géographie
 * remplit l'écran. Elle sert au HUD dès ce lot, et servira de routeur de
 * rendu au 2.7-b (au-dessus du registre orbite, le quadtree cède la place au
 * globe raffiné, dont le limbe est rond par construction).
 *
 * ## D'où viennent les frontières (validation/registres.py)
 *
 * Le critère est la distance de l'horizon au sol — la seule grandeur qui
 * décrive le contenu de la vue indépendamment de l'inclinaison :
 *
 *  - **sol** < 700 m : l'ancre existante (portée où la dilatation temporelle
 *    atteint son plancher, 2 000 km / 2 880 = 694 m), confirmée par un
 *    critère indépendant — l'horizon tient dans un rayon de cellule de la
 *    grille (977 m). Deux raisonnements, même décade : on garde l'ancre.
 *  - **local** < 20 km : l'horizon embrasse 500 km, un massif.
 *  - **régional** < 180 km : l'horizon embrasse 1 500 km, un demi-continent.
 *  - **continental** < 2 000 km : borné par l'ancre existante
 *    [PlanetCamera.TILT_OPEN_RANGE_M], où l'inclinaison s'ouvre — où l'on
 *    cesse de regarder une planète pour se poser dessus.
 *  - **orbite** au-delà.
 *
 * ## Pourquoi une hystérésis
 *
 * L'inertie de fin de geste fait ballotter l'altitude de quelques pour cent.
 * Sans bande morte, un arrêt pile sur une frontière ferait clignoter le HUD —
 * et, au 2.7-b, clignoter le SYSTÈME DE RENDU, ce qui serait autrement plus
 * grave. La bande est en ratio (l'échelle est logarithmique) : ×1,12 de part
 * et d'autre, soit ×1,25 au total — plus large que le ballottement, et
 * vingt fois plus étroite que l'écart minimal entre deux frontières (×9).
 */
enum class ScaleRegister(val label: String) {
    // L'ordre de déclaration est l'ordre des altitudes : ordinal croissant =
    // registre plus haut. Les tests s'appuient dessus.
    GROUND("sol"),
    LOCAL("local"),
    REGIONAL("régional"),
    CONTINENTAL("continental"),
    ORBIT("orbite");
}

class ScaleRegistry(initialAltitudeM: Double = 24_000_000.0) {

    /** Registre courant, stabilisé par l'hystérésis. */
    var current: ScaleRegister = classify(initialAltitudeM)
        private set

    /**
     * Met à jour le registre depuis l'altitude de l'œil au-dessus de la mer.
     *
     * L'altitude MARINE, pas la hauteur sol : le registre décrit l'échelle de
     * la vue, et survoler un plateau à 5 000 m depuis 6 000 m d'altitude est
     * bien une vue locale — c'est la distance d'horizon qui compte, et elle
     * dépend de l'altitude marine. (La hauteur sol, elle, gouverne le plan
     * proche : deux usages, deux grandeurs, comme pour nearPlaneFor.)
     *
     * Retourne vrai si le registre a changé — le 2.7-b s'en servira pour
     * déclencher la transition de rendu une seule fois, pas à chaque image.
     */
    fun update(eyeAltitudeM: Double): Boolean {
        val raw = classify(eyeAltitudeM)
        if (raw == current) return false

        // Les frontières se franchissent UNE À UNE, chacune exigeant d'avoir
        // dépassé sa bande (× BAND en montant, ÷ BAND en descendant). Le
        // piège évité : tester la seule bande du registre CIBLE. Descendre
        // d'orbite à 175 km — dans la bande de la frontière 180 km — doit
        // donner « continental » (la frontière 2 000 km est franchie très
        // franchement), pas rester « orbite » parce que la cible « régional »
        // n'a pas encore purgé SA bande. Une téléportation console traverse
        // ainsi tous les échelons d'un coup : l'hystérésis protège du
        // ballottement, pas du voyage.
        val ladder = ScaleRegister.values()
        var next = current
        if (raw.ordinal > current.ordinal) {
            while (next.ordinal < raw.ordinal &&
                eyeAltitudeM > UPPER_BOUND_M[next.ordinal] * BAND
            ) next = ladder[next.ordinal + 1]
        } else {
            while (next.ordinal > raw.ordinal &&
                eyeAltitudeM < UPPER_BOUND_M[next.ordinal - 1] / BAND
            ) next = ladder[next.ordinal - 1]
        }
        val changed = next != current
        current = next
        return changed
    }

    companion object {
        /**
         * Frontières hautes de chaque registre, en mètres d'altitude marine.
         * Calibrées par validation/registres.py — les modifier là-bas d'abord.
         */
        val UPPER_BOUND_M = doubleArrayOf(
            700.0,          // sol → local
            20_000.0,       // local → régional
            180_000.0,      // régional → continental
            2_000_000.0,    // continental → orbite (= TILT_OPEN_RANGE_M)
            Double.MAX_VALUE
        )

        /** Demi-bande d'hystérésis, en ratio. */
        const val BAND = 1.12

        /** Classification brute, sans hystérésis. */
        fun classify(eyeAltitudeM: Double): ScaleRegister {
            val values = ScaleRegister.values()
            for (i in UPPER_BOUND_M.indices) {
                if (eyeAltitudeM < UPPER_BOUND_M[i]) return values[i]
            }
            return ScaleRegister.ORBIT
        }

        /**
         * Plan de coupe lointain, en mètres.
         *
         * Formule DÉPLACÉE du rendu de la descente (:app, v0.38.1), à
         * l'identique — l'égalité bit à bit avec l'ancienne écriture est
         * vérifiée par validation/registres.py et par un test. On la déplace
         * parce que c'est un calcul d'écran formulable en Kotlin pur : le
         * corollaire de l'état du projet le veut dans :sim, testé, le
         * rendu n'en étant que le miroir.
         *
         * La règle : englober l'horizon (distance oblique au limbe) et les
         * montagnes qui le dépassent ; le facteur 1,8 absorbe l'inclinaison
         * et les jupes, les 80 km couvrent le relief proche au ras du sol.
         * Limite connue et ASSUMÉE : un pic de 21 km culminant très au-delà
         * de l'horizon peut être coupé depuis le sol — comportement présent
         * depuis le lot 2.2, jamais observé à l'écran car la brume l'éteint
         * avant. On ne corrige pas ce qu'on ne voit pas.
         */
        fun farPlaneM(eyeAltitudeM: Double, planetRadiusM: Double): Double =
            slantHorizonM(eyeAltitudeM, planetRadiusM) * 1.8 + 80_000.0

        /**
         * Distance OBLIQUE de l'œil au limbe (la corde, pas l'arc au sol),
         * avec le même plancher de 2 m que l'ancienne écriture du rendu.
         * Exposée séparément parce que la brume de distance s'y règle aussi :
         * la ressortir du plan lointain aurait obligé :app à diviser par 1,8
         * — une constante qui aurait alors vécu à deux endroits.
         */
        fun slantHorizonM(eyeAltitudeM: Double, planetRadiusM: Double): Double {
            val alt = max(2.0, eyeAltitudeM)
            val r = planetRadiusM
            return sqrt(max(0.0, (r + alt) * (r + alt) - r * r))
        }
    }
}
