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

    fun save(snapshot: WorldSave.Snapshot): Boolean = try {
        temp.writeBytes(WorldSave.encode(snapshot))
        if (file.exists()) file.delete()
        val ok = temp.renameTo(file)
        if (!ok) Log.w(TAG, "Renommage de la sauvegarde impossible")
        ok
    } catch (t: Throwable) {
        Log.w(TAG, "Échec de la sauvegarde", t)
        false
    }

    fun load(): WorldSave.Snapshot? = try {
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
