package com.terra.sim

import com.terra.core.PI_F
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
    private val params: PlanetParams = PlanetParams(),
    private val worldName: String = ""
) {

    companion object {
        /** Construit un générateur à partir du nom du monde, qui sert de graine. */
        fun fromName(name: String, params: PlanetParams = PlanetParams()): WorldGenerator {
            val clean = WorldNamer.sanitize(name)
            return WorldGenerator(Seed.fromText(clean), params, clean)
        }
    }

    /** Étapes annoncées à l'écran de chargement (lot 0.11). */
    enum class Stage(val label: String) {
        GEOMETRY("Construction de la sphère"),
        ELEVATION("Soulèvement des terres"),
        SEA_LEVEL("Remplissage des océans"),
        CLIMATE("Établissement du climat"),
        BIOMES("Répartition des biomes"),
        TECTONICS("Découpage des plaques"),
        DONE("Monde prêt")
    }

    fun generate(onProgress: (Stage, Float) -> Unit = { _, _ -> }): PlanetData {
        val startedAt = System.nanoTime()

        // --- Étape 1 : géométrie ---
        onProgress(Stage.GEOMETRY, 0f)
        val sphere = Icosphere(params.subdivisions)
        val n = sphere.vertexCount
        onProgress(Stage.GEOMETRY, 1f)

        // --- Étape 1 bis : tectonique (lots 1.4, 1.5, 1.8, 1.6) ---
        //
        // Avant l'élévation désormais : depuis le lot 1.6, le relief DÉRIVE
        // des plaques. Flux de graine toujours indépendant du bruit.
        onProgress(Stage.TECTONICS, 0f)
        val plates = PlateSet.generate(masterSeed, sphere, params.oceanFraction)
        val boundaries = BoundarySet.classify(sphere, plates)
        val boundaryDistance = BoundaryDistanceField.generate(sphere, plates, boundaries)
        val structuralM = TectonicRelief.build(sphere, plates, boundaryDistance)
        val structuralSampler = FieldSampler(sphere)
        onProgress(Stage.TECTONICS, 1f)

        // --- Étape 2 : élévation brute ---
        // Chaque champ tire sa propre graine : ajouter la tectonique en lot 1.4
        // ne décalera pas le climat déjà généré.
        onProgress(Stage.ELEVATION, 0f)
        // Le champ d'élévation est une fonction pure de la position : c'est ce
        // qui permettra au rendu à tuiles de l'évaluer à n'importe quelle
        // finesse, sans jamais diverger de la grille simulée.
        val field = ElevationField(masterSeed)

        val raw = FloatArray(n)
        for (i in 0 until n) {
            raw[i] = field.rawAt(sphere.vertices[i])
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

        // Chaque monde reçoit son propre caractère de relief : une planète peut
        // être une pénéplaine érodée ou un massif tourmenté. Sans ce tirage, le
        // calibrage par percentile faisait que toutes les planètes atteignaient
        // exactement le plafond d'altitude, ce qui les rendait interchangeables.
        val reliefRng = masterSeed.derive("relief").rng()
        val reliefScale = reliefRng.nextFloatRange(0.45f, 1.0f)
        val peakiness = reliefRng.nextFloatRange(1.6f, 3.2f)
        val trenchScale = reliefRng.nextFloatRange(0.6f, 1.0f)

        // --- Second calibrage : la mer de la somme (lot 1.6) ---
        //
        // Le premier percentile calibre le BRUIT ; mais l'altitude finale y
        // ajoute le relief structural, qui déplace terre et mer. Un second
        // percentile, sur la somme, redonne exactement la fraction océanique
        // demandée — deux constantes globales plutôt qu'un système d'équations.
        val combined = FloatArray(n)
        for (i in 0 until n) {
            combined[i] = TerrainProfile.convertRaw(
                raw[i], seaLevel, landSpan, seaSpan,
                params.maxAltitudeM, params.maxDepthM,
                reliefScale, peakiness, trenchScale
            ) * TectonicRelief.NOISE_DAMP + structuralM[i]
        }
        val sortedCombined = combined.copyOf()
        sortedCombined.sort()
        val seaOffsetM = sortedCombined[cut]

        // Le profil rassemble les constantes issues du calibrage global. À
        // partir d'ici, l'altitude redevient une fonction pure de la position,
        // évaluable partout et à toute résolution.
        val profile = TerrainProfile(
            seed = masterSeed,
            params = params,
            field = field,
            seaLevel = seaLevel,
            landSpan = landSpan,
            seaSpan = seaSpan,
            reliefScale = reliefScale,
            peakiness = peakiness,
            trenchScale = trenchScale,
            structuralM = structuralM,
            structuralSampler = structuralSampler,
            noiseDamp = TectonicRelief.NOISE_DAMP,
            seaOffsetM = seaOffsetM
        )

        // La grille reprend la même somme, terme à terme : la même conversion
        // (companion), le même tableau structural que le sampler rend au bit
        // près sur ses sommets, le même décalage de mer. Grille et fonction ne
        // peuvent pas diverger — TerrainLodTest le vérifie à chaque poussée.
        val altitudeM = FloatArray(n)
        for (i in 0 until n) {
            altitudeM[i] = TerrainProfile.softLimit(
                combined[i] - seaOffsetM, params.maxAltitudeM, params.maxDepthM
            )
        }

        // --- Étape 3 bis : distance à l'océan ---
        //
        // Un parcours en largeur depuis toutes les cellules marines donne, pour
        // chaque cellule terrestre, son éloignement du littoral. C'est ce qui
        // permettra la continentalité : un intérieur de continent n'a pas le
        // climat de sa côte, et prétendre le contraire produit des planètes
        // uniformes et fades.
        val adjacency = sphere.buildAdjacency()
        val stepsToOcean = IntArray(n) { Int.MAX_VALUE }
        run {
            val queue = IntArray(n)
            var head = 0
            var tail = 0
            for (i in 0 until n) {
                if (altitudeM[i] < 0f) {
                    stepsToOcean[i] = 0
                    queue[tail++] = i
                }
            }
            while (head < tail) {
                val v = queue[head++]
                val d = stepsToOcean[v] + 1
                for (w in adjacency[v]) {
                    if (stepsToOcean[w] > d) {
                        stepsToOcean[w] = d
                        queue[tail++] = w
                    }
                }
            }
        }
        // Une arête d'icosphère de niveau L couvre environ ce nombre de km.
        val kmPerStep = kotlin.math.sqrt(4f * PI_F / n) * (params.radiusM / 1000f)

        // --- Étape 4 : climat ---
        onProgress(Stage.CLIMATE, 0f)
        val tempNoise = Noise(masterSeed.derive("climat/temperature"))
        val precipNoise = Noise(masterSeed.derive("climat/precipitations"))

        val temperatureC = FloatArray(n)
        val precipMm = FloatArray(n)

        for (i in 0 until n) {
            val v = sphere.vertices[i]
            val sinLat = clamp(v.y, -1f, 1f)
            val absSin = abs(sinLat)
            val isLand = altitudeM[i] >= 0f

            // --- Profil thermique latitudinal ---
            //
            // Le profil naïf en sin² chute beaucoup trop vite : il donnait
            // −3 °C à 45° de latitude, contre +12 °C dans la réalité, ce qui
            // gelait la moitié de la planète.
            //
            // Le mélange sin² / sin⁴ ci-dessous suit de près la moyenne annuelle
            // terrestre observée :
            //
            //   latitude   modèle   Terre
            //      0°       27 °C    26 °C
            //     30°       21 °C    20 °C
            //     45°       12 °C    12 °C
            //     60°       −1 °C     0 °C
            //     90°      −20 °C   −20 °C
            val s2 = absSin * absSin
            val s4 = s2 * s2
            var latFactor = 0.35f * s2 + 0.65f * s4

            // Inertie thermique océanique : la mer restitue en hiver la chaleur
            // accumulée en été, ce qui adoucit nettement les hautes latitudes
            // maritimes et repousse la banquise vers les pôles.
            if (!isLand) latFactor *= params.oceanThermalInertia

            var t = params.equatorTempC - params.poleTempDropC * latFactor

            // --- Continentalité ---
            // Loin de tout océan, les hivers sont plus rudes que les étés ne
            // sont chauds : en moyenne annuelle, l'intérieur des continents est
            // plus froid, et d'autant plus qu'on monte en latitude. À
            // l'équateur, l'effet est nul (l'Amazonie intérieure n'est pas
            // froide).
            val distanceKm = if (stepsToOcean[i] == Int.MAX_VALUE) 4000f
                             else stepsToOcean[i] * kmPerStep
            val continentality = clamp01(distanceKm / 1800f)
            t -= continentality * params.continentalityC * latFactor

            // --- Altitude ---
            val altKm = max(0f, altitudeM[i]) / 1000f
            t -= params.lapseRateCPerKm * altKm

            // --- Variation régionale ---
            t += tempNoise.fbm(v.x * 2.4f, v.y * 2.4f, v.z * 2.4f, 3) * 3.5f

            temperatureC[i] = t

            // --- Précipitations ---
            // Bandes latitudinales : ceinture équatoriale humide, tropiques secs
            // vers 27°, façades tempérées arrosées vers 46°. Les vents et
            // l'ombre pluviométrique du relief arriveront au lot 1.14.
            val equatorialBelt = 1f - clamp01(absSin / 0.30f)
            val subtropicalDry = 1f - clamp01(abs(absSin - 0.45f) / 0.22f)
            val temperateBelt = 1f - clamp01(abs(absSin - 0.72f) / 0.25f)

            var wetness = 0.42f + equatorialBelt * 0.50f - subtropicalDry * 0.38f +
                    temperateBelt * 0.18f
            wetness += precipNoise.fbm(
                v.x * 1.9f + 57f, v.y * 1.9f - 31f, v.z * 1.9f + 13f, 4
            ) * 0.55f

            // L'humidité vient de la mer : elle se tarit vers l'intérieur.
            // C'est ce qui creuse les déserts continentaux, du Gobi au Sahara
            // oriental, et ce qui manquait le plus au modèle précédent.
            wetness -= continentality * 0.32f

            // Les hauts reliefs et l'air froid portent moins d'eau.
            wetness -= clamp01(altKm / 5f) * 0.18f
            wetness -= latFactor * 0.16f

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
            highestAltitudeM = altitudeM.maxOrNull() ?: 0f,
            deepestDepthM = altitudeM.minOrNull() ?: 0f,
            coldestC = coldest,
            hottestC = hottest,
            biomeCounts = counts
        )

        onProgress(Stage.DONE, 1f)

        return PlanetData(
            name = worldName,
            seed = masterSeed,
            sphere = sphere,
            params = params,
            altitudeM = altitudeM,
            temperatureC = temperatureC,
            precipMm = precipMm,
            biomeId = biomeId,
            stats = stats,
            terrain = profile,
            plates = plates,
            boundaries = boundaries,
            boundaryDistance = boundaryDistance
        )
    }

}
