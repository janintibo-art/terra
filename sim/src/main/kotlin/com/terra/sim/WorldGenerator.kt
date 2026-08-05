package com.terra.sim

import com.terra.core.Seed
import com.terra.core.Vec3
import com.terra.core.clamp
import com.terra.core.clamp01
import com.terra.core.lerp
import kotlin.math.abs
import kotlin.math.max

/**
 * Générateur de monde — orchestration des étapes de la Phase 1.
 *
 * L'état actuel couvre les lots 1.1 à 1.3. Les lots 1.4 à 1.17 (tectonique,
 * érosion, hydrographie, circulation atmosphérique) viendront s'insérer comme
 * des étapes supplémentaires dans [generate], sans que le reste du projet ait à
 * changer : c'est tout l'intérêt d'avoir isolé la génération derrière une
 * structure de données stable.
 */
class WorldGenerator(
    private val masterSeed: Seed,
    private val params: PlanetParams = PlanetParams()
) {

    /** Étapes annoncées à l'écran de chargement (lot 0.11). */
    enum class Stage(val label: String) {
        GEOMETRY("Construction de la sphère"),
        ELEVATION("Soulèvement des terres"),
        SEA_LEVEL("Remplissage des océans"),
        CLIMATE("Établissement du climat"),
        BIOMES("Répartition des biomes"),
        DONE("Monde prêt")
    }

    fun generate(onProgress: (Stage, Float) -> Unit = { _, _ -> }): PlanetData {
        val startedAt = System.nanoTime()

        // --- Étape 1 : géométrie ---
        onProgress(Stage.GEOMETRY, 0f)
        val sphere = Icosphere(params.subdivisions)
        val n = sphere.vertexCount
        onProgress(Stage.GEOMETRY, 1f)

        // --- Étape 2 : élévation brute ---
        // Chaque champ tire sa propre graine : ajouter la tectonique en lot 1.4
        // ne décalera pas le climat déjà généré.
        onProgress(Stage.ELEVATION, 0f)
        val terrainNoise = Noise(masterSeed.derive("terrain"))
        val warpNoise = Noise(masterSeed.derive("terrain/warp"))

        val raw = FloatArray(n)
        for (i in 0 until n) {
            raw[i] = rawElevation(terrainNoise, warpNoise, sphere.vertices[i])
            if (i and 1023 == 0) onProgress(Stage.ELEVATION, i.toFloat() / n)
        }
        onProgress(Stage.ELEVATION, 1f)

        // --- Étape 3 : calibrage du niveau de la mer ---
        //
        // Point important. Fixer un seuil absolu (« terre si bruit > 0 ») donne
        // des mondes radicalement différents selon la graine : ici une planète
        // noyée, là un continent unique couvrant tout l'hémisphère. C'est
        // exactement le défaut observé sur la version 0.1.
        //
        // En prenant le percentile voulu de la distribution réelle des
        // élévations, on garantit la proportion d'océan demandée quelle que soit
        // la graine, tout en laissant la forme des continents entièrement libre.
        onProgress(Stage.SEA_LEVEL, 0f)
        val sorted = raw.copyOf()
        sorted.sort()
        val cut = (n * params.oceanFraction).toInt().coerceIn(0, n - 1)
        val seaLevel = sorted[cut]
        val highest = sorted[n - 1]
        val lowest = sorted[0]
        val landSpan = max(1e-5f, highest - seaLevel)
        val seaSpan = max(1e-5f, seaLevel - lowest)
        onProgress(Stage.SEA_LEVEL, 1f)

        val altitudeM = FloatArray(n)
        for (i in 0 until n) {
            val e = raw[i]
            altitudeM[i] = if (e >= seaLevel) {
                val t = (e - seaLevel) / landSpan
                // Puissance > 1 : beaucoup de plaines basses, peu de sommets.
                t * t * params.maxAltitudeM
            } else {
                val t = (seaLevel - e) / seaSpan
                -t * params.maxDepthM
            }
        }

        // --- Étape 4 : climat (approximation, remplacée en lots 1.12 à 1.15) ---
        onProgress(Stage.CLIMATE, 0f)
        val tempNoise = Noise(masterSeed.derive("climat/temperature"))
        val precipNoise = Noise(masterSeed.derive("climat/precipitations"))

        val temperatureC = FloatArray(n)
        val precipMm = FloatArray(n)

        for (i in 0 until n) {
            val v = sphere.vertices[i]
            val sinLat = clamp(v.y, -1f, 1f)
            val latFactor = sinLat * sinLat            // 0 à l'équateur, 1 aux pôles

            val altKm = max(0f, altitudeM[i]) / 1000f
            val variation = tempNoise.fbm(v.x * 2.4f, v.y * 2.4f, v.z * 2.4f, 3) * 5.5f

            temperatureC[i] = params.equatorTempC -
                    params.poleTempDropC * latFactor -
                    params.lapseRateCPerKm * altKm +
                    variation

            // Approximation de l'humidité : bandes latitudinales (équatoriale
            // humide, tropiques secs vers 25 à 30°, tempérées humides) modulées
            // par du bruit. Les vents et l'ombre pluviométrique arriveront au
            // lot 1.14 et remplaceront ce calcul.
            val absSin = abs(sinLat)
            val equatorialBelt = 1f - clamp01(abs(absSin - 0.00f) / 0.30f)
            val subtropicalDry = 1f - clamp01(abs(absSin - 0.45f) / 0.22f)
            val temperateBelt = 1f - clamp01(abs(absSin - 0.72f) / 0.25f)

            var wetness = 0.42f + equatorialBelt * 0.50f - subtropicalDry * 0.38f +
                    temperateBelt * 0.18f
            wetness += precipNoise.fbm(
                v.x * 1.9f + 57f, v.y * 1.9f - 31f, v.z * 1.9f + 13f, 4
            ) * 0.55f

            // Les terres intérieures d'altitude reçoivent moins de pluie.
            wetness -= clamp01(altKm / 5f) * 0.20f

            // L'eau froide s'évapore mal : les hautes latitudes sont sèches.
            wetness -= latFactor * 0.18f

            precipMm[i] = clamp01(wetness) * params.maxPrecipMm

            if (i and 1023 == 0) onProgress(Stage.CLIMATE, i.toFloat() / n)
        }
        onProgress(Stage.CLIMATE, 1f)

        // --- Étape 5 : biomes ---
        onProgress(Stage.BIOMES, 0f)
        val biomeId = ByteArray(n)
        val counts = HashMap<Biome, Int>()
        var coldest = Float.MAX_VALUE
        var hottest = -Float.MAX_VALUE
        var oceanCells = 0

        for (i in 0 until n) {
            val b = Biome.classify(altitudeM[i], temperatureC[i], precipMm[i])
            biomeId[i] = b.ordinal.toByte()
            counts[b] = (counts[b] ?: 0) + 1
            if (temperatureC[i] < coldest) coldest = temperatureC[i]
            if (temperatureC[i] > hottest) hottest = temperatureC[i]
            if (altitudeM[i] < 0f) oceanCells++
        }
        onProgress(Stage.BIOMES, 1f)

        val stats = GenerationStats(
            generationMs = (System.nanoTime() - startedAt) / 1_000_000L,
            oceanFractionActual = oceanCells.toFloat() / n,
            highestAltitudeM = altitudeM.max(),
            deepestDepthM = altitudeM.min(),
            coldestC = coldest,
            hottestC = hottest,
            biomeCounts = counts
        )

        onProgress(Stage.DONE, 1f)

        return PlanetData(
            seed = masterSeed,
            sphere = sphere,
            params = params,
            altitudeM = altitudeM,
            temperatureC = temperatureC,
            precipMm = precipMm,
            biomeId = biomeId,
            stats = stats
        )
    }

    /**
     * Champ d'élévation brut, sans unité — seul son ordre relatif compte, le
     * calibrage du niveau de la mer s'occupe de la mise à l'échelle.
     *
     * Trois ingrédients :
     *  - une **déformation du domaine** qui tord l'espace avant l'échantillonnage,
     *    ce qui produit des côtes sinueuses au lieu de contours ronds ;
     *  - un **bruit basse fréquence** qui dessine les masses continentales ;
     *  - un **bruit en crêtes**, masqué par les terres, qui pose les montagnes
     *    uniquement là où il y a de la terre à soulever.
     */
    private fun rawElevation(terrain: Noise, warp: Noise, v: Vec3): Float {
        val d = 0.32f
        val wx = v.x + warp.fbm(v.x * 2.6f, v.y * 2.6f, v.z * 2.6f, 3) * d
        val wy = v.y + warp.fbm(v.x * 2.6f + 31f, v.y * 2.6f + 17f, v.z * 2.6f + 7f, 3) * d
        val wz = v.z + warp.fbm(v.x * 2.6f - 19f, v.y * 2.6f - 43f, v.z * 2.6f + 23f, 3) * d

        val continent = terrain.fbm(wx * 1.45f, wy * 1.45f, wz * 1.45f, 6)
        val mountains = terrain.ridged(wx * 3.2f + 100f, wy * 3.2f, wz * 3.2f - 100f, 5)

        // Le masque évite les montagnes surgissant du milieu de l'océan.
        val landMask = clamp01((continent + 0.03f) * 3.5f)
        val coastalSoftening = lerp(0.35f, 1f, landMask)

        return continent + mountains * landMask * coastalSoftening * 1.30f
    }
}
