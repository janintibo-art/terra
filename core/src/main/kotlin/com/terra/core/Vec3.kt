package com.terra.core

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vecteur 3D immuable.
 *
 * L'immuabilité coûte des allocations dans les boucles très chaudes ; là où ce
 * sera mesuré comme un problème (Phase 4), on passera sur des tableaux de
 * flottants à plat plutôt que sur des objets. Tant que rien n'est mesuré, on
 * privilégie la lisibilité et la sûreté.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {

    operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float): Vec3 = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float): Vec3 = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3): Vec3 = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )

    val lengthSq: Float get() = x * x + y * y + z * z
    val length: Float get() = sqrt(lengthSq)

    fun normalized(): Vec3 {
        val l = length
        return if (l > 1e-20f) Vec3(x / l, y / l, z / l) else ZERO
    }

    fun isFinite(): Boolean =
        x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UNIT_X = Vec3(1f, 0f, 0f)
        val UNIT_Y = Vec3(0f, 1f, 0f)
        val UNIT_Z = Vec3(0f, 0f, 1f)
    }
}

/**
 * Géométrie sur la sphère unité.
 *
 * Convention retenue pour tout le projet :
 *   - l'axe Y est l'axe de rotation de la planète (pôle nord = +Y)
 *   - la latitude est en radians dans [-PI/2, +PI/2]
 *   - la longitude est en radians dans [-PI, +PI]
 *
 * Cette convention est fixée une fois pour toutes : la changer plus tard
 * invaliderait tous les mondes déjà générés.
 */
object Sphere {

    fun toVec(latRad: Float, lonRad: Float): Vec3 {
        val cl = cos(latRad)
        return Vec3(cl * cos(lonRad), sin(latRad), cl * sin(lonRad))
    }

    fun latitude(v: Vec3): Float {
        val n = v.normalized()
        return asin(clamp(n.y, -1f, 1f))
    }

    fun longitude(v: Vec3): Float = atan2(v.z, v.x)

    /** Distance géodésique en radians (à multiplier par le rayon pour des mètres). */
    fun geodesic(a: Vec3, b: Vec3): Float =
        acos(clamp(a.normalized() dot b.normalized(), -1f, 1f))

    /** Interpolation le long du grand cercle. */
    fun slerp(a: Vec3, b: Vec3, t: Float): Vec3 {
        val an = a.normalized()
        val bn = b.normalized()
        val d = clamp(an dot bn, -1f, 1f)
        val theta = acos(d)
        if (theta < 1e-5f) return (an * (1f - t) + bn * t).normalized()
        val st = sin(theta)
        return (an * (sin((1f - t) * theta) / st) + bn * (sin(t * theta) / st)).normalized()
    }

    /** Point aléatoire uniformément réparti sur la sphère (méthode d'Archimède). */
    fun randomPoint(rng: Rng): Vec3 {
        val y = rng.nextFloat() * 2f - 1f
        val phi = rng.nextFloat() * TAU
        val r = sqrt(clamp01(1f - y * y))
        return Vec3(r * cos(phi), y, r * sin(phi))
    }
}
