package com.terra.sim

import com.terra.core.DEG_TO_RAD
import com.terra.core.Seed
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Circulation atmosphérique de surface — lot 1.13.
 *
 * Trois cellules par hémisphère (Hadley, Ferrel, polaire), en somme de
 * gaussiennes centrées sur le cœur de chaque régime : alizés d'est vers
 * 15°, vents d'ouest vers 45°, vents polaires d'est vers 75°, plus une
 * composante d'est équatoriale — les alizés traversent l'équateur, une
 * gaussienne à 15° n'y arrive pas. Le zonal encode déjà Coriolis : les
 * alizés sont d'est PARCE QUE l'air descendant vers l'équateur est dévié
 * vers l'ouest. Les branches méridiennes s'inversent avec l'hémisphère.
 *
 * Calibré contre la climatologie du vent de surface : onze latitudes de
 * 0° à 85°, toutes dans les tolérances (`validation/vents_calibrage.py`).
 * Unités physiques : mètres par seconde, est et nord positifs.
 *
 * Le champ par monde ajoute au profil une respiration propre à la planète
 * — rotation locale de la direction et modulation de la vitesse par un
 * bruit dérivé de `climat/vents` : flux de graine indépendant, donc AUCUN
 * effet sur les mondes existants. Les empreintes figées le prouvent à
 * chaque poussée ; c'est le lot 1.14 qui, en consommant ces vents pour
 * l'humidité, incrémentera GENERATION_VERSION.
 */
object WindField {

    // Amplitudes (m/s) et largeurs (degrés) du profil calibré.
    const val U_EQUATORIAL = 2.2f
    const val U_TRADES = 5.5f
    const val U_WESTERLIES = 7.5f
    const val U_POLAR = 2.5f
    const val V_HADLEY = 2.2f
    const val V_FERREL = 1.6f
    const val V_POLAR = 1.0f
    const val W_EQ = 16f
    const val W_TRADES = 12f
    const val W_WESTERLIES = 14f
    const val W_POLAR = 9f

    /** Rotation maximale de la direction par le bruit du monde, en radians. */
    const val DIRECTION_JITTER_RAD = 0.35f

    /** Modulation relative maximale de la vitesse par le bruit du monde. */
    const val SPEED_JITTER = 0.25f

    private fun g(phiDeg: Float, muDeg: Float, widthDeg: Float): Float {
        val t = (phiDeg - muDeg) / widthDeg
        return exp(-t * t)
    }

    /** Vent zonal du profil, m/s, est positif. Fonction de |latitude|. */
    fun zonalMS(absLatDeg: Float): Float =
        -U_EQUATORIAL * g(absLatDeg, 0f, W_EQ) -
            U_TRADES * g(absLatDeg, 15f, W_TRADES) +
            U_WESTERLIES * g(absLatDeg, 45f, W_WESTERLIES) -
            U_POLAR * g(absLatDeg, 75f, W_POLAR)

    /** Vent méridien du profil, m/s, nord positif. Fonction de la latitude SIGNÉE. */
    fun meridionalMS(latDeg: Float): Float {
        if (latDeg == 0f) return 0f
        val a = abs(latDeg)
        val m = -V_HADLEY * g(a, 15f, W_TRADES) +
            V_FERREL * g(a, 45f, W_WESTERLIES) -
            V_POLAR * g(a, 75f, W_POLAR)
        return if (latDeg > 0f) m else -m
    }

    /**
     * Construit le champ par sommet : (est, nord) en m/s.
     *
     * Déterministe par construction — profil analytique plus bruit à graine
     * dérivée — et sans lecture du relief : les vents du lot 1.13 sont la
     * circulation PLANÉTAIRE ; l'effet du relief sur la pluie viendra par
     * l'ombre pluviométrique du lot 1.14, pas par une déviation du vent.
     */
    fun build(masterSeed: Seed, sphere: Icosphere): Pair<FloatArray, FloatArray> {
        val n = sphere.vertexCount
        val east = FloatArray(n)
        val north = FloatArray(n)
        val angleNoise = Noise(masterSeed.derive("climat/vents/direction"))
        val speedNoise = Noise(masterSeed.derive("climat/vents/vitesse"))

        for (i in 0 until n) {
            val v = sphere.vertices[i]
            val latDeg = asin(v.y.coerceIn(-1f, 1f)) / DEG_TO_RAD
            val u0 = zonalMS(abs(latDeg))
            val v0 = meridionalMS(latDeg)

            // Respiration du monde : le bruit tourne la direction et module
            // la vitesse, à grande longueur d'onde — des régimes régionaux,
            // pas de la turbulence.
            val a = angleNoise.fbm(v.x * 1.3f, v.y * 1.3f, v.z * 1.3f, 3) *
                DIRECTION_JITTER_RAD
            val s = 1f + speedNoise.fbm(
                v.x * 1.3f + 41f, v.y * 1.3f - 17f, v.z * 1.3f + 8f, 3
            ) * SPEED_JITTER
            val ca = cos(a)
            val sa = sin(a)
            east[i] = (u0 * ca - v0 * sa) * s
            north[i] = (u0 * sa + v0 * ca) * s
        }
        return Pair(east, north)
    }

    /** Vitesse du vent au sommet, m/s. */
    fun speedMS(data: PlanetData, i: Int): Float {
        val e = data.windEastMS[i]
        val n = data.windNorthMS[i]
        return sqrt(e * e + n * n)
    }
}
