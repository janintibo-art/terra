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
    CLIMATE_ZONES("Zones climatiques", "Zones"),
    PLATES("Plaques tectoniques", "Plaques"),
    RIVERS("Écoulement et érosion", "Eaux");

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
            MapLayer.PLATES -> plate(data, index, out)
            MapLayer.RIVERS -> rivers(data, index, out)
        }
    }

    /**
     * Écoulement : le débit cumulé en bleu croissant sur fond de relief, les
     * cuvettes comblées (futurs lacs) en turquoise. C'est la carte de ce que
     * les lots 1.10 et 1.11 transformeront en rivières et en lacs.
     */
    private fun rivers(data: PlanetData, i: Int, out: FloatArray) {
        val h = data.hydrology
        if (data.altitudeM[i] <= 0f) {
            out[0] = 0.06f; out[1] = 0.10f; out[2] = 0.20f
            return
        }
        // Les lacs retenus (au-delà du seuil) en turquoise franc, les
        // cuvettes trop faibles pour en devenir en turquoise éteint : on voit
        // ainsi ce que le seuil a écarté, et de combien.
        val fill = h.fillDepthM[i]
        if (fill >= TerrainProfile.LAKE_MIN_DEPTH_M) {
            out[0] = 0.20f; out[1] = 0.78f; out[2] = 0.74f
            return
        }
        if (fill > 5f) {
            out[0] = 0.18f; out[1] = 0.42f; out[2] = 0.42f
            return
        }
        // Échelle logarithmique : le débit s'étale sur trois décades, une
        // échelle linéaire ne montrerait que le fleuve principal.
        val flow = kotlin.math.ln(1f + h.flowAccum[i]) / kotlin.math.ln(1f + 800f)
        val t = clamp01(flow)
        val base = 0.30f + 0.35f * clamp01(data.altitudeM[i] / data.params.maxAltitudeM)
        out[0] = base * (1f - t) + 0.05f * t
        out[1] = base * (1f - t) + 0.45f * t
        out[2] = base * (1f - t) + 0.95f * t
    }

    /**
     * Couleur de plaque, assombrie sous l'océan actuel : on voit d'un coup
     * d'œil où le futur relief tectonique (lot 1.6) devra contredire ou
     * confirmer le trait de côte issu du bruit.
     */
    private fun plate(data: PlanetData, i: Int, out: FloatArray) {
        // Les frontières priment sur la teinte de plaque : c'est leur nature
        // qui décidera du relief au lot 1.6, autant la voir dès maintenant.
        // Rouge : convergence ; turquoise : divergence ; jaune : coulissage.
        val bt = data.boundaries.vertexType[i].toInt()
        if (bt >= 0) {
            when (bt) {
                BoundaryType.CONVERGENT.ordinal -> { out[0] = 0.86f; out[1] = 0.16f; out[2] = 0.10f }
                BoundaryType.DIVERGENT.ordinal -> { out[0] = 0.10f; out[1] = 0.74f; out[2] = 0.62f }
                else -> { out[0] = 0.92f; out[1] = 0.80f; out[2] = 0.16f }
            }
            return
        }
        val p = data.plates.plateOf(i)
        val dim = if (data.altitudeM[i] < 0f) 0.55f else 1f
        var r = p.r * dim; var g = p.g * dim; var b = p.b * dim

        // Halo : la teinte de la frontière la plus proche déteint sur ~600 km
        // (0,094 rad), proportionnellement à sa proximité — on lit d'un coup
        // d'œil la portée du futur relief du lot 1.6, pas seulement son trait.
        val bd = data.boundaryDistance
        val dc = bd.distConvergent[i]
        val dd = bd.distDivergent[i]
        val haloRad = 0.094f
        if (dc < dd && dc < haloRad) {
            val w = (1f - dc / haloRad) * 0.55f
            r += (0.86f - r) * w; g += (0.16f - g) * w; b += (0.10f - b) * w
        } else if (dd < haloRad) {
            val w = (1f - dd / haloRad) * 0.55f
            r += (0.10f - r) * w; g += (0.74f - g) * w; b += (0.62f - b) * w
        }
        out[0] = r; out[1] = g; out[2] = b
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
