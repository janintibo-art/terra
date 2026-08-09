package com.terra.core

/**
 * Journalisation minimale pour le code de simulation.
 *
 * ## Pourquoi cette abstraction existe
 *
 * `TileWorkerPool` vivait dans `:app` pour une seule raison : un appel à
 * `android.util.Log`. Cette unique ligne rendait intestable en CI toute la
 * logique de concurrence du pool — déduplication, annulation, retrait
 * conditionnel — alors que deux courses y ont déjà été trouvées à l'œil nu.
 * On inverse la dépendance : la simulation parle à cette interface, et
 * `:app` fournit l'adaptateur vers logcat.
 *
 * ## Pourquoi une seule méthode
 *
 * Le code de simulation n'a pas vocation à bavarder : il ne journalise que
 * les erreurs avalées (une exception dans un fil de travail ne doit ni tuer
 * le fil ni disparaître). Ajouter debug/info/warn inviterait à s'en servir —
 * et un `Log.d` dans une boucle de maillage coûte plus cher que le maillage.
 */
fun interface TerraLogger {
    fun error(tag: String, message: String, cause: Throwable?)

    companion object {
        /**
         * Repli par défaut : la sortie d'erreur standard.
         *
         * PAS un puits silencieux — une exception avalée sans trace est le
         * pire des deux mondes. Sur JVM de test, stderr apparaît dans le
         * rapport ; sur Android, `System.err` est de toute façon redirigé
         * vers logcat (étiquette `System.err`), donc même un oubli
         * d'adaptateur dans `:app` resterait visible.
         */
        val STDERR: TerraLogger = TerraLogger { tag, message, cause ->
            System.err.println("[$tag] $message")
            cause?.printStackTrace()
        }
    }
}
