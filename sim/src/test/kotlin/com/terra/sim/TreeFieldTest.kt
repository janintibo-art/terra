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
    fun lesArbresSontPosesSurLaSurfaceDessinee() {
        // Le correctif v0.51.2 : chaque arbre doit se poser sur la tuile
        // que le SÉLECTEUR dessine à cette position d'œil, pas sur le
        // terrain exact — l'écart entre les deux atteint plusieurs mètres
        // aux niveaux grossiers et faisait flotter les arbres.
        val eye = eyeAt(30.0)
        val drawn = ArrayList<TileId>()
        TileSelector().select(
            eye.x / radius, eye.y / radius, eye.z / radius, drawn
        )
        val byPack = HashSet<Long>()
        for (tile in drawn) byPack.add(tile.packed())

        var checkedNodes = 0
        for (inst in field.instances) {
            val r = sqrt(inst.posXM * inst.posXM + inst.posYM * inst.posYM +
                inst.posZM * inst.posZM)
            val dir = com.terra.core.Vec3(
                (inst.posXM / r).toFloat(), (inst.posYM / r).toFloat(),
                (inst.posZM / r).toFloat()
            )
            // La surface dessinée s'écarte du terrain exact DANS LES DEUX
            // SENS (corde au-dessus d'un creux, au-dessous d'une bosse) :
            // la borne est symétrique — écart de tuile grossière (~3 m au
            // niveau 15) plus enfouissement maximal, jamais davantage.
            // L'ancien code, posé sur l'exact, violait cette borne de
            // plusieurs mètres côté « au-dessus » dès 200 m de distance.
            val instAlt = r - radius
            val exact = profile.renderedAltitudeAt(dir).toDouble()
            assertTrue(
                kotlin.math.abs(instAlt - exact) < 4.5,
                "arbre à ${instAlt - exact} m du terrain exact"
            )
            checkedNodes++
        }
        assertTrue(checkedNodes > 5)
        assertTrue(drawn.isNotEmpty() && byPack.size == drawn.size)
    }

    @Test
    fun lAllocationEstProgressivePasUneFalaise() {
        // v0.52.1 : l'allocation gloutonne donnait 38 arbres pleins puis
        // plus rien — la « falaise » vue sur photo. La nouvelle part du
        // niveau dicté par la taille apparente et dégrade depuis la queue :
        // en forêt dense, il DOIT exister des arbres moyens, et les
        // niveaux doivent se dégrader de façon monotone avec le rang.
        val counts = HashMap<TreeDetail, Int>()
        for (inst in field.instances) {
            counts[inst.variant.detail] = (counts[inst.variant.detail] ?: 0) + 1
        }
        assertTrue((counts[TreeDetail.FULL] ?: 0) >= 5, "pleins : $counts")
        assertTrue((counts[TreeDetail.MEDIUM] ?: 0) >= 5, "moyens : $counts")
        for (i in 1 until field.instances.size) {
            assertTrue(
                field.instances[i].variant.detail.ordinal >=
                    field.instances[i - 1].variant.detail.ordinal,
                "niveau remonté au rang $i"
            )
        }
    }

    @Test
    fun lesCasesOccupeesSontPublieesUneParArbre() {
        // Le fondement du lot 3.5-b : chaque arbre réel occupe exactement
        // une case, et l'ensemble sert à taire les losanges en dessous.
        assertEquals(field.instances.size, field.occupiedCells.size)
        for (key in field.occupiedCells) {
            assertTrue(key != 0L)
        }
    }

    @Test
    fun lExclusionRepondPresentSurSesClefs() {
        PlantExclusion.replace(setOf(PlantExclusion.key(2, 1234L, 5678L)))
        try {
            assertTrue(PlantExclusion.contains(2, 1234L, 5678L))
            assertTrue(!PlantExclusion.contains(2, 1234L, 5679L))
            assertTrue(!PlantExclusion.contains(3, 1234L, 5678L))
            assertEquals(1, PlantExclusion.size)
        } finally {
            // L'objet est global : un test qui ne nettoie pas polluerait
            // les autres.
            PlantExclusion.clear()
        }
        assertTrue(!PlantExclusion.contains(2, 1234L, 5678L))
    }

    @Test
    fun lEnumerationCouvreLeDisqueMemeAUneAreteDuCube() {
        // Lot 3.5-c : l'ancienne énumération s'arrêtait à la face du point
        // sous l'œil — à cheval sur une arête, la moitié du disque restait
        // vide. L'échantillonnage de directions doit visiter autant de
        // cases à une arête qu'en pleine face, à la déformation près.
        val edgeDir = Vec3d(1.0, 0.03, 1.0).normalized()   // arête +X / +Z
        val f = TreeField(profile, sampler, radius)
            .build(edgeDir * (radius + 30.0), pxPerRadian, 400.0)
        val cellArc = (Math.PI / 2.0 * radius) / (7L shl 15).toDouble()
        val nominal = Math.PI * 400.0 * 400.0 / (cellArc * cellArc)
        assertTrue(
            f.cellsVisited > nominal * 0.8,
            "seulement ${f.cellsVisited} cases visitées à l'arête " +
                "pour ~${nominal.toInt()} attendues — un pan du disque manque"
        )
        // Les cases d'arête sont plus petites (déformation −25 %) : le
        // compte peut dépasser le nominal, jamais le double.
        assertTrue(f.cellsVisited < nominal * 2.0, "${f.cellsVisited} cases")
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
