package com.terra.sim

import com.terra.core.Vec3d
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lot 3.5 — champ d'arbres instancié.
 *
 * Le monde est généré une fois pour la classe (même approche que les tests
 * d'occlusion) et le point d'essai est CHERCHÉ : la première cellule de
 * forêt tempérée ou tropicale — un test qui tomberait sur l'océan ne
 * testerait rien.
 */
class TreeFieldTest {

    private companion object {
        val world = WorldGenerator.fromName("Terra").generate { _, _ -> }
        val profile = world.terrain
        val sampler = CoarseSampler(world)
        val radius = world.params.radiusM.toDouble()

        /** Direction d'une cellule bien végétalisée. */
        val forestDir: Vec3d = run {
            var best = -1
            for (cell in 0 until world.vertexCount) {
                val biome = world.biome(cell)
                if (VegetationRules.densityFor(biome) >= 0.9f &&
                    world.altitudeM[cell] > 50f
                ) {
                    best = cell
                    break
                }
            }
            check(best >= 0) { "Aucune cellule de forêt dense sur Terra" }
            val p = world.position(best)
            Vec3d(p.x.toDouble(), p.y.toDouble(), p.z.toDouble()).normalized()
        }

        val pxPerRadian = 1406.8f

        fun eyeAt(altAboveGroundM: Double): Vec3d {
            val ground = profile.altitudeAt(forestDir.toVec3()).toDouble()
            return forestDir * (radius + ground.coerceAtLeast(0.0) + altAboveGroundM)
        }

        val field = TreeField(profile, sampler, radius)
            .build(eyeAt(30.0), pxPerRadian, 400.0)
    }

    @Test
    fun leChampNestPasVide() {
        assertTrue(field.cellsVisited > 100, "cases visitées : ${field.cellsVisited}")
        assertTrue(
            field.instances.size >= 10,
            "seulement ${field.instances.size} arbres en forêt dense " +
                "(${field.cellsPlanted} candidats)"
        )
    }

    @Test
    fun leBudgetEstTenu() {
        assertTrue(
            field.trianglesSpent <= TreeField(profile, sampler, radius).budgetTriangles,
            "dépense ${field.trianglesSpent}"
        )
        assertTrue(field.trianglesSpent > 0)
    }

    @Test
    fun lesPlusGrosSontServisEnPremier() {
        // L'ordre des instances suit la taille apparente décroissante :
        // c'est le contrat de l'allocation, hérité du 3.3-b.
        for (i in 1 until field.instances.size) {
            assertTrue(
                field.instances[i].apparentPx <= field.instances[i - 1].apparentPx + 1e-3f,
                "instance $i mieux classée que la précédente"
            )
        }
    }

    @Test
    fun determinismeDuChamp() {
        val again = TreeField(profile, sampler, radius)
            .build(eyeAt(30.0), pxPerRadian, 400.0)
        assertEquals(field.instances.size, again.instances.size)
        assertEquals(field.trianglesSpent, again.trianglesSpent)
        for (i in field.instances.indices) {
            assertEquals(field.instances[i].posXM, again.instances[i].posXM, "x de $i")
            assertEquals(field.instances[i].variant, again.instances[i].variant, "variante de $i")
        }
    }

    @Test
    fun toutesLesInstancesSontDansLaPortee() {
        val eye = eyeAt(30.0)
        for (inst in field.instances) {
            val dx = inst.posXM - eye.x
            val dy = inst.posYM - eye.y
            val dz = inst.posZM - eye.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            assertTrue(dist <= 400.0 + 1e-6, "arbre à $dist m")
            // Et posées sur la planète, pas au centre ni dans l'espace.
            val r = sqrt(inst.posXM * inst.posXM + inst.posYM * inst.posYM +
                inst.posZM * inst.posZM)
            assertTrue(r > radius - 100.0 && r < radius + 9_000.0, "rayon $r")
        }
    }

    @Test
    fun lesVariantesSontBorneesEtLeursMaillagesValides() {
        val builder = TreeField(profile, sampler, radius)
        val seen = HashSet<TreeField.VariantKey>()
        for (inst in field.instances) {
            assertTrue(inst.variant.index in 0 until builder.variantCount(inst.variant.detail))
            seen += inst.variant
        }
        // Chaque variante utilisée produit un maillage non vide dont le
        // compte de triangles correspond au niveau demandé.
        for (key in seen) {
            val mesh = builder.buildVariantMesh(key)
            assertTrue(mesh.isNotEmpty(), "$key vide")
            assertEquals(0, (mesh.size / TreeMesh.FLOATS_PER_VERTEX) % 3)
        }
        assertTrue(seen.size >= 2, "une seule variante utilisée sur tout le champ")
    }

    @Test
    fun lesReperesPortentLEchelle() {
        // Les colonnes du repère incluent l'échelle ±15 % : leur norme doit
        // être dans [0,85 ; 1,15], jamais 1 exactement pour tous.
        var minLen = Float.MAX_VALUE
        var maxLen = 0f
        for (inst in field.instances) {
            val f = inst.frame
            val len = sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2])
            minLen = minOf(minLen, len)
            maxLen = maxOf(maxLen, len)
            assertTrue(len in 0.849f..1.151f, "échelle $len hors bornes")
        }
        assertTrue(maxLen - minLen > 0.02f, "aucune variation d'échelle")
    }

    @Test
    fun surLoceanLeChampEstVide() {
        var oceanCell = -1
        for (cell in 0 until world.vertexCount) {
            if (world.altitudeM[cell] < -2000f) {
                oceanCell = cell
                break
            }
        }
        check(oceanCell >= 0)
        val p = world.position(oceanCell)
        val dir = Vec3d(p.x.toDouble(), p.y.toDouble(), p.z.toDouble()).normalized()
        val ocean = TreeField(profile, sampler, radius)
            .build(dir * (radius + 30.0), pxPerRadian, 400.0)
        assertEquals(0, ocean.instances.size)
    }
}
