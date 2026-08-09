package com.terra.planet

import android.content.Context
import android.util.Log
import com.terra.sim.WorldSave
import java.io.File

/**
 * Écriture et lecture de la sauvegarde sur le stockage privé de l'application.
 *
 * Aucune permission n'est requise : `filesDir` appartient à l'application et
 * disparaît avec elle. Rien ne quitte l'appareil, conformément à la promesse du
 * projet.
 *
 * L'écriture passe par un fichier temporaire renommé ensuite. Sans cette
 * précaution, une interruption au mauvais moment (batterie vide, arrêt forcé)
 * laisserait une sauvegarde à moitié écrite, donc un monde perdu.
 */
class WorldStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val temp = File(context.filesDir, "$FILE_NAME.tmp")

    /**
     * Écrit la sauvegarde de façon atomique et durable.
     *
     * Trois défauts de la version précédente, dans l'ordre de gravité :
     *
     *  1. Elle SUPPRIMAIT la sauvegarde avant de renommer le temporaire. Entre
     *     les deux, aucun fichier n'existait : une coupure à cet instant
     *     perdait le monde. On renomme désormais par-dessus — sur le même
     *     système de fichiers, `rename` est atomique, la sauvegarde passe
     *     directement de l'ancienne version à la nouvelle.
     *  2. Elle ne forçait pas l'écriture sur le support. `writeBytes` rend la
     *     main quand les octets sont dans le cache du noyau ; une coupure de
     *     courant pouvait laisser un fichier de la bonne taille au contenu
     *     partiel. Un `fd.sync()` explicite ferme cette fenêtre.
     *  3. Elle ne vérifiait pas ce qu'elle venait d'écrire. Le décodage de
     *     contrôle coûte quelques microsecondes sur moins de 200 octets et
     *     garantit qu'on ne remplace jamais une sauvegarde valide par une
     *     corrompue.
     */
    fun save(snapshot: WorldSave.Snapshot): Boolean = try {
        val bytes = WorldSave.encode(snapshot)
        if (WorldSave.decode(bytes) == null) {
            Log.w(TAG, "Sauvegarde refusée : l'encodage ne se relit pas")
            false
        } else {
            java.io.FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                // Durabilité : sans sync, les octets ne sont que dans le
                // cache du noyau et une coupure laisse un fichier tronqué.
                out.fd.sync()
            }
            // Pas de delete préalable : rename écrase atomiquement.
            val ok = temp.renameTo(file)
            if (!ok) {
                Log.w(TAG, "Renommage de la sauvegarde impossible")
                temp.delete()
            }
            ok
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Échec de la sauvegarde", t)
        try { temp.delete() } catch (ignored: Throwable) { }
        false
    }

    /**
     * Relit la sauvegarde. Un temporaire résiduel signale une écriture
     * interrompue : on l'efface plutôt que de le laisser s'accumuler, la
     * sauvegarde précédente restant intacte par construction.
     */
    fun load(): WorldSave.Snapshot? = try {
        if (temp.exists()) temp.delete()
        if (!file.exists()) null else WorldSave.decode(file.readBytes())
    } catch (t: Throwable) {
        Log.w(TAG, "Échec du chargement", t)
        null
    }

    fun clear() {
        file.delete()
        temp.delete()
    }

    companion object {
        private const val TAG = "TerraStore"
        private const val FILE_NAME = "world.terra"
    }
}
