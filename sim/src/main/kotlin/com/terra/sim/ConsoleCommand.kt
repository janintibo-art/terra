package com.terra.sim

/**
 * Commandes de la console de mise au point — version minimale du lot 0.7,
 * avancée ici parce que se téléporter à des coordonnées données accélère
 * énormément les essais de descente du lot B.
 *
 * L'analyse vit dans `:sim` et l'interface dans `:app` : la grammaire est ainsi
 * testée en intégration continue, et seul l'affichage échappe aux tests.
 */
sealed class ConsoleCommand {

    /** `tp <lat> <lon> [portée]` — se téléporter. Portée en mètres, suffixe k = km. */
    data class Teleport(val latDeg: Double, val lonDeg: Double, val rangeM: Double?) : ConsoleCommand()

    /** `monde <nom>` — régénérer le monde depuis un nom-graine. */
    data class LoadWorld(val name: String) : ConsoleCommand()

    /** `mode sol` / `mode globe` — basculer entre descente et globe. */
    data class SetMode(val descent: Boolean) : ConsoleCommand()

    /** `soleil <heure>` — avancer l'horloge jusqu'à cette heure locale. */
    data class SetLocalHour(val hour: Double) : ConsoleCommand()

    /**
     * `teinte [on|off]` — colorer chaque tuile selon son niveau de
     * subdivision. Sans argument : bascule. L'outil de diagnostic des
     * artefacts de tuiles : un défaut devient lisible — sa tuile, son
     * niveau, sa frontière — au lieu d'être une tache anonyme.
     */
    data class SetLevelTint(val enabled: Boolean?) : ConsoleCommand()

    /** `aide` — texte d'aide. */
    object Help : ConsoleCommand()

    /** Entrée incompréhensible, avec l'explication à afficher. */
    data class Invalid(val message: String) : ConsoleCommand()

    companion object {

        const val HELP_TEXT: String =
            "tp <lat> <lon> [portée]   ex : tp 45.5 -73.6 500\n" +
            "   portée en mètres, suffixe k pour km : tp 12 34 80k\n" +
            "monde <nom>               régénère depuis ce nom-graine\n" +
            "soleil <heure>            avance jusqu'à cette heure locale, ex : soleil 12\n" +
            "mode sol | mode globe     bascule la vue\n" +
            "teinte [on|off]           colore les tuiles par niveau (diagnostic)\n" +
            "aide                      ce texte"

        /**
         * Analyse une ligne de commande.
         *
         * Les nombres acceptent la virgule décimale : sur un clavier français,
         * taper un point dans un champ numérique demande un détour, et rejeter
         * « 45,5 » serait une source d'agacement gratuite.
         */
        fun parse(line: String): ConsoleCommand {
            val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return Invalid("Commande vide. Tapez « aide ».")

            return when (parts[0].lowercase()) {
                "tp" -> parseTeleport(parts)
                "monde", "seed" -> {
                    if (parts.size < 2) Invalid("Usage : monde <nom>")
                    else LoadWorld(parts.drop(1).joinToString(" "))
                }
                "soleil" -> {
                    val h = parts.getOrNull(1)?.let { parseNumber(it) }
                    when {
                        h == null -> Invalid("Usage : soleil <heure entre 0 et 24>")
                        h < 0.0 || h >= 24.0 -> Invalid("Heure hors de [0, 24) : $h")
                        else -> SetLocalHour(h)
                    }
                }
                "teinte" -> when (parts.getOrNull(1)?.lowercase()) {
                    null -> SetLevelTint(null)
                    "on", "oui" -> SetLevelTint(true)
                    "off", "non" -> SetLevelTint(false)
                    else -> Invalid("Usage : teinte [on|off]")
                }
                "mode" -> when (parts.getOrNull(1)?.lowercase()) {
                    "sol", "descente" -> SetMode(descent = true)
                    "globe", "orbite" -> SetMode(descent = false)
                    else -> Invalid("Usage : mode sol | mode globe")
                }
                "aide", "help", "?" -> Help
                else -> Invalid("Commande inconnue : ${parts[0]}. Tapez « aide ».")
            }
        }

        private fun parseTeleport(parts: List<String>): ConsoleCommand {
            if (parts.size < 3) return Invalid("Usage : tp <lat> <lon> [portée]")

            val lat = parseNumber(parts[1])
                ?: return Invalid("Latitude illisible : ${parts[1]}")
            val lon = parseNumber(parts[2])
                ?: return Invalid("Longitude illisible : ${parts[2]}")
            if (lat < -90.0 || lat > 90.0) return Invalid("Latitude hors de [-90, 90] : $lat")
            if (lon < -180.0 || lon > 180.0) return Invalid("Longitude hors de [-180, 180] : $lon")

            var range: Double? = null
            if (parts.size >= 4) {
                range = parseRange(parts[3])
                    ?: return Invalid("Portée illisible : ${parts[3]}")
                if (range <= 0.0) return Invalid("La portée doit être positive.")
            }
            return Teleport(lat, lon, range)
        }

        private fun parseNumber(raw: String): Double? =
            raw.replace(',', '.').toDoubleOrNull()

        /** Portée en mètres ; « 80k » ou « 80km » se lisent 80 000 m. */
        private fun parseRange(raw: String): Double? {
            val cleaned = raw.lowercase().replace(',', '.')
            return when {
                cleaned.endsWith("km") -> cleaned.dropLast(2).toDoubleOrNull()?.times(1000.0)
                cleaned.endsWith("k") -> cleaned.dropLast(1).toDoubleOrNull()?.times(1000.0)
                cleaned.endsWith("m") -> cleaned.dropLast(1).toDoubleOrNull()
                else -> cleaned.toDoubleOrNull()
            }
        }
    }
}
