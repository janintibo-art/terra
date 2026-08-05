package com.terra.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vecteur 3D en double précision.
 *
 * ## Pourquoi une seconde classe de vecteurs
 *
 * [Vec3] travaille en flottant 32 bits, ce qui convient à la géométrie
 * normalisée : sur la sphère unité, la précision est d'environ un dix-millionième,
 * largement suffisant.
 *
 * Elle ne convient pas aux **positions métriques**. À 6 371 000 m du centre, le
 * plus petit écart représentable en 32 bits vaut cinquante centimètres : une
 * caméra posée au sol tremblerait visiblement à chaque image.
 *
 * Le double précision descend sous le milliardième de millimètre à la même
 * distance. C'est pourquoi tout ce qui touche à la caméra et au lancer de rayon
 * vit ici, et n'est converti en 32 bits qu'au tout dernier moment, une fois les
 * coordonnées ramenées près de l'observateur.
 */
data class Vec3d(val x: Double, val y: Double, val z: Double) {

    operator fun plus(o: Vec3d) = Vec3d(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3d) = Vec3d(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3d(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3d(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3d(-x, -y, -z)

    infix fun dot(o: Vec3d): Double = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3d) = Vec3d(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )

    val lengthSq: Double get() = x * x + y * y + z * z
    val length: Double get() = sqrt(lengthSq)

    fun normalized(): Vec3d {
        val l = length
        return if (l > 1e-300) Vec3d(x / l, y / l, z / l) else ZERO
    }

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    /** Conversion vers le flottant 32 bits, pour l'envoi au GPU. */
    fun toVec3(): Vec3 = Vec3(x.toFloat(), y.toFloat(), z.toFloat())

    /**
     * Rotation autour d'un axe unitaire, formule de Rodrigues.
     * Employée pour déplacer la caméra le long de la surface : contrairement à
     * une translation tangente suivie d'une renormalisation, elle reste exacte
     * quelle que soit l'amplitude du déplacement.
     */
    fun rotatedAround(axis: Vec3d, angleRad: Double): Vec3d {
        val a = axis.normalized()
        val c = cos(angleRad)
        val s = sin(angleRad)
        return this * c + (a cross this) * s + a * ((a dot this) * (1.0 - c))
    }

    companion object {
        val ZERO = Vec3d(0.0, 0.0, 0.0)
        val UNIT_X = Vec3d(1.0, 0.0, 0.0)
        val UNIT_Y = Vec3d(0.0, 1.0, 0.0)
        val UNIT_Z = Vec3d(0.0, 0.0, 1.0)

        fun of(v: Vec3) = Vec3d(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
    }
}

/**
 * Géodésie en double précision, sur la même convention que [Sphere] :
 * axe Y polaire, latitude dans [−π/2, π/2], longitude dans [−π, π].
 */
object Geodesy {

    fun toUnit(latRad: Double, lonRad: Double): Vec3d {
        val cl = cos(latRad)
        return Vec3d(cl * cos(lonRad), sin(latRad), cl * sin(lonRad))
    }

    fun latitude(p: Vec3d): Double {
        val n = p.normalized()
        return asin(n.y.coerceIn(-1.0, 1.0))
    }

    fun longitude(p: Vec3d): Double = atan2(p.z, p.x)

    /** Angle au centre entre deux directions, en radians. */
    fun angleBetween(a: Vec3d, b: Vec3d): Double =
        acos((a.normalized() dot b.normalized()).coerceIn(-1.0, 1.0))

    /** Vecteur unitaire pointant vers le nord dans le plan tangent en [p]. */
    fun northAt(p: Vec3d): Vec3d {
        val up = p.normalized()
        // Aux pôles, la direction du nord est indéfinie : on choisit une
        // référence arbitraire mais stable pour éviter un vecteur nul.
        val ref = if (abs(up.y) > 0.999999) Vec3d.UNIT_Z else Vec3d.UNIT_Y
        val east = (ref cross up).normalized()
        return (up cross east).normalized()
    }

    /** Vecteur unitaire pointant vers l'est dans le plan tangent en [p]. */
    fun eastAt(p: Vec3d): Vec3d {
        val up = p.normalized()
        val ref = if (abs(up.y) > 0.999999) Vec3d.UNIT_Z else Vec3d.UNIT_Y
        return (ref cross up).normalized()
    }

    /**
     * Déplace une direction unitaire le long de la surface, d'une distance
     * exprimée en radians, dans une direction tangente donnée.
     */
    fun move(from: Vec3d, tangentDir: Vec3d, angleRad: Double): Vec3d {
        if (angleRad == 0.0) return from
        val up = from.normalized()
        val axis = (up cross tangentDir).normalized()
        if (!axis.isFinite() || axis.lengthSq < 1e-18) return from
        return up.rotatedAround(axis, -angleRad).normalized()
    }
}
