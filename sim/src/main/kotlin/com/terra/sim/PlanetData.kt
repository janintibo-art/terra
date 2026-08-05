package com.terra.sim

import com.terra.core.Seed
import com.terra.core.Vec3

/**
 * Le monde généré, sous forme de données pures.
 *
 * Cette classe ne connaît ni OpenGL, ni Android, ni le moindre pixel. Le moteur
 * de rendu la lit pour construire ses tampons ; les systèmes de simulation la
 * liront pour placer des plantes, des créatures et des tribus. Cette séparation
 * est ce qui rend la génération testable automatiquement en intégration
 * continue.
 *
 * Toutes les grandeurs sont dans des unités physiques réelles.
 */
class PlanetData(
    val seed: Seed,
    val sphere: Icosphere,
    val params: PlanetParams,

    /** Altitude par sommet, en mètres. Négative sous le niveau de la mer. */
    val altitudeM: FloatArray,

    /** Température moyenne annuelle par sommet, en degrés Celsius. */
    val temperatureC: FloatArray,

    /** Précipitations annuelles par sommet, en millimètres. */
    val precipMm: FloatArray,

    /** Biome par sommet, stocké par ordinal pour la compacité. */
    val biomeId: ByteArray,

    /** Statistiques de génération, affichées dans le HUD de debug. */
    val stats: GenerationStats
) {

    val vertexCount: Int get() = sphere.vertexCount
    val faceCount: Int get() = sphere.faceCount

    fun position(index: Int): Vec3 = sphere.vertices[index]

    fun biome(index: Int): Biome = BIOMES[biomeId[index].toInt()]

    fun isLand(index: Int): Boolean = altitudeM[index] >= 0f

    /**
     * Rayon de rendu d'un sommet, en unités de planète (1.0 = niveau de la mer).
     *
     * Le relief est volontairement exagéré : à l'échelle réelle, l'Everest
     * représente 0,14 % du rayon terrestre et serait rigoureusement invisible.
     * Tous les globes en relief du monde trichent de la même façon.
     */
    fun renderRadius(index: Int): Float {
        val a = altitudeM[index]
        if (a <= 0f) return 1f
        return 1f + (a / params.maxAltitudeM) * params.reliefExaggeration
    }

    companion object {
        private val BIOMES = Biome.values()
    }
}

/**
 * Paramètres de génération. Exposés en un seul endroit pour que l'éditeur en
 * jeu du lot 1.18 puisse tous les manipuler sans toucher au code.
 */
data class PlanetParams(
    /** Rayon planétaire en mètres. Terre : 6 371 000. */
    val radiusM: Float = 6_371_000f,

    /** Proportion de la surface couverte par les océans. Terre : 0,71. */
    val oceanFraction: Float = 0.66f,

    /** Altitude du point le plus haut, en mètres. */
    val maxAltitudeM: Float = 7_000f,

    /** Profondeur du point le plus bas, en mètres. */
    val maxDepthM: Float = 6_500f,

    /** Facteur d'exagération du relief au rendu. */
    val reliefExaggeration: Float = 0.055f,

    /** Inclinaison de l'axe de rotation, en degrés. Terre : 23,44. */
    val axialTiltDeg: Float = 23.4f,

    /** Température moyenne à l'équateur au niveau de la mer, en °C. */
    val equatorTempC: Float = 28f,

    /** Écart de température entre l'équateur et les pôles, en °C. */
    val poleTempDropC: Float = 62f,

    /** Refroidissement avec l'altitude, en °C par kilomètre. Terre : 6,5. */
    val lapseRateCPerKm: Float = 6.5f,

    /** Précipitations maximales, en millimètres par an. */
    val maxPrecipMm: Float = 3_600f,

    /** Niveau de subdivision de l'icosphère. */
    val subdivisions: Int = 5
)

/** Mesures relevées pendant la génération, pour le HUD et les tests. */
data class GenerationStats(
    val generationMs: Long,
    val oceanFractionActual: Float,
    val highestAltitudeM: Float,
    val deepestDepthM: Float,
    val coldestC: Float,
    val hottestC: Float,
    val biomeCounts: Map<Biome, Int>
) {
    val distinctBiomes: Int get() = biomeCounts.count { it.value > 0 }
}
