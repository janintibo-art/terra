package com.terra.sim

import com.terra.core.clamp01
import com.terra.core.lerp

/**
 * Calques de visualisation.
 *
 * Juger un climat sur des couleurs de biome revient à diagnostiquer un moteur en
 * écoutant le bruit qu'il fait. Ces calques donnent accès aux grandeurs
 * sous-jacentes : on voit directement si le gradient de température est
 * plausible, si les déserts tombent bien sous les tropiques, si les montagnes
 * sont réparties ou concentrées.
 *
 * Ils resteront utiles bien au-delà de la Phase 1 : la carte des plaques, des
 * populations et des territoires viendra s'ajouter ici.
 */
enum class MapLayer(val label: String, val shortLabel: String) {
    BIOME("Biomes", "Biomes"),
    ALTITUDE("Altitude", "Relief"),
    TEMPERATURE("Température", "Temp."),
    PRECIPITATION("Précipitations", "Pluie"),
    CLIMATE_ZONES("Zones climatiques", "Zones");

    companion object {
        fun next(current: MapLayer): MapLayer {
            val v = values()
            return v[(current.ordinal + 1) % v.size]
        }
    }
}

/**
 * Palettes. Reçoit un tableau de trois flottants à remplir, pour éviter
 * d'allouer un objet par sommet — soit 10 242 allocations par changement de
 * calque.
 */
object LayerPalette {

    fun color(layer: MapLayer, data: PlanetData, index: Int, out: FloatArray) {
        when (layer) {
            MapLayer.BIOME -> biome(data, index, out)
            MapLayer.ALTITUDE -> altitude(data, index, out)
            MapLayer.TEMPERATURE -> temperature(data, index, out)
            MapLayer.PRECIPITATION -> precipitation(data, index, out)
            MapLayer.CLIMATE_ZONES -> climateZone(data, index, out)
        }
    }

    private fun biome(data: PlanetData, i: Int, out: FloatArray) {
        val b = data.biome(i)
        out[0] = b.r; out[1] = b.g; out[2] = b.b
    }

    /** Hypsométrie classique : bathymétrie bleue, terres vert → brun → blanc. */
    private fun altitude(data: PlanetData, i: Int, out: FloatArray) {
        val a = data.altitudeM[i]
        if (a < 0f) {
            val t = clamp01(-a / data.params.maxDepthM)
            out[0] = lerp(0.35f, 0.02f, t)
            out[1] = lerp(0.62f, 0.10f, t)
            out[2] = lerp(0.85f, 0.32f, t)
        } else {
            val t = clamp01(a / data.params.maxAltitudeM)
            when {
                t < 0.30f -> ramp(t / 0.30f, 0.18f, 0.55f, 0.22f, 0.75f, 0.72f, 0.35f, out)
                t < 0.65f -> ramp((t - 0.30f) / 0.35f, 0.75f, 0.72f, 0.35f, 0.55f, 0.38f, 0.22f, out)
                else -> ramp((t - 0.65f) / 0.35f, 0.55f, 0.38f, 0.22f, 0.98f, 0.98f, 0.98f, out)
            }
        }
    }

    /** Du bleu glacé au rouge, avec le zéro Celsius marqué en blanc. */
    private fun temperature(data: PlanetData, i: Int, out: FloatArray) {
        val c = data.temperatureC[i]
        when {
            c < 0f -> {
                val t = clamp01((c + 40f) / 40f)
                ramp(t, 0.10f, 0.10f, 0.45f, 0.92f, 0.95f, 1.00f, out)
            }
            else -> {
                val t = clamp01(c / 40f)
                when {
                    t < 0.5f -> ramp(t / 0.5f, 0.92f, 0.95f, 1.00f, 0.98f, 0.85f, 0.30f, out)
                    else -> ramp((t - 0.5f) / 0.5f, 0.98f, 0.85f, 0.30f, 0.75f, 0.10f, 0.08f, out)
                }
            }
        }
    }

    /** Du sable aride au bleu-vert saturé des zones les plus arrosées. */
    private fun precipitation(data: PlanetData, i: Int, out: FloatArray) {
        val t = clamp01(data.precipMm[i] / data.params.maxPrecipMm)
        when {
            t < 0.35f -> ramp(t / 0.35f, 0.88f, 0.78f, 0.50f, 0.55f, 0.78f, 0.42f, out)
            else -> ramp((t - 0.35f) / 0.65f, 0.55f, 0.78f, 0.42f, 0.05f, 0.30f, 0.55f, out)
        }
    }

    /**
     * Grandes zones climatiques, calquées sur les bandes de Köppen simplifiées.
     * Permet de vérifier d'un coup d'œil que les déserts se forment bien vers
     * 25-30° de latitude et non n'importe où.
     */
    private fun climateZone(data: PlanetData, i: Int, out: FloatArray) {
        if (data.altitudeM[i] < 0f) {
            out[0] = 0.08f; out[1] = 0.13f; out[2] = 0.22f
            return
        }
        val c = data.temperatureC[i]
        val p = data.precipMm[i]
        when {
            c < -2f -> { out[0] = 0.85f; out[1] = 0.90f; out[2] = 0.95f }  // polaire
            c < 8f -> { out[0] = 0.35f; out[1] = 0.55f; out[2] = 0.75f }   // froid
            p < 400f -> { out[0] = 0.92f; out[1] = 0.78f; out[2] = 0.35f } // aride
            c > 20f && p > 1800f -> { out[0] = 0.10f; out[1] = 0.60f; out[2] = 0.25f } // tropical humide
            c > 20f -> { out[0] = 0.65f; out[1] = 0.75f; out[2] = 0.25f }  // tropical sec
            else -> { out[0] = 0.30f; out[1] = 0.65f; out[2] = 0.45f }     // tempéré
        }
    }

    private fun ramp(
        t: Float,
        r0: Float, g0: Float, b0: Float,
        r1: Float, g1: Float, b1: Float,
        out: FloatArray
    ) {
        val u = clamp01(t)
        out[0] = lerp(r0, r1, u)
        out[1] = lerp(g0, g1, u)
        out[2] = lerp(b0, b1, u)
    }
}
