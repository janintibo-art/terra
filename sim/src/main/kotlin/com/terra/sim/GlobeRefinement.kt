package com.terra.sim

/**
 * Globe haute définition — raffinement du maillage de contemplation.
 *
 * Le globe était dessiné sur la grille de simulation elle-même : 10 242
 * sommets au niveau 5, des côtes en escalier, des facettes de 250 km. Or le
 * terrain n'est PAS la grille : l'invariant n°3 garantit que
 * [TerrainProfile.altitudeAt] est une fonction continue, exacte aux sommets
 * de la grille. Ce module évalue donc la géométrie du globe UN niveau plus
 * fin — quatre fois plus de triangles — sur le terrain continu : le trait
 * de côte passe là où le terrain croise réellement le niveau de la mer,
 * plus au bord des cellules.
 *
 * Les COULEURS, elles, restent par cellule (plus proche voisin via
 * [CoarseSampler]) : les données climatiques n'existent qu'à la résolution
 * de la grille, et les interpoler peindrait une précision mensongère. La
 * frontière entre biomes reste franche — c'est le relief qui s'affine, pas
 * les données.
 *
 * Tout est calculé UNE fois par monde, sur le fil de travail : le
 * changement de calque ne refait que la passe de couleurs, comme avant.
 */
class GlobeRefinement(
    val data: PlanetData,
    /**
     * Niveau du maillage fin ; un cran au-dessus de la grille, plafonné à
     * 6 : le niveau 7 dupliquerait 327 000 faces en tampon entrelacé, soit
     * 39 Mo de sommets — quatre fois le budget raisonnable d'un Mali-G77.
     * Au plafond, sur une grille déjà au niveau 6, le raffinement devient
     * neutre : géométrie identique par l'invariant n°3, sans dégât.
     */
    level: Int = (data.params.subdivisions + 1).coerceAtMost(6)
) {

    val sphere: Icosphere = Icosphere(level)

    /** Rayon de rendu par sommet fin — le pendant continu de [PlanetData.renderRadius]. */
    val renderRadius: FloatArray

    /** Vrai si le terrain est sous le niveau de la mer au sommet fin. */
    val water: BooleanArray

    /** Cellule de la grille la plus proche de chaque sommet fin, pour les couleurs. */
    val nearestCell: IntArray

    init {
        val n = sphere.vertexCount
        renderRadius = FloatArray(n)
        water = BooleanArray(n)
        nearestCell = IntArray(n)

        val sampler = CoarseSampler(data)
        val exaggeration = data.params.reliefExaggeration
        val maxAlt = data.params.maxAltitudeM
        // L'indice précédent sert d'indice de départ à la marche du plus
        // proche voisin : les sommets d'une icosphère se suivent par
        // paquets voisins, et la marche converge en un ou deux pas.
        var hint = 0
        for (i in 0 until n) {
            val v = sphere.vertices[i]
            val a = data.terrain.altitudeAt(v)
            water[i] = a <= 0f
            // Réplique exacte de PlanetData.renderRadius : la mer reste au
            // rayon unité, la terre est exagérée proportionnellement.
            renderRadius[i] = if (a <= 0f) 1f
                              else 1f + (a / maxAlt) * exaggeration
            hint = sampler.nearestVertex(v, hint)
            nearestCell[i] = hint
        }
    }
}
