package com.terra.sim

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Sauvegarde du monde — lot 0.8.
 *
 * ## Ce qu'on sauvegarde, et ce qu'on ne sauvegarde pas
 *
 * On **ne sauvegarde pas la géométrie**. Une planète de niveau 5 pèse plusieurs
 * mégaoctets sous forme de tableaux ; or elle est entièrement déterminée par son
 * nom, ses paramètres et la version de l'algorithme. On sauvegarde donc la
 * recette, pas le plat : moins de deux cents octets, régénérés en une seconde.
 *
 * Ce choix restera valable en Phase 4 et 6, où il faudra en revanche sauvegarder
 * l'état des créatures et des civilisations, qui eux ne sont pas dérivables du
 * point de départ. Le format ci-dessous prévoit déjà cette extension par son
 * numéro de version.
 *
 * ## Version de génération
 *
 * [GENERATION_VERSION] doit être incrémenté **chaque fois que l'algorithme de
 * génération change** : ajout de la tectonique, changement d'une constante de
 * bruit, correction d'un seuil de biome. Une sauvegarde portant une version
 * antérieure produira une planète différente ; l'application le signale au lieu
 * de prétendre restituer le monde d'origine.
 */
object WorldSave {

    private const val MAGIC = 0x54455252   // "TERR"

    /** Version du format de fichier. À incrémenter si la structure change. */
    const val FORMAT_VERSION = 1

    /**
     * Version de l'algorithme de génération.
     *
     * Historique :
     *   1 — v0.2.0 : Perlin, calibrage du niveau de la mer, biomes de Whittaker
     *   2 — v0.4.0 : profil thermique réaliste, continentalité, variété du relief
     */
    const val GENERATION_VERSION = 2

    data class Snapshot(
        val worldName: String,
        val params: PlanetParams,
        val tick: Long,
        val formatVersion: Int = FORMAT_VERSION,
        val generationVersion: Int = GENERATION_VERSION
    ) {
        /** Vrai si le monde a été créé par une version antérieure du générateur. */
        val isStale: Boolean get() = generationVersion != GENERATION_VERSION
    }

    fun encode(snapshot: Snapshot): ByteArray {
        val bytes = ByteArrayOutputStream(256)
        DataOutputStream(bytes).use { stream ->
            stream.writeInt(MAGIC)
            stream.writeInt(FORMAT_VERSION)
            stream.writeInt(GENERATION_VERSION)
            stream.writeUTF(snapshot.worldName)
            stream.writeLong(snapshot.tick)

            val p = snapshot.params
            stream.writeFloat(p.radiusM)
            stream.writeFloat(p.oceanFraction)
            stream.writeFloat(p.maxAltitudeM)
            stream.writeFloat(p.maxDepthM)
            stream.writeFloat(p.reliefExaggeration)
            stream.writeFloat(p.axialTiltDeg)
            stream.writeFloat(p.equatorTempC)
            stream.writeFloat(p.poleTempDropC)
            stream.writeFloat(p.lapseRateCPerKm)
            stream.writeFloat(p.maxPrecipMm)
            stream.writeInt(p.subdivisions)
        }
        return bytes.toByteArray()
    }

    /** Rend null si les données sont absentes, tronquées ou d'un format inconnu. */
    fun decode(bytes: ByteArray): Snapshot? {
        if (bytes.size < 16) return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MAGIC) return null
                val formatVersion = input.readInt()
                if (formatVersion > FORMAT_VERSION) return null   // écrit par une version future
                val generationVersion = input.readInt()
                val name = input.readUTF()
                val tick = input.readLong()

                val params = PlanetParams(
                    radiusM = input.readFloat(),
                    oceanFraction = input.readFloat(),
                    maxAltitudeM = input.readFloat(),
                    maxDepthM = input.readFloat(),
                    reliefExaggeration = input.readFloat(),
                    axialTiltDeg = input.readFloat(),
                    equatorTempC = input.readFloat(),
                    poleTempDropC = input.readFloat(),
                    lapseRateCPerKm = input.readFloat(),
                    maxPrecipMm = input.readFloat(),
                    subdivisions = input.readInt()
                )
                Snapshot(name, params, tick, formatVersion, generationVersion)
            }
        } catch (t: Throwable) {
            // Fichier corrompu : on préfère un monde neuf à un plantage au
            // démarrage. La sauvegarde sera écrasée au prochain enregistrement.
            null
        }
    }
}
