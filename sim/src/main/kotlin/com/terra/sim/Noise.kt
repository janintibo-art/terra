package com.terra.sim

import com.terra.core.Rng
import com.terra.core.Seed
import kotlin.math.abs
import kotlin.math.floor

/**
 * Bruit de Perlin 3D déterministe.
 *
 * La table de permutation est mélangée par un [Rng] dérivé de la [Seed] : deux
 * instances construites avec la même graine produisent exactement le même champ,
 * sur n'importe quel appareil et à n'importe quelle version.
 *
 * On utilise du bruit **3D échantillonné sur la sphère** plutôt que du bruit 2D
 * projeté : c'est la seule façon d'éviter les coutures aux méridiens et la
 * compression aux pôles.
 */
class Noise(seed: Seed) {

    private val perm = IntArray(512)

    init {
        val rng = seed.rng()
        val p = IntArray(256) { it }
        rng.shuffle(p)
        for (i in 0 until 512) perm[i] = p[i and 255]
    }

    private fun fade(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

    private fun grad(hash: Int, x: Float, y: Float, z: Float): Float {
        val h = hash and 15
        val u = if (h < 8) x else y
        val v = if (h < 4) y else if (h == 12 || h == 14) x else z
        return (if (h and 1 == 0) u else -u) + (if (h and 2 == 0) v else -v)
    }

    /** Bruit de Perlin brut. Sortie approximativement dans [-0.7, +0.7]. */
    fun perlin(x: Float, y: Float, z: Float): Float {
        val fx = floor(x); val fy = floor(y); val fz = floor(z)
        val xi = fx.toInt() and 255
        val yi = fy.toInt() and 255
        val zi = fz.toInt() and 255
        val xf = x - fx; val yf = y - fy; val zf = z - fz
        val u = fade(xf); val v = fade(yf); val w = fade(zf)

        val a = perm[xi] + yi
        val aa = perm[a] + zi
        val ab = perm[a + 1] + zi
        val b = perm[xi + 1] + yi
        val ba = perm[b] + zi
        val bb = perm[b + 1] + zi

        val x1 = lp(u, grad(perm[aa], xf, yf, zf), grad(perm[ba], xf - 1f, yf, zf))
        val x2 = lp(u, grad(perm[ab], xf, yf - 1f, zf), grad(perm[bb], xf - 1f, yf - 1f, zf))
        val y1 = lp(v, x1, x2)
        val x3 = lp(u, grad(perm[aa + 1], xf, yf, zf - 1f), grad(perm[ba + 1], xf - 1f, yf, zf - 1f))
        val x4 = lp(u, grad(perm[ab + 1], xf, yf - 1f, zf - 1f), grad(perm[bb + 1], xf - 1f, yf - 1f, zf - 1f))
        val y2 = lp(v, x3, x4)
        return lp(w, y1, y2)
    }

    private fun lp(t: Float, a: Float, b: Float): Float = a + t * (b - a)

    /**
     * Somme d'octaves (fractal Brownian motion) : superpose des détails de plus
     * en plus fins et de moins en moins marqués. Résultat normalisé.
     */
    fun fbm(
        x: Float, y: Float, z: Float,
        octaves: Int,
        lacunarity: Float = 2.03f,
        gain: Float = 0.5f
    ): Float {
        var sum = 0f; var amp = 1f; var freq = 1f; var norm = 0f
        repeat(octaves) {
            sum += perlin(x * freq, y * freq, z * freq) * amp
            norm += amp
            amp *= gain
            freq *= lacunarity
        }
        return if (norm > 0f) sum / norm else 0f
    }

    /**
     * Bruit en crêtes : produit des arêtes vives plutôt que des bosses molles.
     * C'est ce qui donnera des chaînes de montagnes plutôt que des collines.
     */
    fun ridged(x: Float, y: Float, z: Float, octaves: Int): Float {
        var sum = 0f; var amp = 1f; var freq = 1f; var norm = 0f
        repeat(octaves) {
            val n = 1f - abs(perlin(x * freq, y * freq, z * freq)) * 2.2f
            sum += n * n * amp
            norm += amp
            amp *= 0.5f
            freq *= 2.1f
        }
        return if (norm > 0f) sum / norm else 0f
    }

    /**
     * Bruit à billes (billow) : bosses arrondies, utile pour les dunes et les
     * nuages.
     */
    fun billow(x: Float, y: Float, z: Float, octaves: Int): Float {
        var sum = 0f; var amp = 1f; var freq = 1f; var norm = 0f
        repeat(octaves) {
            sum += (abs(perlin(x * freq, y * freq, z * freq)) * 2f - 0.5f) * amp
            norm += amp
            amp *= 0.5f
            freq *= 2.05f
        }
        return if (norm > 0f) sum / norm else 0f
    }
}
