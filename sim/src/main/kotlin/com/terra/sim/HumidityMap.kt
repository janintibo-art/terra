package com.terra.sim

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Carte d'humidité équirectangulaire — lot 2.14 b.
 *
 * Solde une dette : le lot 2.14 prescrivait une couche nuageuse « pilotée
 * par l'humidité simulée en Phase 1 », et la v0.26.0 a livré un bruit pur.
 * Les déserts avaient donc autant de nuages que les forêts tropicales,
 * alors que le transport d'humidité (v0.21.0) sait exactement où il pleut.
 * C'était le seul endroit du moteur où l'apparence contredisait la
 * simulation.
 *
 * Le rendu a besoin d'interroger l'humidité par DIRECTION, des centaines de
 * milliers de fois par image : la recherche du plus proche voisin sur
 * l'icosphère est hors de question. On projette donc une fois par monde sur
 * une grille latitude/longitude, que le GPU échantillonne en temps constant.
 *
 * Dimensions en puissances de deux : OpenGL ES 2 ne garantit le filtrage
 * linéaire et la répétition que dans ce cas.
 */
object HumidityMap {

    const val WIDTH = 256
    const val HEIGHT = 128

    /**
     * Précipitations en deçà desquelles le ciel est réputé sec, en mm/an.
     * Aligné sur [LocalWeather.DRY_THRESHOLD_MM] : ce qui ne fait pas pleuvoir
     * au sol ne doit pas non plus faire de nuage au-dessus.
     */
    const val DRY_MM = LocalWeather.DRY_THRESHOLD_MM

    /** Précipitations donnant un ciel pleinement couvert, en mm/an. */
    const val WET_MM = LocalWeather.WET_SATURATION_MM

    /**
     * Couverture minimale conservée au-dessus d'un désert absolu.
     *
     * Pas zéro : un ciel rigoureusement vide sur un tiers de la planète
     * paraîtrait cassé plutôt que sec, et même le Sahara voit passer des
     * cirrus. Un cinquième laisse le contraste franc tout en gardant le ciel
     * vivant partout.
     */
    const val MIN_COVER = 0.20f

    /**
     * Construit la carte : un octet par cellule, 0 = ciel sec, 255 = couvert.
     *
     * L'échantillonnage se fait par plus proche voisin sur l'icosphère avec
     * indice de départ glissant — les cellules voisines de la grille le sont
     * aussi sur la sphère, la marche converge en un ou deux pas.
     */
    fun build(data: PlanetData): ByteArray {
        val out = ByteArray(WIDTH * HEIGHT)
        val sampler = CoarseSampler(data)
        var hint = 0
        for (j in 0 until HEIGHT) {
            // Centre de cellule : la latitude va de +90° (haut) à −90°.
            val lat = (0.5 - (j + 0.5) / HEIGHT) * PI
            val cosLat = cos(lat).toFloat()
            val sinLat = sin(lat).toFloat()
            for (i in 0 until WIDTH) {
                val lon = ((i + 0.5) / WIDTH) * 2.0 * PI - PI
                // Convention figée du projet : axe polaire Y, longitude
                // atan2(z, x) — la même que Geodesy, sans quoi les nuages
                // seraient décalés en longitude par rapport au sol.
                val v = com.terra.core.Vec3(
                    cosLat * cos(lon).toFloat(),
                    sinLat,
                    cosLat * sin(lon).toFloat()
                )
                hint = sampler.nearestVertex(v, hint)
                val mm = data.precipMm[hint]
                val t = ((mm - DRY_MM) / (WET_MM - DRY_MM)).coerceIn(0f, 1f)
                val cover = MIN_COVER + (1f - MIN_COVER) * t
                out[j * WIDTH + i] = (cover * 255f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /**
     * Lecture de la carte pour une direction donnée, en [0, 1]. Réplique
     * exacte de ce que le shader calcule — sert aux tests et à tout usage
     * côté processeur.
     */
    fun coverAt(map: ByteArray, direction: com.terra.core.Vec3): Float {
        val lat = asin(direction.y.coerceIn(-1f, 1f)).toDouble()
        val lon = atan2(direction.z.toDouble(), direction.x.toDouble())
        val u = ((lon + PI) / (2.0 * PI)).coerceIn(0.0, 0.999999)
        val vv = (0.5 - lat / PI).coerceIn(0.0, 0.999999)
        val i = (u * WIDTH).toInt().coerceIn(0, WIDTH - 1)
        val j = (vv * HEIGHT).toInt().coerceIn(0, HEIGHT - 1)
        return (map[j * WIDTH + i].toInt() and 0xFF) / 255f
    }
}
