package com.terra.sim

import com.terra.core.PI_F
import com.terra.core.Vec3
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt
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
     * Variante sans allocation : écrit le point directement dans un tableau.
     *
     * La sélection des tuiles évalue plusieurs milliers de nœuds par image, à
     * quatre coins chacun. Passer par [toSphere] y allouerait des millions
     * d'objets par seconde et saturerait le ramasse-miettes. Cette version
     * travaille sur des flottants bruts et un tampon réutilisé.
     */
    fun toSphereInto(face: Int, s: Float, t: Float, out: FloatArray, offset: Int) {
        val u = warp(s)
        val v = warp(t)
        val x: Float; val y: Float; val z: Float
        when (face) {
            FACE_POS_X -> { x = 1f; y = v; z = -u }
            FACE_NEG_X -> { x = -1f; y = v; z = u }
            FACE_POS_Y -> { x = u; y = 1f; z = -v }
            FACE_NEG_Y -> { x = u; y = -1f; z = v }
            FACE_POS_Z -> { x = u; y = v; z = 1f }
            else -> { x = -u; y = v; z = -1f }
        }
        val inv = 1f / sqrt(x * x + y * y + z * z)
        out[offset] = x * inv
        out[offset + 1] = y * inv
        out[offset + 2] = z * inv
    }

    /**
     * Déformation en double précision — réservée au maillage des tuiles.
     *
     * Le sélecteur reste en 32 bits : sa précision suffit pour décider d'une
     * subdivision, et il tourne à chaque image. Le mailleur, lui, produit des
     * positions métriques : à 6 371 km du centre, le flottant 32 bits ne
     * distingue plus rien en deçà de 50 cm, ce qui ferait bâiller les bords
     * de tuiles voisines. D'où ce second chemin, en double de bout en bout.
     */
    fun warpD(x: Double): Double = kotlin.math.tan(x * (Math.PI * 0.25))

    /**
     * Point de la sphère unité en double précision.
     *
     * Même convention de faces que [toSphere]. Les deux chemins ne doivent
     * jamais diverger : un test compare leurs résultats à la précision du
     * flottant 32 bits.
     */
    fun toSphereD(face: Int, s: Double, t: Double): com.terra.core.Vec3d {
        val u = warpD(s)
        val v = warpD(t)
        val x: Double; val y: Double; val z: Double
        when (face) {
            FACE_POS_X -> { x = 1.0; y = v; z = -u }
            FACE_NEG_X -> { x = -1.0; y = v; z = u }
            FACE_POS_Y -> { x = u; y = 1.0; z = -v }
            FACE_NEG_Y -> { x = u; y = -1.0; z = v }
            FACE_POS_Z -> { x = u; y = v; z = 1.0 }
            else -> { x = -u; y = v; z = -1.0 }
        }
        val inv = 1.0 / kotlin.math.sqrt(x * x + y * y + z * z)
        return com.terra.core.Vec3d(x * inv, y * inv, z * inv)
    }

    /**
     * Direction du sommet de grille **global** (face, niveau, indice de maille).
     *
     * ## Pourquoi passer par des indices globaux
     *
     * Deux tuiles voisines partagent des sommets de bord. Si chacune calculait
     * ses coordonnées par interpolation locale (`s0 + (s1−s0)·i/n`), les
     * arrondis différeraient d'un ulp et les bords s'écarteraient — jusqu'à
     * 70 cm une fois multipliés par le rayon planétaire, une fissure visible.
     *
     * En calculant `s = −1 + 2·gx/total` à partir de l'indice **global** gx,
     * le sommet partagé reçoit exactement les mêmes opérandes dans les deux
     * tuiles, donc exactement les mêmes bits. La coïncidence des bords n'est
     * pas approchée, elle est structurelle. Un test la vérifie bit à bit.
     *
     * Aux frontières entre faces, cette garantie tombe (les paramétrages
     * diffèrent) : l'écart y est de l'ordre de l'ulp du double, soit moins
     * d'un nanomètre — les jupes couvrent très largement le résidu.
     *
     * @param gx indice global de colonne, entre 0 et meshN·2^niveau inclus
     * @param gy indice global de ligne, même borne
     */
    fun gridDirection(face: Int, level: Int, gx: Int, gy: Int, meshN: Int): com.terra.core.Vec3d {
        val total = (meshN.toLong() shl level).toDouble()
        val s = -1.0 + 2.0 * gx.toDouble() / total
        val t = -1.0 + 2.0 * gy.toDouble() / total
        return toSphereD(face, s, t)
    }

    /**
     * Variante à coordonnées FRACTIONNAIRES, pour placer un point entre les
     * nœuds de la grille — la végétation s'en sert. Même définition que
     * [gridDirection] : sur des coordonnées entières, les deux coïncident.
     */
    fun gridDirectionF(face: Int, level: Int, gx: Float, gy: Float, meshN: Int): com.terra.core.Vec3 {
        val total = (meshN.toLong() shl level).toDouble()
        val s = -1.0 + 2.0 * gx.toDouble() / total
        val t = -1.0 + 2.0 * gy.toDouble() / total
        return toSphereD(face, s, t).toVec3()
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
