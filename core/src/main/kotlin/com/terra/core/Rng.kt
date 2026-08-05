package com.terra.core

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * PCG32 — générateur pseudo-aléatoire déterministe.
 *
 * Choisi plutôt que [java.util.Random] pour trois raisons qui comptent sur la
 * durée du projet :
 *
 *  1. **État explicite et sérialisable.** On peut sauvegarder un générateur au
 *     milieu d'une simulation et reprendre exactement où on en était.
 *  2. **Flux indépendants.** Deux générateurs de même graine mais de séquence
 *     différente ne se corrèlent pas. Chaque créature aura le sien.
 *  3. **Identique sur toute plateforme.** Aucune dépendance à l'implémentation
 *     de la JVM ou d'Android.
 *
 * Le déterminisme de cette classe est la fondation de tout Terra : si elle
 * dérive, tous les tests de rejeu de la Phase 5 s'effondrent.
 */
class Rng(seed: Long, sequence: Long = DEFAULT_SEQUENCE) {

    var state: Long = 0L
        private set

    var inc: Long = 0L
        private set

    init {
        // Amorçage standard PCG : on avance une fois, on injecte la graine, on
        // avance à nouveau. Sans cette double avance, deux graines proches
        // produiraient des premiers tirages proches.
        inc = (sequence shl 1) or 1L
        state = 0L
        nextBits()
        state += seed
        nextBits()
    }

    private fun nextBits(): Int {
        val old = state
        state = old * 6364136223846793005L + inc
        val xorshifted = (((old ushr 18) xor old) ushr 27).toInt()
        val rot = (old ushr 59).toInt()
        return (xorshifted ushr rot) or (xorshifted shl ((-rot) and 31))
    }

    fun nextInt(): Int = nextBits()

    /** Entier uniforme dans [0, bound), sans biais de modulo. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound doit être strictement positif" }
        val b = bound.toLong()
        val threshold = ((0x100000000L - b) % b)
        while (true) {
            val r = nextBits().toLong() and 0xFFFFFFFFL
            if (r >= threshold) return (r % b).toInt()
        }
    }

    /** Entier uniforme dans [min, max] inclus. */
    fun nextIntRange(min: Int, max: Int): Int {
        require(max >= min)
        return min + nextInt(max - min + 1)
    }

    fun nextLong(): Long =
        (nextBits().toLong() shl 32) or (nextBits().toLong() and 0xFFFFFFFFL)

    fun nextBoolean(): Boolean = (nextBits() ushr 31) != 0

    /** Flottant uniforme dans [0, 1). 24 bits de mantisse : pas de perte. */
    fun nextFloat(): Float = (nextBits().toLong() and 0xFFFFFFL) / 16777216f

    /** Flottant uniforme dans [-1, 1). */
    fun nextFloatSigned(): Float = nextFloat() * 2f - 1f

    fun nextFloatRange(min: Float, max: Float): Float = min + nextFloat() * (max - min)

    /**
     * Loi normale centrée réduite (Box-Muller).
     * Sans mémorisation du second tirage : consommer un nombre fixe de valeurs
     * du flux à chaque appel garde le déterminisme trivialement vérifiable.
     */
    fun nextGaussian(): Float {
        var u1 = nextFloat()
        if (u1 < 1e-7f) u1 = 1e-7f
        val u2 = nextFloat()
        return sqrt(-2f * ln(u1)) * cos(TAU * u2)
    }

    /** Loi normale bornée : indispensable pour les mutations génétiques. */
    fun nextGaussianClamped(sigma: Float, limit: Float): Float =
        clamp(nextGaussian() * sigma, -limit, limit)

    fun <T> shuffle(list: MutableList<T>) {
        for (i in list.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            val t = list[i]; list[i] = list[j]; list[j] = t
        }
    }

    fun shuffle(array: IntArray) {
        for (i in array.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            val t = array[i]; array[i] = array[j]; array[j] = t
        }
    }

    fun <T> pick(items: List<T>): T = items[nextInt(items.size)]

    /** Copie indépendante, positionnée exactement au même point du flux. */
    fun copy(): Rng = fromState(state, inc)

    companion object {

        /**
         * Numéro de flux par défaut. Deux générateurs de même graine mais de
         * séquences différentes produisent des suites indépendantes.
         */
        const val DEFAULT_SEQUENCE: Long = 0x14057B7EF767814FL

        /**
         * Restaure un générateur depuis un état sauvegardé.
         *
         * Volontairement une fonction plutôt qu'un constructeur : `Rng(Long, Long)`
         * est déjà pris par `(graine, séquence)`, et deux constructeurs à deux
         * `Long` auraient la même signature une fois compilés.
         */
        fun fromState(state: Long, inc: Long): Rng {
            val rng = Rng(0L)
            rng.state = state
            rng.inc = inc
            return rng
        }
    }
}
