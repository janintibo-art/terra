package com.terra.sim

/**
 * Biomes — classification inspirée du diagramme de Whittaker (température
 * moyenne annuelle × précipitations), complétée par les cas marins et
 * d'altitude.
 *
 * Cette version est déjà exprimée en **unités physiques réelles** (degrés
 * Celsius, mètres, millimètres de pluie) plutôt qu'en valeurs abstraites. Ce
 * choix coûte un peu de rigueur maintenant et fait gagner énormément plus tard :
 * quand la Phase 1 remplacera l'estimation climatique par une vraie circulation
 * atmosphérique, les seuils ci-dessous resteront valables tels quels.
 */
enum class Biome(
    val label: String,
    val r: Float, val g: Float, val b: Float
) {
    DEEP_OCEAN("Océan profond", 0.035f, 0.085f, 0.240f),
    OCEAN("Océan", 0.060f, 0.180f, 0.400f),
    SHALLOW_SEA("Mer côtière", 0.110f, 0.360f, 0.560f),
    SEA_ICE("Banquise", 0.800f, 0.870f, 0.910f),

    BEACH("Plage", 0.820f, 0.760f, 0.560f),
    DESERT("Désert", 0.800f, 0.700f, 0.450f),
    SEMI_DESERT("Semi-désert", 0.700f, 0.630f, 0.420f),
    STEPPE("Steppe", 0.620f, 0.600f, 0.360f),
    SAVANNA("Savane", 0.560f, 0.560f, 0.250f),
    GRASSLAND("Prairie", 0.330f, 0.520f, 0.230f),
    TEMPERATE_FOREST("Forêt tempérée", 0.150f, 0.420f, 0.180f),
    BOREAL_FOREST("Forêt boréale", 0.130f, 0.310f, 0.200f),
    RAINFOREST("Forêt tropicale", 0.060f, 0.340f, 0.120f),
    WETLAND("Zone humide", 0.230f, 0.410f, 0.290f),

    TUNDRA("Toundra", 0.430f, 0.460f, 0.400f),
    ALPINE("Alpin", 0.480f, 0.470f, 0.450f),
    BARE_ROCK("Roche nue", 0.400f, 0.380f, 0.360f),
    SNOW("Neige", 0.930f, 0.945f, 0.960f),
    GLACIER("Glacier", 0.870f, 0.910f, 0.950f);

    val isWater: Boolean
        get() = this == DEEP_OCEAN || this == OCEAN || this == SHALLOW_SEA || this == SEA_ICE

    companion object {

        /**
         * Détermine le biome d'un point.
         *
         * @param altitudeM altitude en mètres ; négative sous le niveau de la mer
         * @param temperatureC température moyenne annuelle en degrés Celsius
         * @param precipMm précipitations annuelles en millimètres
         */
        fun classify(altitudeM: Float, temperatureC: Float, precipMm: Float): Biome {

            // --- Domaine marin ---
            if (altitudeM < 0f) {
                if (temperatureC < -1.8f) return SEA_ICE          // point de congélation de l'eau de mer
                return when {
                    altitudeM < -3000f -> DEEP_OCEAN
                    altitudeM < -200f -> OCEAN                    // au-delà du plateau continental
                    else -> SHALLOW_SEA
                }
            }

            // --- Domaine terrestre ---
            if (temperatureC < -8f) return GLACIER
            if (temperatureC < -2f) return SNOW

            if (altitudeM < 12f && precipMm < 1800f) return BEACH

            if (altitudeM > 4200f) return if (temperatureC < 2f) SNOW else BARE_ROCK
            if (altitudeM > 3000f) return ALPINE

            if (temperatureC < 3f) return TUNDRA

            if (precipMm < 200f) return DESERT
            if (precipMm < 400f) return if (temperatureC > 20f) SEMI_DESERT else STEPPE

            if (temperatureC < 8f) return BOREAL_FOREST

            if (precipMm < 800f) {
                return if (temperatureC > 21f) SAVANNA else GRASSLAND
            }

            if (precipMm > 2200f) {
                return if (temperatureC > 20f) RAINFOREST else WETLAND
            }

            return if (temperatureC > 23f && precipMm > 1600f) RAINFOREST else TEMPERATE_FOREST
        }
    }
}
