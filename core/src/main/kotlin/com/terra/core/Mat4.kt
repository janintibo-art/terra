package com.terra.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Matrices 4×4 en colonnes — lot 10.1.
 *
 * Android fournit `android.opengl.Matrix` ; le module `:desktop` n'a rien
 * d'équivalent. Plutôt qu'ajouter une dépendance (JOML) pour trois
 * fonctions, elles vivent ici : ce sont des maths pures, donc testables en
 * intégration continue, conformément à la règle du projet.
 *
 * ## Convention
 *
 * Ordre COLONNES, celui d'OpenGL : l'élément (ligne i, colonne j) est à
 * l'indice `j * 4 + i`. C'est la disposition que `glUniformMatrix4fv`
 * attend avec `transpose = false`, et s'y tromper donne une image
 * silencieusement fausse plutôt qu'une erreur.
 */
object Mat4 {

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    /**
     * Projection en perspective.
     *
     * @param fovYRad champ vertical
     * @param aspect largeur / hauteur
     * @param near plan proche, strictement positif
     * @param far plan lointain
     */
    fun perspective(fovYRad: Float, aspect: Float, near: Float, far: Float): FloatArray {
        require(near > 0f && far > near) { "Plans invalides : near=$near far=$far" }
        require(aspect > 0f) { "Rapport d'image non positif : $aspect" }
        val f = 1f / tan(fovYRad / 2f)
        val out = FloatArray(16)
        out[0] = f / aspect
        out[5] = f
        out[10] = (far + near) / (near - far)
        out[11] = -1f
        out[14] = 2f * far * near / (near - far)
        return out
    }

    /**
     * Vue « regarde vers », en coordonnées RELATIVES À L'ŒIL : l'œil est
     * à l'origine, seule l'orientation compte. C'est l'invariant n°5 du
     * projet appliqué au PC — la soustraction œil↔monde se fait en double
     * côté CPU, la matrice ne voit jamais de grandes coordonnées.
     */
    fun lookDirection(
        dirX: Float, dirY: Float, dirZ: Float,
        upX: Float, upY: Float, upZ: Float
    ): FloatArray {
        val fl = sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ).coerceAtLeast(1e-9f)
        val fx = dirX / fl; val fy = dirY / fl; val fz = dirZ / fl

        // Droite = avant × haut, puis haut recalculé : le « haut » fourni
        // n'a pas besoin d'être orthogonal à la visée.
        var sx = fy * upZ - fz * upY
        var sy = fz * upX - fx * upZ
        var sz = fx * upY - fy * upX
        val sl = sqrt(sx * sx + sy * sy + sz * sz)
        if (sl < 1e-9f) {
            // Visée colinéaire au haut : on prend un axe de secours plutôt
            // que de produire des NaN qui feraient disparaître l'image.
            sx = 1f; sy = 0f; sz = 0f
        } else {
            sx /= sl; sy /= sl; sz /= sl
        }
        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx

        return floatArrayOf(
            sx, ux, -fx, 0f,
            sy, uy, -fy, 0f,
            sz, uz, -fz, 0f,
            0f, 0f, 0f, 1f
        )
    }

    /** Produit `a × b`, colonnes. */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == 16 && b.size == 16) { "Matrices 4×4 attendues" }
        val out = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
        return out
    }

    /** Rotation autour d'un axe unitaire, en radians. */
    fun rotation(axisX: Float, axisY: Float, axisZ: Float, angleRad: Float): FloatArray {
        val l = sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ).coerceAtLeast(1e-9f)
        val x = axisX / l; val y = axisY / l; val z = axisZ / l
        val c = cos(angleRad); val s = sin(angleRad); val t = 1f - c
        return floatArrayOf(
            t * x * x + c, t * x * y + s * z, t * x * z - s * y, 0f,
            t * x * y - s * z, t * y * y + c, t * y * z + s * x, 0f,
            t * x * z + s * y, t * y * z - s * x, t * z * z + c, 0f,
            0f, 0f, 0f, 1f
        )
    }

    /** Applique la matrice à un point (w = 1), sans division perspective. */
    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray =
        floatArrayOf(
            m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14],
            m[3] * x + m[7] * y + m[11] * z + m[15]
        )
}
