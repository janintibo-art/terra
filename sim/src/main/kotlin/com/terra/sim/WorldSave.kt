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

    /**
     * Version du format de fichier. À incrémenter si la structure change.
     *
     *   1 — écriture initiale (lot 0.8)
     *   2 — v0.16.0 : ajout d'oceanThermalInertia et continentalityC en fin
     *       d'enregistrement. Ces deux paramètres existaient dans
     *       [PlanetParams] mais n'étaient PAS persistés — bug latent
     *       invisible tant qu'on ne les éditait pas, fatal dès l'éditeur du
     *       lot 1.18 : une valeur réglée aurait silencieusement repris sa
     *       valeur d'usine au redémarrage, en violation de « la sauvegarde
     *       de la recette ». Les nouveaux champs sont ajoutés en QUEUE pour
     *       que le préfixe v1 reste lisible tel quel.
     *   3 — v0.17.0 : ajout de tectonicActivity en queue (lot 1.18 b).
     */
    const val FORMAT_VERSION = 3

    /**
     * Version de l'algorithme de génération.
     *
     * Historique :
     *   1 — v0.2.0 : Perlin, calibrage du niveau de la mer, biomes de Whittaker
     *   2 — v0.4.0 : profil thermique réaliste, continentalité, variété du relief
     *  12 — v0.15.2 : courants — inversion subpolaire du motif est/ouest,
     *       atténuation de l'effet avec l'altitude
     *
     * Resté à 12 au lot 1.18 b (v0.17.0) : l'activité tectonique est un
     * multiplicateur post-tirages, exactement neutre à sa valeur d'usine —
     * aucune recette existante ne change. TectonicActivityTest le verrouille
     * au bit près ; si ce test casse, incrémenter ICI, pas l'assouplir.
     */
    const val GENERATION_VERSION = 12

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
            // Champs du format 2, en queue pour préserver le préfixe v1.
            stream.writeFloat(p.oceanThermalInertia)
            stream.writeFloat(p.continentalityC)
            // Champ du format 3.
            stream.writeFloat(p.tectonicActivity)
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

                val radiusM = input.readFloat()
                val oceanFraction = input.readFloat()
                val maxAltitudeM = input.readFloat()
                val maxDepthM = input.readFloat()
                val reliefExaggeration = input.readFloat()
                val axialTiltDeg = input.readFloat()
                val equatorTempC = input.readFloat()
                val poleTempDropC = input.readFloat()
                val lapseRateCPerKm = input.readFloat()
                val maxPrecipMm = input.readFloat()
                val subdivisions = input.readInt()
                // Migration v1 → v2 : les sauvegardes anciennes n'ont pas les
                // deux derniers champs ; elles reçoivent les valeurs d'usine,
                // qui sont exactement celles avec lesquelles elles avaient
                // été générées puisque ces paramètres n'étaient pas éditables.
                val defaults = PlanetParams()
                val inertia = if (formatVersion >= 2) input.readFloat()
                              else defaults.oceanThermalInertia
                val contC = if (formatVersion >= 2) input.readFloat()
                            else defaults.continentalityC
                val activity = if (formatVersion >= 3) input.readFloat()
                               else defaults.tectonicActivity

                val params = PlanetParams(
                    radiusM = radiusM,
                    oceanFraction = oceanFraction,
                    maxAltitudeM = maxAltitudeM,
                    maxDepthM = maxDepthM,
                    reliefExaggeration = reliefExaggeration,
                    axialTiltDeg = axialTiltDeg,
                    equatorTempC = equatorTempC,
                    poleTempDropC = poleTempDropC,
                    oceanThermalInertia = inertia,
                    continentalityC = contC,
                    lapseRateCPerKm = lapseRateCPerKm,
                    maxPrecipMm = maxPrecipMm,
                    subdivisions = subdivisions,
                    tectonicActivity = activity
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
