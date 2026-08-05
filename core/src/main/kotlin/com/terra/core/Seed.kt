package com.terra.core

/**
 * Graine hiérarchique — lot 0.3.
 *
 * ## Le problème que cette classe résout
 *
 * L'approche naïve consiste à créer un unique générateur aléatoire et à le faire
 * consommer par tous les systèmes. Elle fonctionne jusqu'au jour où l'on ajoute
 * un système : celui-ci consomme des valeurs du flux commun, décale tout ce qui
 * suit, et **tous les mondes déjà générés changent**. Une sauvegarde faite en
 * Phase 3 deviendrait un monde différent en Phase 6.
 *
 * ## La solution
 *
 * Chaque domaine dérive sa propre graine depuis la graine maîtresse, par un
 * chemin nommé et stable :
 *
 * ```
 * val master  = Seed.master(1337L)
 * val terrain = master.derive("terrain")
 * val climat  = master.derive("climat")
 * val faune   = master.derive("faune")
 * val bete17  = faune.derive("individu", 17)
 * ```
 *
 * Ajouter `master.derive("civilisations")` en Phase 6 ne modifie strictement
 * rien à ce que produisent `terrain` ou `faune`. Les phases deviennent
 * indépendantes les unes des autres.
 *
 * ## Contrat de stabilité
 *
 * Les fonctions de mélange ci-dessous ne doivent **jamais** être modifiées une
 * fois qu'un monde a été publié. Toute retouche invaliderait les sauvegardes.
 * Si un changement s'avérait indispensable, il faudrait passer par un numéro de
 * version de génération et conserver l'ancienne fonction pour les mondes
 * existants.
 */
class Seed private constructor(
    val value: Long,
    val path: String
) {

    /** Dérive une sous-graine nommée. Le nom fait partie du contrat. */
    fun derive(label: String): Seed = Seed(
        mix(value xor fnv1a(label)),
        if (path.isEmpty()) label else "$path/$label"
    )

    /** Dérive une sous-graine indexée : utile pour la n-ième entité d'un domaine. */
    fun derive(label: String, index: Int): Seed {
        val base = mix(value xor fnv1a(label))
        return Seed(
            mix(base + index.toLong() * GOLDEN),
            (if (path.isEmpty()) label else "$path/$label") + "#" + index
        )
    }

    /** Générateur positionné au début du flux de cette graine. */
    fun rng(): Rng = Rng(value)

    /**
     * Générateur sur un flux indépendant de la même graine. Deux appels avec des
     * numéros de séquence différents ne se corrèlent pas.
     */
    fun rng(sequence: Long): Rng = Rng(value, sequence)

    /** Représentation courte et lisible, affichée dans le HUD de debug. */
    fun shortCode(): String {
        val v = value
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val sb = StringBuilder()
        var x = v
        repeat(8) {
            sb.append(chars[((x ushr 59) and 31L).toInt()])
            x = x shl 5
        }
        return sb.toString()
    }

    override fun toString(): String =
        "Seed(${shortCode()}${if (path.isEmpty()) "" else " /$path"})"

    override fun equals(other: Any?): Boolean =
        other is Seed && other.value == value && other.path == path

    override fun hashCode(): Int = (value xor (value ushr 32)).toInt() * 31 + path.hashCode()

    companion object {

        private const val GOLDEN: Long = -7046029254386353131L // 0x9E3779B97F4A7C15

        fun master(value: Long): Seed = Seed(mix(value), "")

        /** Graine à partir d'un texte : permet de partager un monde par son nom. */
        fun fromText(text: String): Seed = master(fnv1a(text.trim().lowercase()))

        /** Hachage FNV-1a 64 bits — stable, indépendant de la plateforme. */
        fun fnv1a(s: String): Long {
            var h = -3750763034362895579L // 0xCBF29CE484222325
            for (c in s) {
                h = h xor c.code.toLong()
                h *= 1099511628211L
            }
            return h
        }

        /** Finaliseur SplitMix64 : excellente dispersion des bits. */
        fun mix(input: Long): Long {
            var z = input + GOLDEN
            z = (z xor (z ushr 30)) * -4658895280553007687L // 0xBF58476D1CE4E5B9
            z = (z xor (z ushr 27)) * -7723592293110705685L // 0x94D049BB133111EB
            return z xor (z ushr 31)
        }
    }
}
