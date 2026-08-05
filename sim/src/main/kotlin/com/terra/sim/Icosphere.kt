package com.terra.sim

import com.terra.core.Vec3
import kotlin.math.sqrt

/**
 * Icosphère géodésique — le support de toute la simulation.
 *
 * ## Pourquoi une icosphère
 *
 * Une sphère UV (latitude/longitude) concentre ses cellules aux pôles : les
 * quadrilatères y deviennent des slivers dégénérés, les biomes s'y étirent, et
 * toute simulation par cellule y serait faussée. Un cube-sphère est meilleur
 * mais garde huit coins singuliers.
 *
 * L'icosphère répartit ses sommets de façon quasi uniforme : chaque cellule
 * couvre à peu près la même surface, ce qui est exactement ce qu'il faut pour
 * simuler un climat, des populations ou des ressources sans biais géographique.
 *
 * ## Coût
 *
 * | Niveau | Sommets | Faces   |
 * |--------|---------|---------|
 * | 3      | 642     | 1 280   |
 * | 4      | 2 562   | 5 120   |
 * | 5      | 10 242  | 20 480  |
 * | 6      | 40 962  | 81 920  |
 *
 * Le niveau 5 est le compromis retenu pour la Phase 1 : assez fin pour des
 * côtes crédibles, assez léger pour être régénéré en moins d'une seconde.
 * Le découpage adaptatif viendra en Phase 2 (lot 2.2).
 */
class Icosphere(val level: Int) {

    /** Sommets sur la sphère unité. */
    val vertices: Array<Vec3>

    /** Triangles, trois indices consécutifs par face. */
    val faces: IntArray

    val vertexCount: Int get() = vertices.size
    val faceCount: Int get() = faces.size / 3

    init {
        require(level in 0..7) { "niveau de subdivision hors limites : $level" }

        val t = (1f + sqrt(5f)) / 2f
        val verts = ArrayList<Vec3>(expectedVertexCount(level))

        // Les douze sommets de l'icosaèdre régulier.
        for (v in listOf(
            Vec3(-1f, t, 0f), Vec3(1f, t, 0f), Vec3(-1f, -t, 0f), Vec3(1f, -t, 0f),
            Vec3(0f, -1f, t), Vec3(0f, 1f, t), Vec3(0f, -1f, -t), Vec3(0f, 1f, -t),
            Vec3(t, 0f, -1f), Vec3(t, 0f, 1f), Vec3(-t, 0f, -1f), Vec3(-t, 0f, 1f)
        )) verts.add(v.normalized())

        var tris = mutableListOf(
            intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7),
            intArrayOf(0, 7, 10), intArrayOf(0, 10, 11), intArrayOf(1, 5, 9),
            intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6),
            intArrayOf(7, 1, 8), intArrayOf(3, 9, 4), intArrayOf(3, 4, 2),
            intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
            intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10),
            intArrayOf(8, 6, 7), intArrayOf(9, 8, 1)
        )

        repeat(level) {
            val cache = HashMap<Long, Int>(tris.size * 2)

            fun midpoint(a: Int, b: Int): Int {
                val key = if (a < b) (a.toLong() shl 32) or b.toLong()
                          else (b.toLong() shl 32) or a.toLong()
                val existing = cache[key]
                if (existing != null) return existing
                val m = ((verts[a] + verts[b]) * 0.5f).normalized()
                verts.add(m)
                val idx = verts.size - 1
                cache[key] = idx
                return idx
            }

            val next = ArrayList<IntArray>(tris.size * 4)
            for (f in tris) {
                val a = midpoint(f[0], f[1])
                val b = midpoint(f[1], f[2])
                val c = midpoint(f[2], f[0])
                next.add(intArrayOf(f[0], a, c))
                next.add(intArrayOf(f[1], b, a))
                next.add(intArrayOf(f[2], c, b))
                next.add(intArrayOf(a, b, c))
            }
            tris = next
        }

        vertices = verts.toTypedArray()
        faces = IntArray(tris.size * 3)
        var i = 0
        for (f in tris) {
            faces[i++] = f[0]; faces[i++] = f[1]; faces[i++] = f[2]
        }
    }

    /**
     * Liste d'adjacence : pour chaque sommet, ses voisins directs.
     *
     * Construite à la demande car elle n'est pas nécessaire au rendu, mais elle
     * sera indispensable dès la Phase 1 (érosion, écoulement des eaux,
     * propagation climatique) et la Phase 4 (déplacement des créatures).
     */
    fun buildAdjacency(): Array<IntArray> {
        val sets = Array(vertices.size) { HashSet<Int>(6) }
        var i = 0
        while (i < faces.size) {
            val a = faces[i]; val b = faces[i + 1]; val c = faces[i + 2]
            sets[a].add(b); sets[a].add(c)
            sets[b].add(a); sets[b].add(c)
            sets[c].add(a); sets[c].add(b)
            i += 3
        }
        return Array(vertices.size) { sets[it].toIntArray() }
    }

    companion object {
        fun expectedVertexCount(level: Int): Int {
            var v = 12
            var e = 30
            repeat(level) {
                v += e
                e *= 4
            }
            return v
        }

        fun expectedFaceCount(level: Int): Int {
            var f = 20
            repeat(level) { f *= 4 }
            return f
        }
    }
}
