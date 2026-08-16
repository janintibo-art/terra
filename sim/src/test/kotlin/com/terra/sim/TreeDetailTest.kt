package com.terra.sim

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Lot 3.3-b — niveaux de détail et allocation sous budget.
 *
 * Le test central est [leBudgetEstTenuQuelleQueSoitLaDensite] : c'est la
 * propriété que la conception par seuils ne savait PAS garantir, et la
 * raison d'être de l'allocateur.
 */
class TreeDetailTest {

    private val pxPerRadian = 1406.8f

    private fun mesh(species: TreeSpecies, detail: TreeDetail): Int {
        val tree = TreeGenerator.generate(species.params(), 1L)
        return TreeMesh.build(tree, species.params(), detail = detail)
            .size / TreeMesh.FLOATS_PER_VERTEX / 3
    }

    // ------------------------------------------------------ les quatre niveaux

    @Test
    fun chaqueNiveauEstStrictementMoinsCherQueLePrecedent() {
        // La propriété qui donne son sens à tout le lot. Elle vaut pour
        // TOUTES les espèces : un niveau dégradé qui coûterait plus cher
        // sur une seule d'entre elles ruinerait l'allocation.
        for (species in TreeSpecies.values()) {
            val costs = TreeDetail.values().map { mesh(species, it) }
            for (i in 1 until costs.size) {
                assertTrue(
                    costs[i] < costs[i - 1],
                    "${species.label} : ${TreeDetail.values()[i]} (${costs[i]}) " +
                        "pas moins cher que ${TreeDetail.values()[i - 1]} (${costs[i - 1]})"
                )
            }
        }
    }

    @Test
    fun coutsDuConifereConformesALInstruction() {
        // Ordres de grandeur annoncés dans validation/lod_arbres.py.
        // Valeurs MESURÉES puis inscrites, pas devinées : mes premiers
        // seuils (« MEDIUM < 8 000 ») étaient faux d'un tiers.
        assertEquals(17_776, mesh(TreeSpecies.CONIFERE, TreeDetail.FULL))
        assertEquals(10_110, mesh(TreeSpecies.CONIFERE, TreeDetail.MEDIUM))
        assertEquals(638, mesh(TreeSpecies.CONIFERE, TreeDetail.LOW))
        assertEquals(4, mesh(TreeSpecies.CONIFERE, TreeDetail.BILLBOARD))
        // L'essentiel du gain vient de l'élagage des rameaux, pas des
        // côtés : le conifère porte 1 000 de ses 1 111 segments au dernier
        // niveau. D'où le saut de 10 110 à 638 entre MOYEN et BAS.
        assertEquals(6_472, mesh(TreeSpecies.FEUILLU, TreeDetail.FULL))
        assertEquals(3_721, mesh(TreeSpecies.FEUILLU, TreeDetail.MEDIUM))
        assertEquals(771, mesh(TreeSpecies.FEUILLU, TreeDetail.LOW))
    }

    @Test
    fun lePanneauGardeLaTailleEtLaCouleurDeLArbre() {
        val species = TreeSpecies.FEUILLU
        val tree = TreeGenerator.generate(species.params(), 3L)
        val billboard = TreeMesh.build(
            tree, species.params(), detail = TreeDetail.BILLBOARD
        )
        val stride = TreeMesh.FLOATS_PER_VERTEX
        var maxY = 0f
        for (v in 0 until billboard.size / stride) {
            maxY = maxOf(maxY, billboard[v * stride + 1])
            // Couleur du feuillage : un arbre lointain doit rester vert.
            assertEquals(species.params().foliageRed, billboard[v * stride + 6])
        }
        assertTrue(
            abs(maxY - tree.heightM()) < 1e-3f,
            "panneau de $maxY m pour un arbre de ${tree.heightM()} m"
        )
    }

    @Test
    fun lePredicteurDeSommetsResteJusteATousLesNiveaux() {
        for (species in TreeSpecies.values()) {
            val tree = TreeGenerator.generate(species.params(), 2L)
            for (detail in TreeDetail.values()) {
                assertEquals(
                    TreeMesh.build(tree, species.params(), detail = detail).size /
                        TreeMesh.FLOATS_PER_VERTEX,
                    TreeMesh.vertexCount(tree, species.params(), detail = detail),
                    "${species.label} / ${detail.label}"
                )
            }
        }
    }

    // ------------------------------------------------- taille apparente

    @Test
    fun tailleApparenteEtSonInverse() {
        val h = 11.6f
        for (d in floatArrayOf(5f, 50f, 400f, 1500f)) {
            val px = TreeLodBudget.apparentPx(h, d, pxPerRadian)
            val back = TreeLodBudget.distanceForPx(h, px, pxPerRadian)
            assertTrue(abs(back - d) / d < 1e-3f, "aller-retour à $d m : $back")
        }
        // Décroissance stricte avec la distance.
        assertTrue(
            TreeLodBudget.apparentPx(h, 10f, pxPerRadian) >
                TreeLodBudget.apparentPx(h, 20f, pxPerRadian)
        )
    }

    @Test
    fun lesSeuilsValentPourToutesLesEspeces() {
        // Le même seuil en pixels donne des distances propres à chaque
        // espèce : c'est tout l'intérêt d'un critère en taille apparente.
        val conifer = TreeLodBudget.distanceForPx(11.6f, 90f, pxPerRadian)
        val moss = TreeLodBudget.distanceForPx(0.085f, 90f, pxPerRadian)
        assertTrue(conifer > 100f, "conifère : $conifer m")
        assertTrue(moss < 2f, "mousse : $moss m")
    }

    @Test
    fun hysteresisEmpecheLeClignotement() {
        // Sans état, la bascule se fait haut ; en venant du niveau plein,
        // on le garde plus bas. La zone morte est la bande ×1,15².
        assertEquals(TreeDetail.FULL, TreeLodBudget.detailForSize(104f))
        assertEquals(TreeDetail.MEDIUM, TreeLodBudget.detailForSize(103f))
        assertEquals(TreeDetail.FULL, TreeLodBudget.detailForSize(80f, TreeDetail.FULL))
        assertEquals(TreeDetail.MEDIUM, TreeLodBudget.detailForSize(77f, TreeDetail.FULL))
        // Un arbre minuscule reste un panneau, quel que soit son passé.
        assertEquals(TreeDetail.BILLBOARD, TreeLodBudget.detailForSize(3f, TreeDetail.FULL))
    }

    // ---------------------------------------------------------- allocation

    @Test
    fun leBudgetEstTenuQuelleQueSoitLaDensite() {
        // LE test du lot : la propriété que des seuils de distance ne
        // savaient pas garantir. On essaie des forêts de 10 à 20 000
        // arbres, budgets serrés comme larges.
        val cost = mapOf(
            TreeDetail.FULL to 17_776, TreeDetail.MEDIUM to 5_700,
            TreeDetail.LOW to 200, TreeDetail.BILLBOARD to 4, TreeDetail.NONE to 0
        )
        for (count in intArrayOf(10, 200, 5_000, 20_000)) {
            for (budget in intArrayOf(50_000, 200_000, 700_000)) {
                // Tailles décroissantes, du très proche au très lointain.
                val sizes = FloatArray(count) { 400f / (1f + it * 0.05f) }
                val levels = TreeLodBudget.allocate(sizes, { cost.getValue(it) }, budget)
                val total = levels.sumOf { cost.getValue(it) }
                assertTrue(
                    total <= budget,
                    "budget $budget dépassé ($total) pour $count arbres"
                )
                assertEquals(count, levels.size)
            }
        }
    }

    @Test
    fun lesPlusGrosSontServisEnPremier() {
        val cost = mapOf(
            TreeDetail.FULL to 17_776, TreeDetail.MEDIUM to 5_700,
            TreeDetail.LOW to 200, TreeDetail.BILLBOARD to 4, TreeDetail.NONE to 0
        )
        val sizes = FloatArray(100) { 300f - it * 2.5f }
        val levels = TreeLodBudget.allocate(sizes, { cost.getValue(it) }, 100_000)
        // Le niveau ne peut que se dégrader en descendant la liste.
        for (i in 1 until levels.size) {
            assertTrue(
                levels[i].ordinal >= levels[i - 1].ordinal,
                "l'arbre $i est mieux servi que le précédent, pourtant plus gros"
            )
        }
        assertEquals(TreeDetail.FULL, levels[0])
    }

    @Test
    fun aucunArbreNeDisparaitQuandLeBudgetEstServe() {
        // Piège évité : dépenser tout sur les premiers laisserait les
        // suivants sans rien. Le panneau est un plancher payé d'avance.
        val cost = mapOf(
            TreeDetail.FULL to 17_776, TreeDetail.MEDIUM to 5_700,
            TreeDetail.LOW to 200, TreeDetail.BILLBOARD to 4, TreeDetail.NONE to 0
        )
        val sizes = FloatArray(500) { 300f - it * 0.5f }
        val levels = TreeLodBudget.allocate(sizes, { cost.getValue(it) }, 60_000)
        assertEquals(500, levels.size)
        val total = levels.sumOf { cost.getValue(it) }
        assertTrue(total <= 60_000)
        // Tous les arbres ont AU MOINS un panneau.
        assertTrue(levels.all { cost.getValue(it) >= 4 })
    }

    @Test
    fun quandMemeLePlancherNeTientPasLesPlusLointainsDisparaissent() {
        // Le cas qui a fait tomber la v0.48.0 : 20 000 panneaux à quatre
        // triangles en coûtent 80 000, au-dessus d'un budget de 50 000.
        // L'allocateur doit alors retirer les arbres les plus lointains —
        // et non promettre un budget qu'il ne peut pas tenir.
        val cost = mapOf(
            TreeDetail.FULL to 17_776, TreeDetail.MEDIUM to 5_700,
            TreeDetail.LOW to 200, TreeDetail.BILLBOARD to 4, TreeDetail.NONE to 0
        )
        val sizes = FloatArray(20_000) { 400f / (1f + it * 0.05f) }
        val levels = TreeLodBudget.allocate(sizes, { cost.getValue(it) }, 50_000)
        val total = levels.sumOf { cost.getValue(it) }
        assertTrue(total <= 50_000, "budget dépassé : $total")
        // Des arbres ont bien été retirés, et ce sont les DERNIERS.
        assertTrue(levels.any { it == TreeDetail.NONE })
        assertEquals(TreeDetail.NONE, levels.last())
        assertTrue(levels.first() != TreeDetail.NONE)
    }

    @Test
    fun lesPlafondsPrimentSurLeBudget() {
        // Budget énorme, arbre minuscule : il reste un panneau.
        val cost = mapOf(
            TreeDetail.FULL to 17_776, TreeDetail.MEDIUM to 5_700,
            TreeDetail.LOW to 200, TreeDetail.BILLBOARD to 4, TreeDetail.NONE to 0
        )
        val levels = TreeLodBudget.allocate(
            floatArrayOf(8f, 5f, 2f), { cost.getValue(it) }, 10_000_000
        )
        assertTrue(levels.all { it == TreeDetail.BILLBOARD })
    }

    @Test
    fun listeNonTrieeRefusee() {
        // Trier dans l'allocateur coûterait une allocation par image ;
        // l'appelant tient déjà ses arbres par distance. Le prérequis est
        // donc vérifié, pas contourné en silence.
        assertFailsWith<IllegalArgumentException> {
            TreeLodBudget.allocate(floatArrayOf(10f, 50f, 20f), { 100 }, 1_000)
        }
    }

    @Test
    fun foretVide() {
        assertEquals(0, TreeLodBudget.allocate(FloatArray(0), { 100 }, 1_000).size)
    }
}
