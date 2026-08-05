package com.terra.sim

import com.terra.core.PI_F
import com.terra.core.Vec3
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan

/**
 * Projection cube-sphère — fondation du découpage adaptatif (lot 2.2).
 *
 * ## Pourquoi changer de découpage
 *
 * L'icosphère est idéale pour porter une **simulation** : ses cellules sont
 * quasi équivalentes en surface, sans singularité polaire. Elle est en revanche
 * inadaptée au **rendu adaptatif** : ses triangles n'ont pas de structure
 * hiérarchique commode, et rien n'y ressemble à un quadtree.
 *
 * Le cube-sphère, lui, projette les six faces d'un cube sur la sphère. Chaque
 * face est un carré paramétré par deux coordonnées dans [-1, 1], que l'on peut
 * subdiviser récursivement en quatre — exactement ce qu'attend un quadtree.
 *
 * Les deux coexistent : l'icosphère porte le monde simulé, le cube-sphère porte
 * la géométrie affichée, et le second interroge le premier.
 *
 * ## La déformation tangente
 *
 * Une projection cube-sphère naïve produit des cellules très inégales : celles
 * du centre des faces couvrent près de cinq fois la surface de celles des coins.
 * Appliquer `tan(x·π/4)` aux coordonnées avant projection ramène ce rapport de
 * **4,74 à 1,37** — mesuré, pas estimé. Les tuiles deviennent comparables
 * partout, ce qui rend le critère de subdivision homogène sur toute la planète.
 *
 * ## Convention
 *
 * Les six faces sont numérotées ainsi, en cohérence avec l'axe Y polaire retenu
 * pour tout le projet :
 *
 * | Face | Direction | Rôle |
 * |------|-----------|------|
 * | 0 | +X | équatoriale |
 * | 1 | −X | équatoriale |
 * | 2 | +Y | pôle nord |
 * | 3 | −Y | pôle sud |
 * | 4 | +Z | équatoriale |
 * | 5 | −Z | équatoriale |
 */
object CubeSphere {

    const val FACE_COUNT = 6

    const val FACE_POS_X = 0
    const val FACE_NEG_X = 1
    const val FACE_POS_Y = 2
    const val FACE_NEG_Y = 3
    const val FACE_POS_Z = 4
    const val FACE_NEG_Z = 5

    /** Déformation qui égalise les surfaces. */
    fun warp(x: Float): Float = tan(x * PI_F * 0.25f)

    /** Réciproque de [warp]. */
    fun unwarp(y: Float): Float = atan(y) * 4f / PI_F

    /**
     * Point de la sphère unité correspondant à la coordonnée (s, t) de la face.
     * s et t appartiennent à [-1, 1].
     */
    fun toSphere(face: Int, s: Float, t: Float): Vec3 {
        val u = warp(s)
        val v = warp(t)
        return when (face) {
            FACE_POS_X -> Vec3(1f, v, -u)
            FACE_NEG_X -> Vec3(-1f, v, u)
            FACE_POS_Y -> Vec3(u, 1f, -v)
            FACE_NEG_Y -> Vec3(u, -1f, v)
            FACE_POS_Z -> Vec3(u, v, 1f)
            else -> Vec3(-u, v, -1f)
        }.normalized()
    }

    /**
     * Face et coordonnées d'un point de la sphère.
     *
     * Sur une arête ou un coin, plusieurs faces sont également valides ; le
     * choix est alors arbitraire mais déterministe. La position reconstruite
     * reste identique, ce qui est la seule propriété dont dépend le rendu.
     *
     * @return un triplet (face, s, t)
     */
    fun fromSphere(p: Vec3): Triple<Int, Float, Float> {
        val x = p.x; val y = p.y; val z = p.z
        val ax = abs(x); val ay = abs(y); val az = abs(z)

        val face: Int
        val u: Float
        val v: Float

        if (ax >= ay && ax >= az) {
            if (x > 0f) { face = FACE_POS_X; u = -z / x; v = y / x }
            else { face = FACE_NEG_X; u = z / -x; v = y / -x }
        } else if (ay >= az) {
            if (y > 0f) { face = FACE_POS_Y; u = x / y; v = -z / y }
            else { face = FACE_NEG_Y; u = x / -y; v = z / -y }
        } else {
            if (z > 0f) { face = FACE_POS_Z; u = x / z; v = y / z }
            else { face = FACE_NEG_Z; u = x / z; v = y / -z }
        }
        return Triple(face, unwarp(u), unwarp(v))
    }

    /** Vrai si la face regarde majoritairement vers le point donné. */
    fun faceOf(p: Vec3): Int = fromSphere(p).first
}
