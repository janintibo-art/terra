package com.terra.sim

import com.terra.core.Seed
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundariesTest {

    companion object {
        private val sphere: Icosphere by lazy { Icosphere(4) }

        private fun setFor(name: String): Pair<PlateSet, BoundarySet> {
            val plates = PlateSet.generate(Seed.fromText(name), sphere, 0.66f)
            return plates to BoundarySet.classify(sphere, plates)
        }
    }

    @Test
    fun `chaque arete inter-plaques est classee exactement une fois`() {
        val (plates, b) = setFor("Kaleth")
        val adjacency = sphere.buildAdjacency()

        // Décompte indépendant des arêtes de frontière attendues.
        var expected = 0
        for (a in 0 until sphere.vertexCount) {
            for (n in adjacency[a]) {
                if (n > a && plates.plateId[a] != plates.plateId[n]) expected++
            }
        }
        assertEquals(expected, b.edgeCount)

        // Aucun doublon, orientation canonique a < b, plaques bien distinctes.
        val seen = HashSet<Long>()
        for (i in 0 until b.edgeCount) {
            val a = b.edgeA[i]; val bb = b.edgeB[i]
            assertTrue(a < bb, "orientation non canonique : $a, $bb")
            assertTrue(plates.plateId[a] != plates.plateId[bb])
            assertTrue(seen.add(a.toLong() shl 32 or bb.toLong()), "arête en double")
        }
    }

    @Test
    fun `les sommets de bord et eux seuls portent un type`() {
        val (plates, b) = setFor("Ormun")
        val adjacency = sphere.buildAdjacency()
        for (v in 0 until sphere.vertexCount) {
            val isBorder = adjacency[v].any { plates.plateId[it] != plates.plateId[v] }
            if (isBorder) {
                assertTrue(b.vertexType[v] >= 0, "sommet de bord $v sans type")
            } else {
                assertEquals((-1).toByte(), b.vertexType[v], "sommet intérieur $v typé")
            }
        }
    }

    @Test
    fun `la classification est deterministe et les trois types presents`() {
        val (_, b1) = setFor("Vessiane")
        val (_, b2) = setFor("Vessiane")
        assertTrue(b1.edgeType.contentEquals(b2.edgeType))
        assertTrue(b1.relSpeed.contentEquals(b2.relSpeed))

        for (name in listOf("Kaleth", "Ormun", "Vessiane")) {
            val counts = setFor(name).second.countByType()
            for ((t, c) in counts.withIndex()) {
                assertTrue(c > 0, "$name : type $t absent")
            }
        }
    }

    @Test
    fun `les proportions restent dans la plage mesuree`() {
        // La simulation de calibrage donne ~33 % par type sur des rotations
        // uniformes ; sur quelques centaines d'arêtes par monde, l'écart-type
        // par graine reste sous ~8 points. Bornes à trois écarts-types, pour
        // que le test attrape un vrai déséquilibre — un critère cassé — sans
        // rougir sur la variance normale d'une graine.
        for (name in listOf("Kaleth", "Ormun", "Vessiane")) {
            val b = setFor(name).second
            val counts = b.countByType()
            val total = b.edgeCount.toFloat()
            for ((t, c) in counts.withIndex()) {
                val share = c / total
                assertTrue(share in 0.09f..0.60f, "$name : type $t à ${share * 100} %")
            }
        }
    }

    @Test
    fun `la decomposition normale-tangentielle est orthogonale et bornee`() {
        val (plates, b) = setFor("Kaleth")
        var checked = 0
        for (i in 0 until b.edgeCount step 7) {
            val a = b.edgeA[i]; val bb = b.edgeB[i]
            val va = sphere.vertices[a]; val vb = sphere.vertices[bb]
            val ml = sqrt(
                (va.x + vb.x) * (va.x + vb.x) + (va.y + vb.y) * (va.y + vb.y) + (va.z + vb.z) * (va.z + vb.z)
            )
            val m = com.terra.core.Vec3((va.x + vb.x) / ml, (va.y + vb.y) / ml, (va.z + vb.z) / ml)
            val vA = plates.plateOf(a).velocityAt(m)
            val vB = plates.plateOf(bb).velocityAt(m)
            val rx = vB.x - vA.x; val ry = vB.y - vA.y; val rz = vB.z - vA.z
            val speed = sqrt(rx * rx + ry * ry + rz * rz)

            assertEquals(speed, b.relSpeed[i], 1e-6f, "vitesse relative, arête $i")
            // Jamais plus vite que la somme des deux rotations maximales.
            assertTrue(speed <= 2f * PlateSet.MAX_OMEGA * 1.0001f)
            // vr est tangente : différence de deux tangentes au même point.
            assertTrue(abs(rx * m.x + ry * m.y + rz * m.z) < 1e-6f)
            checked++
        }
        assertTrue(checked > 30)
    }
}
