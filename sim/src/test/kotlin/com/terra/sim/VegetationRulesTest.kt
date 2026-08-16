package com.terra.sim

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lot 3.6 — répartition de la végétation.
 *
 * La tolérance du test de fréquences (0,008) est CALCULÉE dans
 * validation/repartition.py §3 : quatre écarts-types binomiaux au plus
 * petit poids sur 20 000 tirages — faux rouge à ~6e-5, biais réel de 2 %
 * attrapé à coup sûr.
 */
class VegetationRulesTest {

    private val vegetated = listOf(
        Biome.RAINFOREST, Biome.TEMPERATE_FOREST, Biome.BOREAL_FOREST,
        Biome.WETLAND, Biome.GRASSLAND, Biome.SAVANNA, Biome.STEPPE,
        Biome.TUNDRA, Biome.SEMI_DESERT
    )

    @Test
    fun lesDensitesSontCellesDeLaV026() {
        // DÉPLACÉES, pas modifiées : changer une valeur changerait l'aspect
        // de tous les mondes existants. Ce test fige le contrat.
        assertEquals(1.0f, VegetationRules.densityFor(Biome.RAINFOREST))
        assertEquals(0.9f, VegetationRules.densityFor(Biome.TEMPERATE_FOREST))
        assertEquals(0.8f, VegetationRules.densityFor(Biome.BOREAL_FOREST))
        assertEquals(0.6f, VegetationRules.densityFor(Biome.WETLAND))
        assertEquals(0.5f, VegetationRules.densityFor(Biome.GRASSLAND))
        assertEquals(0.35f, VegetationRules.densityFor(Biome.SAVANNA))
        assertEquals(0.25f, VegetationRules.densityFor(Biome.STEPPE))
        assertEquals(0.12f, VegetationRules.densityFor(Biome.TUNDRA))
        assertEquals(0.08f, VegetationRules.densityFor(Biome.SEMI_DESERT))
        for (bare in listOf(
            Biome.DESERT, Biome.BEACH, Biome.BARE_ROCK, Biome.SNOW,
            Biome.GLACIER, Biome.ALPINE, Biome.SEA_ICE, Biome.OCEAN,
            Biome.DEEP_OCEAN, Biome.SHALLOW_SEA
        )) {
            assertEquals(0f, VegetationRules.densityFor(bare), bare.label)
        }
    }

    @Test
    fun couvertureEtNormalisation() {
        for (biome in Biome.values()) {
            val mix = VegetationRules.mixFor(biome)
            if (VegetationRules.densityFor(biome) > 0f) {
                assertTrue(mix.isNotEmpty(), "${biome.label} : densité sans mélange")
                val total = mix.map { it.weight }.sum()
                assertTrue(abs(total - 1f) < 1e-5f, "${biome.label} : somme $total")
                // Aucun doublon d'espèce dans un mélange.
                assertEquals(
                    mix.size, mix.map { it.species }.distinct().size, biome.label
                )
            } else {
                assertTrue(mix.isEmpty(), "${biome.label} : mélange sans densité")
                assertNull(VegetationRules.speciesAt(biome, 0.5f))
            }
        }
    }

    @Test
    fun vraisemblanceEcologique() {
        // Les huit assertions de l'instruction, reprises telles quelles.
        fun weight(biome: Biome, sp: TreeSpecies): Float =
            VegetationRules.mixFor(biome).firstOrNull { it.species == sp }?.weight ?: 0f

        assertTrue(weight(Biome.BOREAL_FOREST, TreeSpecies.CONIFERE) > 0.7f)
        assertTrue(weight(Biome.RAINFOREST, TreeSpecies.PALMIER) > 0.15f)
        assertEquals(0f, weight(Biome.RAINFOREST, TreeSpecies.CONIFERE))
        assertTrue(weight(Biome.SEMI_DESERT, TreeSpecies.CACTUS) > 0.5f)
        assertEquals(0f, weight(Biome.SEMI_DESERT, TreeSpecies.PALMIER))
        assertTrue(weight(Biome.TUNDRA, TreeSpecies.MOUSSE) > 0.5f)
        assertEquals(0f, weight(Biome.TUNDRA, TreeSpecies.CONIFERE))
        assertTrue(
            weight(Biome.TEMPERATE_FOREST, TreeSpecies.FEUILLU) >
                weight(Biome.TEMPERATE_FOREST, TreeSpecies.CONIFERE)
        )
        assertTrue(weight(Biome.GRASSLAND, TreeSpecies.HERBACEE) >= 0.5f)
        assertTrue(weight(Biome.STEPPE, TreeSpecies.HERBACEE) >= 0.5f)
    }

    @Test
    fun lesFrequencesDeTirageSuiventLesPoids() {
        // 20 000 u équirépartis par biome — pas un RNG : l'équirépartition
        // stricte teste le découpage cumulatif sans aléa de test.
        val n = 20_000
        val tolerance = 0.008f
        for (biome in vegetated) {
            val counts = HashMap<TreeSpecies, Int>()
            for (i in 0 until n) {
                val u = (i + 0.5f) / n
                val sp = VegetationRules.speciesAt(biome, u)!!
                counts[sp] = (counts[sp] ?: 0) + 1
            }
            for (entry in VegetationRules.mixFor(biome)) {
                val freq = (counts[entry.species] ?: 0).toFloat() / n
                assertTrue(
                    abs(freq - entry.weight) < tolerance,
                    "${biome.label} / ${entry.species.label} : $freq pour ${entry.weight}"
                )
            }
        }
    }

    @Test
    fun tirageDeterministeEtBornesSures() {
        for (biome in vegetated) {
            assertEquals(
                VegetationRules.speciesAt(biome, 0.37f),
                VegetationRules.speciesAt(biome, 0.37f)
            )
            // u = 0 donne la première espèce, et le bord haut ne rend
            // jamais nul — l'arrondi float des poids est absorbé.
            assertEquals(
                VegetationRules.mixFor(biome).first().species,
                VegetationRules.speciesAt(biome, 0f)
            )
            assertEquals(
                VegetationRules.mixFor(biome).last().species,
                VegetationRules.speciesAt(biome, 0.9999999f)
            )
        }
    }
}
