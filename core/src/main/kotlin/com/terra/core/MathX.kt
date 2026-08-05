package com.terra.core

/**
 * Fonctions mathématiques scalaires partagées par tout le projet.
 *
 * Elles vivent dans [:core] et non dans le moteur de rendu : la simulation doit
 * pouvoir tourner sans Android ni OpenGL, sur une simple JVM, pour rester
 * testable automatiquement.
 */

const val TAU: Float = 6.2831853071795865f
const val PI_F: Float = 3.1415926535897932f
const val DEG_TO_RAD: Float = PI_F / 180f
const val RAD_TO_DEG: Float = 180f / PI_F

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v

fun clamp01(v: Float): Float = clamp(v, 0f, 1f)

fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val span = edge1 - edge0
    if (span == 0f) return if (x < edge0) 0f else 1f
    val t = clamp01((x - edge0) / span)
    return t * t * (3f - 2f * t)
}

/** Interpolation à dérivées première et seconde nulles aux bords (Perlin, 2002). */
fun smootherstep(edge0: Float, edge1: Float, x: Float): Float {
    val span = edge1 - edge0
    if (span == 0f) return if (x < edge0) 0f else 1f
    val t = clamp01((x - edge0) / span)
    return t * t * t * (t * (t * 6f - 15f) + 10f)
}

fun remap(v: Float, inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
    val span = inMax - inMin
    if (span == 0f) return outMin
    return outMin + (v - inMin) / span * (outMax - outMin)
}

/** Remappe puis borne dans l'intervalle de sortie. */
fun remapClamped(v: Float, inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
    val r = remap(v, inMin, inMax, outMin, outMax)
    return if (outMin <= outMax) clamp(r, outMin, outMax) else clamp(r, outMax, outMin)
}
