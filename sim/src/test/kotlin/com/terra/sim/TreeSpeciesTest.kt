package com.terra.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lot 3.2 — familles d'espèces.
 *
 * TOUS les seuils de ce fichier viennent de `validation/especes.py`, qui
 * rejoue la grammaire en Python sur 400 tirages par famille. Ils sont
 * mesurés puis élargis de 15 %, jamais devinés : un échec ici signale une
 * dérive de la grammaire, pas un seuil mal choisi.
 */
class TreeSpeciesTest {

    /** Marge appliquée aux bornes mesurées. */
    private val margin = 0.15f

    private fun withinMeasured(value: Float, lo: Float, hi: Float, what: String) {
        val loM = lo * (1f - margin)
        val hiM = hi * (1f + margin)
        assertTrue(value in loM..hiM, "$what = $value hors de [$loM ; $hiM]")
    }

    @Test
    fun toutesLesFamillesSeGenerentEtTiennentLeBudget() {
        for (species in TreeSpecies.values()) {
            val tree = TreeGenerator.generate(species.params(), 1L)
            assertTrue(tree.segments.isNotEmpty(), "${species.label} vide")
            assertTrue(
                tree.segments.size <= TreeGenerator.MAX_SEGMENTS,
                "${species.label} dépasse la garde"
            )
            for (s in tree.segments) {
                assertTrue(s.baseX.isFinite() && s.baseY.isFinite() && s.baseZ.isFinite())
                assertTrue(s.tipX.isFinite() && s.tipY.isFinite() && s.tipZ.isFinite())
                assertTrue(s.radiusBaseM > 0f, "${species.label} : rayon nul")
            }
        }
    }

    @Test
    fun comptesExactsDeSegments() {
        // Somme k_eff^d, d = 0..D (validation §1).
        val expected = mapOf(
            TreeSpecies.CONIFERE to 1111,   // k_eff = 1 + 3×3 = 10, D = 3
            TreeSpecies.FEUILLU to 364,     // k_eff = 3, D = 5
            TreeSpecies.PALMIER to 9,       // k_eff = 8, D = 1
            TreeSpecies.CACTUS to 7,        // k_eff = 2, D = 2
            TreeSpecies.ARBUSTE to 121,     // k_eff = 3, D = 4
            TreeSpecies.HERBACEE to 5,      // k_eff = 4, D = 1
            TreeSpecies.MOUSSE to 4         // k_eff = 3, D = 1
        )
        for ((species, count) in expected) {
            val tree = TreeGenerator.generate(species.params(), 3L)
            assertEquals(count, tree.segments.size, species.label)
        }
    }

    @Test
    fun silhouettesDansLesBornesMesurees() {
        // Bornes du §2 de validation/especes.py : hauteur puis envergure.
        val bounds = mapOf(
            TreeSpecies.CONIFERE to floatArrayOf(11.390f, 11.894f, 3.543f, 3.811f),
            TreeSpecies.FEUILLU to floatArrayOf(10.792f, 11.247f, 5.039f, 6.373f),
            TreeSpecies.PALMIER to floatArrayOf(10.320f, 10.833f, 3.558f, 3.830f),
            TreeSpecies.CACTUS to floatArrayOf(3.932f, 3.940f, 0.314f, 0.580f),
            TreeSpecies.ARBUSTE to floatArrayOf(2.416f, 2.592f, 1.215f, 1.573f),
            TreeSpecies.HERBACEE to floatArrayOf(0.619f, 0.630f, 0.054f, 0.149f),
            TreeSpecies.MOUSSE to floatArrayOf(0.083f, 0.088f, 0.017f, 0.033f)
        )
        // Plusieurs graines : les bornes valent pour le TIRAGE, pas pour un
        // individu chanceux.
        for (seed in 1L..12L) {
            for ((species, b) in bounds) {
                val tree = TreeGenerator.generate(species.params(), seed)
                withinMeasured(tree.heightM(), b[0], b[1], "${species.label} hauteur")
                withinMeasured(tree.spreadM(), b[2], b[3], "${species.label} envergure")
            }
        }
    }

    @Test
    fun conifereEtFeuilluSeDistinguentParLElancement() {
        // Le seuil 0,39 est le milieu géométrique des plages mesurées
        // (conifère ≤ 0,33 ; feuillu ≥ 0,46). La conicité, elle, a été
        // ESSAYÉE et écartée : elle ne sépare pas les deux familles.
        val threshold = 0.39f
        for (seed in 1L..12L) {
            val conifer = TreeGenerator.generate(TreeSpecies.CONIFERE.params(), seed)
            val broadleaf = TreeGenerator.generate(TreeSpecies.FEUILLU.params(), seed)
            assertTrue(
                conifer.slendernessRatio() < threshold,
                "conifère trop large : ${conifer.slendernessRatio()}"
            )
            assertTrue(
                broadleaf.slendernessRatio() > threshold,
                "feuillu trop serré : ${broadleaf.slendernessRatio()}"
            )
        }
    }

    @Test
    fun seulLeConifereBrancheLeLongDuFut() {
        // Le test qui couvre l'EXTENSION du lot 3.2. Sans branchement
        // latéral, ce compte tomberait à zéro et le conifère perdrait sa
        // silhouette — c'est le bug que ce test attraperait.
        val conifer = TreeGenerator.generate(TreeSpecies.CONIFERE.params(), 5L)
        // 9 latéraux par parent branchant ; parents = 1 + 10 + 100 = 111.
        assertEquals(111 * 9, conifer.lateralCount())
        for (species in TreeSpecies.values()) {
            if (species == TreeSpecies.CONIFERE) continue
            val tree = TreeGenerator.generate(species.params(), 5L)
            assertEquals(0, tree.lateralCount(), "${species.label} ne devrait pas brancher latéralement")
        }
    }

    @Test
    fun continuiteDesRayonsAuxAttachesLaterales() {
        // Généralisation de l'invariant du lot 3.1 : un latéral naît avec
        // exactement le rayon du parent AU POINT d'attache, jamais plus gros
        // que le fût qui le porte.
        val tree = TreeGenerator.generate(TreeSpecies.CONIFERE.params(), 8L)
        for (s in tree.segments) {
            if (s.parent < 0) continue
            val p = tree.segments[s.parent]
            assertTrue(
                s.radiusBaseM <= p.radiusBaseM + 1e-6f,
                "latéral plus gros que son parent"
            )
            assertTrue(s.radiusBaseM >= p.radiusTipM - 1e-6f, "rayon d'attache sous la pointe")
            // Position d'attache sur le segment parent, pas ailleurs.
            val ex = p.tipX - p.baseX; val ey = p.tipY - p.baseY; val ez = p.tipZ - p.baseZ
            val len2 = ex * ex + ey * ey + ez * ez
            val t = ((s.baseX - p.baseX) * ex + (s.baseY - p.baseY) * ey +
                (s.baseZ - p.baseZ) * ez) / len2
            assertTrue(t in 0.2f..1.001f, "attache hors du fût : t = $t")
        }
    }

    @Test
    fun lePiedEstEnfouiAssezMaisPasTrop() {
        // Assez : l'anneau de base ouvert doit passer sous terre, donc au
        // moins le diamètre du tronc — et pour les grands sujets, de quoi
        // couvrir l'écart d'une tuile de niveau 18 (~36 cm), qui est celle
        // qu'on obtient à 50 m de distance.
        for (species in TreeSpecies.values()) {
            val tree = TreeGenerator.generate(species.params(), 2L)
            val sink = tree.footSinkM()
            val trunkRadius = tree.segments[0].radiusBaseM
            assertTrue(sink > 0f, "${species.label} : pied non enfoui")
            assertTrue(
                sink >= trunkRadius,
                "${species.label} : enfouissement $sink sous le rayon $trunkRadius"
            )
            // Pas trop : une mousse de 8 cm ne doit pas disparaître.
            assertTrue(
                sink <= 0.25f * tree.heightM() + 1e-6f,
                "${species.label} : arbre englouti"
            )
        }
        // Les grands sujets couvrent bien l'écart d'une tuile de niveau 18.
        for (species in listOf(TreeSpecies.CONIFERE, TreeSpecies.FEUILLU, TreeSpecies.PALMIER)) {
            val tree = TreeGenerator.generate(species.params(), 2L)
            assertTrue(
                tree.footSinkM() >= 0.36f,
                "${species.label} flotterait sur une tuile de niveau 18"
            )
        }
    }

    @Test
    fun determinismeParEspece() {
        for (species in TreeSpecies.values()) {
            val a = TreeGenerator.generate(species.params(), 77L)
            val b = TreeGenerator.generate(species.params(), 77L)
            assertEquals(a.segments, b.segments, species.label)
        }
    }

    @Test
    fun reconnaissanceDesNoms() {
        assertEquals(TreeSpecies.CONIFERE, TreeSpecies.parse("conifère"))
        assertEquals(TreeSpecies.CONIFERE, TreeSpecies.parse("CONIFERE"))
        assertEquals(TreeSpecies.CONIFERE, TreeSpecies.parse("coni"))
        assertEquals(TreeSpecies.PALMIER, TreeSpecies.parse("palmiers"))
        assertEquals(TreeSpecies.HERBACEE, TreeSpecies.parse("herbacée"))
        assertEquals(TreeSpecies.MOUSSE, TreeSpecies.parse("mouss"))
        // « ca » est sans ambiguïté (cactus), « c » ne l'est pas.
        assertEquals(TreeSpecies.CACTUS, TreeSpecies.parse("ca"))
        assertNull(TreeSpecies.parse("c"))
        assertNull(TreeSpecies.parse("chêne"))
        assertNull(TreeSpecies.parse(""))
    }
}
