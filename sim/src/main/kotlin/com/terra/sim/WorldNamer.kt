package com.terra.sim

import com.terra.core.Rng
import com.terra.core.Seed

/**
 * Nommage des mondes.
 *
 * ## Pourquoi un nom plutôt qu'un nombre
 *
 * Jusqu'ici la graine était `System.currentTimeMillis()` : un monde trouvé beau
 * était perdu à la fermeture, et impossible à décrire à quelqu'un d'autre.
 *
 * Désormais **le nom est la graine**. Un monde s'appelle « Kaleth », et taper
 * « Kaleth » reconstruit exactement la même planète, sur n'importe quel
 * appareil. Un nom se retient, se note, se partage — un entier 64 bits non.
 *
 * La correspondance nom → graine passe par [Seed.fromText], insensible à la
 * casse et aux espaces superflus.
 */
object WorldNamer {

    private val onsets = listOf(
        "b", "br", "c", "cr", "d", "dr", "f", "fr", "g", "gr", "h", "j", "k", "kr",
        "l", "m", "n", "p", "pr", "q", "r", "s", "sk", "sl", "st", "t", "tr", "th",
        "v", "vr", "x", "z", "ph", "ch", "sh", ""
    )

    private val nuclei = listOf(
        "a", "e", "i", "o", "u", "y", "ae", "ai", "au", "ea", "ei", "ia", "io",
        "oa", "oe", "ou", "ua"
    )

    private val codas = listOf(
        "", "", "", "n", "r", "l", "s", "th", "sk", "rn", "ld", "st", "x", "m", "k"
    )

    /** Nom prononçable de deux ou trois syllabes. */
    fun randomName(rng: Rng): String {
        val syllables = if (rng.nextFloat() < 0.62f) 2 else 3
        val sb = StringBuilder()
        repeat(syllables) { i ->
            sb.append(rng.pick(onsets))
            sb.append(rng.pick(nuclei))
            // Une consonne finale en milieu de mot alourdit la prononciation.
            if (i == syllables - 1 || rng.nextFloat() < 0.25f) sb.append(rng.pick(codas))
        }
        val raw = sb.toString()
        if (raw.length < 3) return randomName(rng)          // trop court, on retire
        return raw.substring(0, 1).uppercase() + raw.substring(1)
    }

    /** Nom aléatoire tiré d'une source d'entropie quelconque. */
    fun randomName(entropy: Long): String = randomName(Rng(entropy))

    /** Nettoie une saisie utilisateur en un nom de monde canonique. */
    fun sanitize(input: String): String {
        val cleaned = input.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return ""
        return cleaned.take(24)
    }
}
