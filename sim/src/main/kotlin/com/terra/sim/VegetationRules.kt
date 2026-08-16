package com.terra.sim

/**
 * Règles de répartition de la végétation — lot 3.6.
 *
 * Trois questions, trois fonctions : COMBIEN de plantes (la densité,
 * DÉPLACÉE de TileMesh à l'identique — changer les valeurs aurait changé
 * l'aspect de tous les mondes sans raison), QUELLES espèces (les mélanges,
 * instruits et vérifiés par huit assertions écologiques dans
 * validation/repartition.py), et LAQUELLE à cette position précise (le
 * tirage cumulatif sur un aléa de position).
 *
 * ## Déterminisme
 *
 * [speciesAt] ne tire rien : il reçoit un `u` uniforme fourni par le
 * micro-hachage du terrain (`TerrainProfile.micro01`), déjà déterministe
 * par graine et par position. La même clairière porte donc le même chêne à
 * chaque visite, sur Android comme sur PC — il n'y a aucun état d'instance
 * qui pourrait diverger.
 */
object VegetationRules {

    /** Une espèce et son poids dans le mélange d'un biome. */
    data class Weighted(val species: TreeSpecies, val weight: Float)

    /**
     * Densité de peuplement, dans [0 ; 1] — la probabilité qu'un
     * emplacement du treillis porte une plante. Valeurs de la v0.26,
     * inchangées : TileMesh délègue ici depuis le lot 3.6.
     */
    fun densityFor(biome: Biome): Float = when (biome) {
        Biome.RAINFOREST -> 1.0f
        Biome.TEMPERATE_FOREST -> 0.9f
        Biome.BOREAL_FOREST -> 0.8f
        Biome.WETLAND -> 0.6f
        Biome.GRASSLAND -> 0.5f
        Biome.SAVANNA -> 0.35f
        Biome.STEPPE -> 0.25f
        Biome.TUNDRA -> 0.12f
        Biome.SEMI_DESERT -> 0.08f
        else -> 0f
    }

    /**
     * Mélange d'espèces du biome, poids sommant à 1, ou liste vide pour
     * les biomes nus. L'ordre des entrées fait partie du contrat : le
     * tirage cumulatif de [speciesAt] le parcourt tel quel, et le changer
     * changerait quelle plante pousse où dans tous les mondes.
     */
    fun mixFor(biome: Biome): List<Weighted> = when (biome) {
        Biome.RAINFOREST -> listOf(
            Weighted(TreeSpecies.FEUILLU, 0.62f),
            Weighted(TreeSpecies.PALMIER, 0.23f),
            Weighted(TreeSpecies.ARBUSTE, 0.15f)
        )
        Biome.TEMPERATE_FOREST -> listOf(
            Weighted(TreeSpecies.FEUILLU, 0.62f),
            Weighted(TreeSpecies.CONIFERE, 0.23f),
            Weighted(TreeSpecies.ARBUSTE, 0.15f)
        )
        Biome.BOREAL_FOREST -> listOf(
            Weighted(TreeSpecies.CONIFERE, 0.80f),
            Weighted(TreeSpecies.ARBUSTE, 0.12f),
            Weighted(TreeSpecies.MOUSSE, 0.08f)
        )
        Biome.WETLAND -> listOf(
            Weighted(TreeSpecies.HERBACEE, 0.50f),
            Weighted(TreeSpecies.FEUILLU, 0.28f),
            Weighted(TreeSpecies.ARBUSTE, 0.22f)
        )
        Biome.GRASSLAND -> listOf(
            Weighted(TreeSpecies.HERBACEE, 0.72f),
            Weighted(TreeSpecies.ARBUSTE, 0.18f),
            Weighted(TreeSpecies.FEUILLU, 0.10f)
        )
        Biome.SAVANNA -> listOf(
            Weighted(TreeSpecies.HERBACEE, 0.58f),
            Weighted(TreeSpecies.FEUILLU, 0.30f),
            Weighted(TreeSpecies.ARBUSTE, 0.12f)
        )
        Biome.STEPPE -> listOf(
            Weighted(TreeSpecies.HERBACEE, 0.78f),
            Weighted(TreeSpecies.ARBUSTE, 0.22f)
        )
        Biome.TUNDRA -> listOf(
            Weighted(TreeSpecies.MOUSSE, 0.62f),
            Weighted(TreeSpecies.HERBACEE, 0.28f),
            Weighted(TreeSpecies.ARBUSTE, 0.10f)
        )
        Biome.SEMI_DESERT -> listOf(
            Weighted(TreeSpecies.CACTUS, 0.55f),
            Weighted(TreeSpecies.ARBUSTE, 0.30f),
            Weighted(TreeSpecies.HERBACEE, 0.15f)
        )
        else -> emptyList()
    }

    /**
     * L'espèce à une position donnée, par tirage cumulatif de [u] sur les
     * poids du mélange. Nul pour un biome nu.
     *
     * @param u uniforme dans [0 ; 1), venu du micro-hachage de position —
     *        JAMAIS d'un générateur à état, qui romprait le déterminisme.
     */
    fun speciesAt(biome: Biome, u: Float): TreeSpecies? {
        val mix = mixFor(biome)
        if (mix.isEmpty()) return null
        var cumulative = 0f
        for (entry in mix) {
            cumulative += entry.weight
            if (u < cumulative) return entry.species
        }
        // u tombé dans l'épaisseur d'arrondi des poids float : la dernière
        // espèce du mélange, plutôt qu'un nul qui ferait un trou dans la
        // forêt une fois sur seize millions.
        return mix.last().species
    }
}
