package com.terra.sim

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Lot 3.3-a — maillage des arbres.
 *
 * Les comptes viennent de validation/maillage_arbres.py. Le reste teste des
 * propriétés géométriques exactes : un sommet d'anneau est à la distance
 * `rayon` de l'axe, les triangles regardent dehors, les extrémités sont
 * fermées.
 */
class TreeMeshTest {

    private fun skeleton(species: TreeSpecies, seed: Long = 1L) =
        TreeGenerator.generate(species.params(), seed)

    @Test
    fun comptesDeSommetsConformesALInstruction() {
        // CONIFERE : 1 111 segments dont 1 000 terminaux, 8 côtés
        // → 111×6×8 + 1000×3×8 = 5 328 + 24 000 = 29 328 sommets,
        // soit 9 776 triangles (validation §1).
        val tree = skeleton(TreeSpecies.CONIFERE)
        val mesh = TreeMesh.build(tree)
        assertEquals(29_328, mesh.size / TreeMesh.FLOATS_PER_VERTEX)
        assertEquals(9_776, mesh.size / TreeMesh.FLOATS_PER_VERTEX / 3)
        // vertexCount() doit prédire exactement ce que build() produit,
        // sinon dimensionner un tampon serait un pari.
        assertEquals(TreeMesh.vertexCount(tree), mesh.size / TreeMesh.FLOATS_PER_VERTEX)
    }

    @Test
    fun comptesPourToutesLesFamilles() {
        val expectedTriangles = mapOf(
            TreeSpecies.CONIFERE to 9_776,
            TreeSpecies.FEUILLU to 3_880,
            TreeSpecies.PALMIER to 80,
            TreeSpecies.CACTUS to 80,
            TreeSpecies.ARBUSTE to 1_288,
            TreeSpecies.HERBACEE to 48,
            TreeSpecies.MOUSSE to 40
        )
        for ((species, tris) in expectedTriangles) {
            val mesh = TreeMesh.build(skeleton(species))
            assertEquals(
                tris, mesh.size / TreeMesh.FLOATS_PER_VERTEX / 3, species.label
            )
        }
    }

    @Test
    fun troisSommetsParTriangleEtValeursFinies() {
        val mesh = TreeMesh.build(skeleton(TreeSpecies.FEUILLU))
        assertEquals(0, (mesh.size / TreeMesh.FLOATS_PER_VERTEX) % 3)
        for (f in mesh) assertTrue(f.isFinite())
    }

    @Test
    fun lesSommetsRespectentLeRayonDuSquelette() {
        // Propriété centrale : chaque sommet d'anneau est exactement à la
        // distance « rayon » de l'axe du segment. Si le maillage se mettait
        // à ignorer les rayons — le défaut même que ce lot corrige — ce test
        // tomberait.
        val tree = skeleton(TreeSpecies.CONIFERE, 4L)
        val mesh = TreeMesh.build(tree)
        val stride = TreeMesh.FLOATS_PER_VERTEX

        // Rayons présents dans le squelette, avec une tolérance : chaque
        // sommet doit tomber sur l'un d'eux, à sa distance de l'axe.
        var checked = 0
        var v = 0
        while (v < mesh.size / stride) {
            val x = mesh[v * stride]
            val y = mesh[v * stride + 1]
            val z = mesh[v * stride + 2]
            // On cherche le segment dont un des deux anneaux explique ce
            // sommet ; il suffit qu'il en existe UN.
            var explained = false
            for (s in tree.segments) {
                for (atTip in booleanArrayOf(false, true)) {
                    val cx = if (atTip) s.tipX else s.baseX
                    val cy = if (atTip) s.tipY else s.baseY
                    val cz = if (atTip) s.tipZ else s.baseZ
                    val r = if (atTip) s.radiusTipM else s.radiusBaseM
                    val d = sqrt(
                        (x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz)
                    )
                    if (abs(d - r) < 1e-3f || d < 1e-3f) {
                        explained = true
                        break
                    }
                }
                if (explained) break
            }
            assertTrue(explained, "sommet $v sans anneau correspondant")
            checked++
            v += 977   // échantillonnage : le test exhaustif serait quadratique
        }
        assertTrue(checked > 20, "échantillon trop maigre : $checked")
    }

    @Test
    fun lesExtremitesSontFermees() {
        // Un segment terminal doit produire des triangles dont un sommet
        // est EXACTEMENT sa pointe : c'est l'apex du cône. Sans lui, le
        // maillage serait ouvert (trou de 5,9 cm sur un conifère).
        val tree = skeleton(TreeSpecies.FEUILLU, 6L)
        val mesh = TreeMesh.build(tree)
        val stride = TreeMesh.FLOATS_PER_VERTEX
        val hasChild = BooleanArray(tree.segments.size)
        for (s in tree.segments) if (s.parent >= 0) hasChild[s.parent] = true

        // Prenons un terminal au hasard et cherchons son apex.
        val terminalIndex = tree.segments.indices.first { !hasChild[it] }
        val t = tree.segments[terminalIndex]
        var found = 0
        for (v in 0 until mesh.size / stride) {
            if (abs(mesh[v * stride] - t.tipX) < 1e-6f &&
                abs(mesh[v * stride + 1] - t.tipY) < 1e-6f &&
                abs(mesh[v * stride + 2] - t.tipZ) < 1e-6f
            ) found++
        }
        // Un apex par côté du cône.
        assertEquals(TreeMesh.DEFAULT_SIDES, found)
    }

    @Test
    fun orientationDesTrianglesVersLExterieur() {
        // L'élagage des faces arrière est actif : un triangle mal orienté
        // rendrait l'arbre troué, et le défaut ne se verrait que sur
        // appareil. On compare la normale GÉOMÉTRIQUE (produit vectoriel
        // des arêtes) à la normale stockée : elles doivent pointer du même
        // côté sur chaque triangle.
        for (species in listOf(TreeSpecies.CONIFERE, TreeSpecies.PALMIER)) {
            val mesh = TreeMesh.build(skeleton(species, 3L))
            val stride = TreeMesh.FLOATS_PER_VERTEX
            val triangles = mesh.size / stride / 3
            var checked = 0
            for (t in 0 until triangles) {
                val o0 = (3 * t) * stride
                val o1 = (3 * t + 1) * stride
                val o2 = (3 * t + 2) * stride
                val ax = mesh[o1] - mesh[o0]
                val ay = mesh[o1 + 1] - mesh[o0 + 1]
                val az = mesh[o1 + 2] - mesh[o0 + 2]
                val bx = mesh[o2] - mesh[o0]
                val by = mesh[o2 + 1] - mesh[o0 + 1]
                val bz = mesh[o2 + 2] - mesh[o0 + 2]
                val gx = ay * bz - az * by
                val gy = az * bx - ax * bz
                val gz = ax * by - ay * bx
                val len = sqrt(gx * gx + gy * gy + gz * gz)
                if (len < 1e-12f) continue          // triangle dégénéré éventuel
                // Moyenne des normales stockées du triangle.
                val nx = (mesh[o0 + 3] + mesh[o1 + 3] + mesh[o2 + 3]) / 3f
                val ny = (mesh[o0 + 4] + mesh[o1 + 4] + mesh[o2 + 4]) / 3f
                val nz = (mesh[o0 + 5] + mesh[o1 + 5] + mesh[o2 + 5]) / 3f
                val dot = (gx * nx + gy * ny + gz * nz) / len
                assertTrue(dot > 0f, "${species.label} triangle $t retourné (dot = $dot)")
                checked++
            }
            assertTrue(checked > 50, "trop peu de triangles vérifiés : $checked")
        }
    }

    @Test
    fun normalesUnitaires() {
        val tree = skeleton(TreeSpecies.CACTUS, 2L)
        val mesh = TreeMesh.build(tree)
        val stride = TreeMesh.FLOATS_PER_VERTEX
        for (v in 0 until mesh.size / stride) {
            val nx = mesh[v * stride + 3]
            val ny = mesh[v * stride + 4]
            val nz = mesh[v * stride + 5]
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            assertTrue(abs(len - 1f) < 1e-3f, "normale non unitaire : $len")
        }
    }

    @Test
    fun couleursDansLesBornes() {
        for (species in TreeSpecies.values()) {
            val mesh = TreeMesh.build(skeleton(species))
            val stride = TreeMesh.FLOATS_PER_VERTEX
            for (v in 0 until mesh.size / stride) {
                for (c in 6..8) {
                    val value = mesh[v * stride + c]
                    assertTrue(value in 0f..1f, "${species.label} couleur $value")
                }
            }
        }
    }

    @Test
    fun determinisme() {
        val tree = skeleton(TreeSpecies.ARBUSTE, 9L)
        assertTrue(TreeMesh.build(tree).contentEquals(TreeMesh.build(tree)))
    }

    @Test
    fun nombreDeCotesBorne() {
        val tree = skeleton(TreeSpecies.MOUSSE)
        assertFailsWith<IllegalArgumentException> { TreeMesh.build(tree, 2) }
        assertFailsWith<IllegalArgumentException> { TreeMesh.build(tree, 33) }
        // Et le compte suit le nombre de côtés : 4 segments dont 3
        // terminaux → 1×6×5 + 3×3×5 = 75 sommets à 5 côtés.
        assertEquals(75, TreeMesh.build(tree, 5).size / TreeMesh.FLOATS_PER_VERTEX)
    }
}
