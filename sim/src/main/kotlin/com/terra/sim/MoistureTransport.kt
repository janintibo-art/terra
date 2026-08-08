package com.terra.sim

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Transport d'humidité — lot 1.14. Remplace les bandes latitudinales
 * provisoires du lot 1.3 par le mécanisme complet, validé numériquement
 * dans `validation/humidite_calibrage.py` (ITCZ ×6,5 sur les subtropiques,
 * ombre pluviométrique ×2, façades d'alizés ×8 sur les intérieurs) :
 *
 *  1. ÉVAPORATION : la mer charge l'air selon sa température, la terre
 *     évapotranspire au quart.
 *  2. ADVECTION : à chaque passe, chaque cellule reçoit l'humidité de ses
 *     deux voisines les plus AU VENT, en double tampon — l'ordre de
 *     parcours ne compte pas, le déterminisme est structurel.
 *  3. PLUIE : condensation de base modulée par les mouvements verticaux
 *     des trois cellules (ascendance équatoriale étroite, subsidence des
 *     latitudes des chevaux, ascendance du front polaire), soulèvement
 *     orographique, et capacité thermique de l'air (Clausius-Clapeyron,
 *     6,5 %/°C) ; la subsidence VENTILE en plus la couche humide — deux
 *     leçons du calibrage : retenir la pluie sans assécher fait pleuvoir
 *     quand même, et lire la subsidence dans la dérivée du vent de
 *     surface l'étale sur 30° et vide les alizés avant l'équateur.
 *
 * Rien n'est imposé : l'équateur pleut parce que les alizés y convergent,
 * les déserts subtropicaux et d'abri sont des conséquences. Le champ final
 * est normalisé sur le BUDGET du modèle précédent (même moyenne
 * planétaire) : la répartition change du tout au tout, l'équilibre global
 * des biomes — donc les glaces, les seuils du banc d'essai — est préservé
 * par construction.
 */
object MoistureTransport {

    // Constantes du calibrage, à l'identique du script Python.
    const val EVAP_OCEAN = 1.00f
    const val EVAP_LAND = 0.25f
    const val BASE_RAIN = 0.10f
    const val ORO_RAIN_PER_KM = 0.35f
    const val CC_BASE = 3.0f
    const val CC_RATE = 0.065f
    const val ADVECT = 0.85f
    const val MAX_LOSS = 0.9f

    /**
     * Nombre de passes : la longueur de transport doit être une DISTANCE,
     * pas un nombre de mailles, sinon le climat dépendrait de la
     * résolution. Soixante passes au niveau 5 (mailles de ~250 km), moitié
     * moins au niveau 4, double au niveau 6.
     */
    fun passes(subdivisions: Int): Int = 15 shl (subdivisions - 3).coerceIn(0, 4)

    /** Mouvement vertical des trois cellules ; positif = ascendance. */
    fun wVertical(absLatDeg: Float): Float =
        1.0f * g(absLatDeg, 0f, 8f) -
            1.0f * g(absLatDeg, 28f, 7f) +
            0.6f * g(absLatDeg, 60f, 9f) -
            0.4f * g(absLatDeg, 85f, 8f)

    fun rainFactor(absLatDeg: Float): Float =
        min(2.4f, max(0.25f, 1f + 1.4f * wVertical(absLatDeg)))

    fun ventFactor(absLatDeg: Float): Float =
        1f - 0.5f * max(0f, -wVertical(absLatDeg))

    private fun g(x: Float, mu: Float, w: Float): Float {
        val t = (x - mu) / w
        return exp(-t * t)
    }

    /**
     * Calcule les précipitations, en millimètres par an, normalisées pour
     * que la moyenne planétaire égale [targetMeanMm] (le budget de l'ancien
     * modèle), bornées à [maxPrecipMm].
     */
    fun build(
        sphere: Icosphere,
        adjacency: Array<IntArray>,
        altitudeM: FloatArray,
        temperatureC: FloatArray,
        windEastMS: FloatArray,
        windNorthMS: FloatArray,
        subdivisions: Int,
        targetMeanMm: Float,
        maxPrecipMm: Float
    ): FloatArray {
        val n = sphere.vertexCount

        // --- Précalculs par cellule : voisins au vent et facteurs de bande.
        val up1 = IntArray(n); val up2 = IntArray(n)
        val w1 = FloatArray(n)                 // poids du premier ; w2 = 1 − w1
        val rainF = FloatArray(n); val ventF = FloatArray(n)
        val capacity = FloatArray(n); val evap = FloatArray(n)
        for (i in 0 until n) {
            val v = sphere.vertices[i]
            val latDeg = asin(v.y.coerceIn(-1f, 1f)) * (180f / com.terra.core.PI_F)
            rainF[i] = rainFactor(abs(latDeg))
            ventF[i] = ventFactor(abs(latDeg))
            capacity[i] = CC_BASE * exp(CC_RATE * temperatureC[i])
            evap[i] = (if (altitudeM[i] <= 0f) EVAP_OCEAN else EVAP_LAND) *
                ((temperatureC[i] + 5f) / 30f).coerceIn(0f, 1f)

            // L'humidité VIENT d'où le vent SOUFFLE : les deux voisines les
            // mieux alignées avec l'amont, pondérées par l'alignement — un
            // seul voisin crayonnerait les artefacts de la grille dans la
            // carte des pluies.
            val we = windEastMS[i]; val wn = windNorthMS[i]
            val wl = sqrt(we * we + wn * wn)
            var b1 = -1; var a1 = -2f; var b2 = -1; var a2 = -2f
            if (wl > 1e-4f) {
                for (j in adjacency[i]) {
                    val o = sphere.vertices[j]
                    // Direction tangente de j vers i, projetée est/nord en i.
                    val radial = v.x * o.x + v.y * o.y + v.z * o.z
                    val tx = v.x - o.x * radial
                    val ty = v.y - o.y * radial
                    val tz = v.z - o.z * radial
                    // Est local (−z, 0, x) et nord = haut × est en i.
                    val ex = -v.z; val ez = v.x
                    val el = sqrt(ex * ex + ez * ez)
                    if (el < 1e-4f) continue
                    val east = (tx * ex + tz * ez) / el
                    // Nord local = est × haut, développé : le vecteur
                    // (−y·x, x²+z², −y·z)/el est unitaire par construction.
                    val north = (tx * (-v.y * v.x) +
                        ty * (v.x * v.x + v.z * v.z) +
                        tz * (-v.y * v.z)) / el
                    val align = (east * we + north * wn) / wl
                    if (align > a1) { a2 = a1; b2 = b1; a1 = align; b1 = j }
                    else if (align > a2) { a2 = align; b2 = j }
                }
            }
            if (b1 < 0) { b1 = adjacency[i][0]; a1 = 1f }
            if (b2 < 0) { b2 = b1; a2 = 0f }
            up1[i] = b1; up2[i] = b2
            val p1 = max(0f, a1); val p2 = max(0f, a2)
            w1[i] = if (p1 + p2 < 1e-6f) 1f else p1 / (p1 + p2)
        }

        // --- Passes de transport, double tampon.
        var h = FloatArray(n)
        var h2 = FloatArray(n)
        val rain = FloatArray(n)
        repeat(passes(subdivisions)) {
            for (i in 0 until n) {
                val u1 = up1[i]; val u2 = up2[i]; val k = w1[i]
                val incoming = h[u1] * k + h[u2] * (1f - k)
                val upAlt = altitudeM[u1] * k + altitudeM[u2] * (1f - k)
                var x = ADVECT * incoming + (1f - ADVECT) * h[i]

                val oro = ORO_RAIN_PER_KM *
                    max(0f, altitudeM[i] - upAlt) / 1000f
                val loss = min(MAX_LOSS, BASE_RAIN * rainF[i] + oro)
                var r = x * loss
                x -= r
                if (x > capacity[i]) { r += x - capacity[i]; x = capacity[i] }
                x += evap[i]
                x *= ventF[i]
                h2[i] = x
                rain[i] = r
            }
            val t = h; h = h2; h2 = t
        }

        // --- Normalisation sur le budget de l'ancien modèle.
        var mean = 0.0
        for (i in 0 until n) mean += rain[i]
        mean /= n
        val scale = if (mean > 1e-9) targetMeanMm / mean.toFloat() else 0f
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = (rain[i] * scale).coerceIn(0f, maxPrecipMm)
        }
        return out
    }
}
