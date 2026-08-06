package com.terra.sim

import com.terra.core.Seed
import com.terra.core.Vec3
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Infrastructure des champs de grille — v0.9.0, prérequis du relief 1.6. */
class FieldSamplerTest {

    companion object {
        private val sphere: Icosphere by lazy { Icosphere(4) }
        private val sampler: FieldSampler by lazy { FieldSampler(sphere) }
    }

    private fun randomDir(rng: Random): Vec3 {
        while (true) {
            val x = rng.nextFloat() * 2f - 1f
            val y = rng.nextFloat() * 2f - 1f
            val z = rng.nextFloat() * 2f - 1f
            val l = x * x + y * y + z * z
            if (l in 1e-4f..1f) {
                val inv = 1f / kotlin.math.sqrt(l)
                return Vec3(x * inv, y * inv, z * inv)
            }
        }
    }

    @Test
    fun `un champ constant s interpole en lui-meme`() {
        // Partition de l'unité : si les poids ne sommaient pas à un, la
        // constante dériverait quelque part.
        val field = FloatArray(sphere.vertexCount) { 3.25f }
        val rng = Random(1)
        var hint = 0
        repeat(3000) {
            val p = randomDir(rng)
            hint = sampler.nearestVertex(p, hint)
            assertEquals(3.25f, sampler.sample(field, p, hint), 1e-4f)
        }
    }

    @Test
    fun `exact aux sommets de la grille`() {
        val rng = Random(2)
        val field = FloatArray(sphere.vertexCount) { rng.nextFloat() * 100f }
        for (i in 0 until sphere.vertexCount step 11) {
            assertEquals(field[i], sampler.sample(field, sphere.vertices[i], i), 1e-3f)
        }
    }

    @Test
    fun `un champ lineaire est reproduit a l erreur de corde pres`() {
        // field[i] = x du sommet : l'interpolation barycentrique d'une
        // fonction linéaire de l'espace est exacte sur chaque triangle plan ;
        // l'écart à p.x vient de la corde (le triangle plan sous la sphère),
        // borné par arête²/8 ≈ 4,5e-4 au niveau 4. Tester cela partout couvre
        // d'un coup la localisation, la continuité et la linéarité.
        val field = FloatArray(sphere.vertexCount) { sphere.vertices[it].x }
        val rng = Random(3)
        var hint = 0
        var worst = 0f
        repeat(5000) {
            val p = randomDir(rng)
            hint = sampler.nearestVertex(p, hint)
            val err = abs(sampler.sample(field, p, hint) - p.x)
            if (err > worst) worst = err
        }
        assertTrue(worst < 1.2e-3f, "erreur de reproduction linéaire : $worst")
    }

    @Test
    fun `le hint accelere mais ne change jamais le resultat`() {
        val rng = Random(4)
        val field = FloatArray(sphere.vertexCount) { rng.nextFloat() * 10f }
        repeat(500) {
            val p = randomDir(rng)
            val a = sampler.sample(field, p, 0)
            val b = sampler.sample(field, p, rng.nextInt(sphere.vertexCount))
            assertEquals(a, b, 0f, "le hint a changé la valeur")
        }
    }
}

class BoundaryDistanceFieldTest {

    companion object {
        private val sphere: Icosphere by lazy { Icosphere(4) }

        private fun fieldFor(name: String): Triple<PlateSet, BoundarySet, BoundaryDistanceField> {
            val plates = PlateSet.generate(Seed.fromText(name), sphere, 0.66f)
            val boundaries = BoundarySet.classify(sphere, plates)
            return Triple(plates, boundaries, BoundaryDistanceField.generate(sphere, plates, boundaries))
        }
    }

    @Test
    fun `distance nulle sur les frontieres du type, positive ailleurs`() {
        val (_, boundaries, field) = fieldFor("Kaleth")
        val convSources = HashSet<Int>()
        for (i in 0 until boundaries.edgeCount) {
            if (boundaries.edgeType[i].toInt() == BoundaryType.CONVERGENT.ordinal) {
                convSources.add(boundaries.edgeA[i]); convSources.add(boundaries.edgeB[i])
            }
        }
        assertTrue(convSources.isNotEmpty())
        for (v in 0 until sphere.vertexCount) {
            if (v in convSources) {
                assertEquals(0f, field.distConvergent[v], 0f, "source $v")
            } else {
                assertTrue(field.distConvergent[v] > 0f, "sommet $v à distance nulle sans être source")
            }
        }
    }

    @Test
    fun `la distance respecte l inegalite triangulaire locale`() {
        // Propriété définitoire d'une distance de graphe : entre voisins, les
        // valeurs ne peuvent différer de plus que la longueur de l'arête.
        val (_, _, field) = fieldFor("Ormun")
        val adjacency = sphere.buildAdjacency()
        for (v in 0 until sphere.vertexCount step 3) {
            val pv = sphere.vertices[v]
            for (m in adjacency[v]) {
                val pm = sphere.vertices[m]
                val edge = kotlin.math.acos(
                    (pv.x * pm.x + pv.y * pm.y + pv.z * pm.z).coerceIn(-1f, 1f)
                )
                assertTrue(
                    abs(field.distConvergent[v] - field.distConvergent[m]) <= edge + 1e-5f,
                    "inégalité violée entre $v et $m"
                )
            }
        }
    }

    @Test
    fun `tous les sommets sont atteints avec un caractere valide`() {
        for (name in listOf("Kaleth", "Ormun", "Vessiane")) {
            val (_, _, field) = fieldFor(name)
            for (v in 0 until sphere.vertexCount) {
                assertTrue(field.distConvergent[v] < Float.MAX_VALUE, "$name : $v jamais atteint")
                assertTrue(field.distDivergent[v] < Float.MAX_VALUE)
                if (field.distConvergent[v] < 1e9f) {
                    assertTrue(field.contextConvergent[v] in 0..2)
                    assertTrue(field.intensityConvergent[v] in 0f..(2f * PlateSet.MAX_OMEGA * 1.0001f))
                }
            }
        }
    }

    @Test
    fun `le champ est deterministe malgre les ex aequo`() {
        val (_, _, a) = fieldFor("Vessiane")
        val (_, _, b) = fieldFor("Vessiane")
        assertTrue(a.distConvergent.contentEquals(b.distConvergent))
        assertTrue(a.intensityConvergent.contentEquals(b.intensityConvergent))
        assertTrue(a.contextConvergent.contentEquals(b.contextConvergent))
        assertTrue(a.distTransform.contentEquals(b.distTransform))
    }
}
