package com.terra.sim

import kotlin.math.abs
import kotlin.math.acos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Lot 3.1 — le squelette se teste avant le premier triangle : comptages
 * EXACTS (la formule vient de validation/arbres.py), continuité des
 * raccords au bit près, bornes d'angles, déterminisme par égalité de
 * structure entière.
 */
class TreeGeneratorTest {

    private val params = TreeParams.defaultTree()

    @Test
    fun memeGraineMemeArbreExactement() {
        val a = TreeGenerator.generate(params, 12345L)
        val b = TreeGenerator.generate(params, 12345L)
        assertEquals(a.segments, b.segments)
    }

    @Test
    fun grainesDifferentesArbresDifferents() {
        val a = TreeGenerator.generate(params, 1L)
        val b = TreeGenerator.generate(params, 2L)
        assertTrue(a.segments != b.segments)
        // Même ossature cependant : la STRUCTURE ne dépend que des
        // paramètres, seule la géométrie bouge.
        assertEquals(a.segments.size, b.segments.size)
        assertEquals(
            a.segments.map { it.depth to it.parent },
            b.segments.map { it.depth to it.parent }
        )
    }

    @Test
    fun compteExactDeSegments() {
        // k=3, D=5 : (3^6 − 1)/2 = 364 (validation §1).
        val tree = TreeGenerator.generate(params, 7L)
        assertEquals(364, tree.segments.size)
        // Et par profondeur : 3^d.
        for (d in 0..5) {
            val expected = intArrayOf(1, 3, 9, 27, 81, 243)[d]
            assertEquals(expected, tree.segments.count { it.depth == d }, "profondeur $d")
        }
    }

    @Test
    fun continuiteDesRaccords() {
        val tree = TreeGenerator.generate(params, 99L)
        for (s in tree.segments) {
            if (s.parent < 0) continue
            val p = tree.segments[s.parent]
            // Base de l'enfant = pointe du parent, au bit près (copie, pas
            // recalcul), et même rayon au raccord.
            assertEquals(p.tipX, s.baseX)
            assertEquals(p.tipY, s.baseY)
            assertEquals(p.tipZ, s.baseZ)
            assertEquals(p.radiusTipM, s.radiusBaseM)
            assertEquals(p.depth + 1, s.depth)
        }
    }

    @Test
    fun rayonsEtLongueursEnProgressionGeometrique() {
        // Sans dispersion ni tropisme, les grandeurs sont exactes.
        val strict = params.copy(angleJitterRad = 0f, straightness = 0f)
        val tree = TreeGenerator.generate(strict, 4L)
        for (s in tree.segments) {
            var expectedRadius = strict.trunkRadiusM
            repeat(s.depth) { expectedRadius *= strict.radiusRatio }
            assertTrue(abs(s.radiusBaseM - expectedRadius) < 1e-5f, "rayon prof. ${s.depth}")
            var expectedLength = strict.trunkLengthM
            repeat(s.depth) { expectedLength *= strict.lengthRatio }
            assertTrue(abs(s.lengthM() - expectedLength) < 1e-3f, "longueur prof. ${s.depth}")
        }
    }

    @Test
    fun anglesBornes() {
        // Sans tropisme (qui referme les angles), l'écart enfant/parent est
        // borné par nominal + dispersion.
        val open = params.copy(straightness = 0f)
        val tree = TreeGenerator.generate(open, 11L)
        val worst = open.branchAngleRad + open.angleJitterRad + 1e-3f
        for (s in tree.segments) {
            if (s.parent < 0) continue
            val p = tree.segments[s.parent]
            val d = (s.direction() dot p.direction()).coerceIn(-1f, 1f)
            assertTrue(acos(d) <= worst, "angle ${acos(d)} > $worst")
        }
    }

    @Test
    fun tropismeTotalDressePresqueTout() {
        val vertical = params.copy(straightness = 1f, angleJitterRad = 0f)
        val tree = TreeGenerator.generate(vertical, 5L)
        for (s in tree.segments) {
            assertTrue(s.direction().y > 0.999f, "segment non vertical à s=1")
        }
    }

    @Test
    fun parametrageFouRefuseNet() {
        // k=4, D=8 → 87 381 segments (validation §1) : refus, pas écrêtage.
        val crazy = params.copy(children = 4, maxDepth = 8)
        assertFailsWith<IllegalArgumentException> {
            TreeGenerator.generate(crazy, 1L)
        }
        // Et le calcul du pire cas ne déborde pas en 32 bits : k=6, D=12
        // vaut ~2,6 milliards, un Int aurait bouclé en négatif.
        assertEquals(Int.MAX_VALUE, params.copy(children = 6, maxDepth = 12).worstCaseSegments())
    }

    @Test
    fun filDeFerCoherent() {
        val tree = TreeGenerator.generate(params, 3L)
        val wire = tree.wireframeVertices()
        assertEquals(tree.segments.size * 12, wire.size)
        // Premier sommet = base du tronc, à l'origine.
        assertEquals(0f, wire[0]); assertEquals(0f, wire[1]); assertEquals(0f, wire[2])
        // Toutes les valeurs finies, teintes dans [0, 1].
        for (i in wire.indices) {
            assertTrue(wire[i].isFinite())
            if (i % 6 >= 3) assertTrue(wire[i] in 0f..1f, "teinte hors bornes")
        }
        // La hauteur est plausible : entre le tronc seul et la somme
        // géométrique complète des longueurs.
        assertTrue(tree.heightM() > params.trunkLengthM)
        val maxHeight = params.trunkLengthM / (1f - params.lengthRatio)
        assertTrue(tree.heightM() <= maxHeight)
    }
}
