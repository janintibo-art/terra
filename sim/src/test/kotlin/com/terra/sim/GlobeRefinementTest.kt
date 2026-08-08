package com.terra.sim

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Globe haute définition — v0.19.0.
 *
 * Le raffinement repose entièrement sur l'invariant n°3 : le terrain
 * continu rend EXACTEMENT la grille sur ses sommets. Ces tests vérifient
 * que la géométrie fine honore ce contrat là où elle croise la grille, et
 * que le trait de côte fin reste cohérent avec le niveau de la mer.
 */
class GlobeRefinementTest {

    companion object {
        private val world by lazy {
            WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4)).generate()
        }
        private val refinement by lazy { GlobeRefinement(world) }
    }

    @Test
    fun `le raffinement est un niveau au-dessus de la grille`() {
        assertEquals(
            (world.params.subdivisions + 1).coerceAtMost(6),
            refinement.sphere.level
        )
        assertTrue(refinement.sphere.vertexCount > world.vertexCount * 3,
            "le niveau supérieur doit environ quadrupler les sommets")
        assertEquals(refinement.sphere.vertexCount, refinement.renderRadius.size)
        assertEquals(refinement.sphere.vertexCount, refinement.nearestCell.size)
    }

    @Test
    fun `la geometrie fine coincide avec la grille sur les sommets partages`() {
        // Pour chaque sommet de la GRILLE, on cherche le sommet fin qui
        // porte la même direction (sans supposer que les indices sont un
        // préfixe : c'est un détail d'implémentation d'Icosphere), et le
        // rayon de rendu doit être identique au bit près — c'est
        // l'invariant n°3 vu du globe. Un échantillon suffit : l'égalité
        // est structurelle, pas statistique.
        val fine = refinement.sphere
        var matched = 0
        var i = 0
        while (i < world.vertexCount && matched < 60) {
            val v = world.sphere.vertices[i]
            // Recherche du même vecteur unitaire côté fin.
            var found = -1
            for (j in 0 until fine.vertexCount) {
                val w = fine.vertices[j]
                val dx = v.x - w.x; val dy = v.y - w.y; val dz = v.z - w.z
                if (dx * dx + dy * dy + dz * dz < 1e-12f) { found = j; break }
            }
            if (found >= 0) {
                assertEquals(
                    world.renderRadius(i), refinement.renderRadius[found],
                    "rayon divergent entre grille et raffinement au sommet $i"
                )
                matched++
            }
            i += 37   // échantillonnage clairsemé, le balayage complet serait quadratique
        }
        assertTrue(matched >= 30, "trop peu de sommets partagés retrouvés ($matched)")
    }

    @Test
    fun `le trait de cote fin suit le niveau de la mer`() {
        for (i in 0 until refinement.sphere.vertexCount) {
            val a = world.terrain.altitudeAt(refinement.sphere.vertices[i])
            assertEquals(a <= 0f, refinement.water[i], "désaccord mer/terre au sommet fin $i")
            if (a <= 0f) {
                assertEquals(1f, refinement.renderRadius[i], "la mer doit rester au rayon unité")
            } else {
                assertTrue(refinement.renderRadius[i] > 1f, "la terre doit être exagérée")
            }
        }
    }

    @Test
    fun `chaque sommet fin pointe une cellule valide et proche`() {
        for (i in 0 until refinement.sphere.vertexCount step 7) {
            val cell = refinement.nearestCell[i]
            assertTrue(cell in 0 until world.vertexCount, "cellule hors bornes au sommet $i")
            // La cellule désignée doit être plus proche que la moyenne des
            // cellules : à défaut de prouver le minimum global (la marche
            // est une heuristique), on prouve qu'elle est dans le voisinage
            // — moins d'une arête et demie de la grille.
            val v = refinement.sphere.vertices[i]
            val c = world.sphere.vertices[cell]
            val dot = v.x * c.x + v.y * c.y + v.z * c.z
            // Seuil calculé : le vrai plus proche est à moins d'une demi-arête
            // (cellule de Voronoï), soit 0,035 rad au niveau 4 → cos 0,99939.
            // 0,998 (0,063 rad) laisse la marge du flottant tout en excluant
            // une cellule franchement fausse (une arête pleine = cos 0,9976).
            assertTrue(dot > 0.998f, "cellule lointaine (cos=$dot) au sommet $i")
        }
    }

    @Test
    fun `le raffinement est deterministe`() {
        val again = GlobeRefinement(world)
        assertTrue(refinement.renderRadius.contentEquals(again.renderRadius))
        assertTrue(refinement.water.contentEquals(again.water))
        assertTrue(refinement.nearestCell.contentEquals(again.nearestCell))
    }
}
