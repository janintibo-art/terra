package com.terra.sim

import com.terra.core.Vec3

/**
 * Interroge la grille simulée en un point quelconque de la sphère.
 *
 * Les tuiles fines ont besoin de savoir quel biome, quelle température, quelle
 * pluviométrie règnent là où elles se trouvent. Ces champs sont portés par
 * l'icosphère grossière : il faut donc pouvoir retrouver rapidement la cellule
 * correspondant à une position arbitraire.
 *
 * ## Méthode
 *
 * Une recherche exhaustive coûterait 10 242 produits scalaires par requête, soit
 * des centaines de millions d'opérations pour mailler une seule tuile — hors de
 * question.
 *
 * On procède par **descente de gradient sur le graphe d'adjacence** : partir du
 * meilleur des douze sommets de l'icosaèdre d'origine, puis se déplacer de
 * voisin en voisin tant qu'on se rapproche. La triangulation étant convexe, il
 * n'existe pas de maximum local parasite : la marche atteint toujours le sommet
 * réellement le plus proche, en une trentaine d'étapes au pire.
 *
 * Un test compare le résultat à la recherche exhaustive sur des milliers de
 * points tirés au hasard.
 */
class CoarseSampler(private val data: PlanetData) {

    private val adjacency: Array<IntArray> = data.sphere.buildAdjacency()
    private val vertices = data.sphere.vertices

    /**
     * Échantillonneur barycentrique, pour les grandeurs qui doivent varier
     * continûment — les couleurs de biome, notamment. Le plus proche voisin
     * convient au biome lui-même (une catégorie ne s'interpole pas), mais pas
     * à sa couleur : il peignait chaque cellule d'un aplat, et la planète
     * apparaissait pavée d'hexagones de 115 km.
     */
    private val field = FieldSampler(data.sphere)

    /**
     * Couleur de biome interpolée entre les trois sommets du triangle.
     * [holder] mémorise le dernier sommet trouvé pour accélérer les requêtes
     * voisines ; [out] reçoit les trois composantes.
     */
    fun sampleBiomeColor(p: Vec3, holder: IntArray, out: FloatArray) {
        field.sample3(data.biomeColorR, data.biomeColorG, data.biomeColorB, p, holder, out)
    }

    /** Indice du sommet de la grille le plus proche du point donné. */
    fun nearestVertex(p: Vec3, hint: Int = -1): Int {
        // Point de départ : l'indice suggéré, sinon le meilleur des douze
        // sommets de l'icosaèdre initial, qui couvrent la sphère régulièrement.
        var best: Int
        var bestDot: Float

        if (hint in vertices.indices) {
            best = hint
            bestDot = p dot vertices[hint]
        } else {
            best = 0
            bestDot = p dot vertices[0]
            for (i in 1 until minOf(12, vertices.size)) {
                val d = p dot vertices[i]
                if (d > bestDot) { bestDot = d; best = i }
            }
        }

        // Descente : on ne s'arrête que lorsqu'aucun voisin n'est meilleur.
        var moved = true
        var guard = 0
        while (moved && guard < 128) {
            moved = false
            guard++
            for (n in adjacency[best]) {
                val d = p dot vertices[n]
                if (d > bestDot) {
                    bestDot = d
                    best = n
                    moved = true
                }
            }
        }
        return best
    }

    fun biomeAt(p: Vec3, hint: Int = -1): Biome = data.biome(nearestVertex(p, hint))

    fun temperatureAt(p: Vec3, hint: Int = -1): Float = data.temperatureC[nearestVertex(p, hint)]

    fun precipitationAt(p: Vec3, hint: Int = -1): Float = data.precipMm[nearestVertex(p, hint)]

    /**
     * Interpolation lissée d'un champ continu, par moyenne pondérée du sommet le
     * plus proche et de ses voisins.
     *
     * Utilisée pour la température et la pluie, où un saut brutal d'une cellule
     * à l'autre se verrait. Les biomes, eux, sont catégoriels : on prend le plus
     * proche sans interpoler, sous peine d'inventer des biomes intermédiaires
     * qui n'existent pas.
     */
    fun smoothField(p: Vec3, values: FloatArray, hint: Int = -1): Float {
        val v = nearestVertex(p, hint)
        var weightSum = 0f
        var total = 0f

        val dCenter = maxOf(1e-5f, 1f - (p dot vertices[v]))
        var w = 1f / dCenter
        total += values[v] * w
        weightSum += w

        for (n in adjacency[v]) {
            val d = maxOf(1e-5f, 1f - (p dot vertices[n]))
            w = 1f / d
            total += values[n] * w
            weightSum += w
        }
        return if (weightSum > 0f) total / weightSum else values[v]
    }

    fun smoothTemperatureAt(p: Vec3, hint: Int = -1): Float =
        smoothField(p, data.temperatureC, hint)

    fun smoothPrecipitationAt(p: Vec3, hint: Int = -1): Float =
        smoothField(p, data.precipMm, hint)
}
