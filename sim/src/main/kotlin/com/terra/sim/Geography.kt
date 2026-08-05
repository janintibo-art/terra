package com.terra.sim

import com.terra.core.PI_F

/**
 * Analyse géographique du monde généré.
 *
 * ## Pourquoi ces mesures
 *
 * « 66 % d'océans » ne dit rien de la qualité d'un monde. Deux planètes ayant la
 * même proportion de terre peuvent être :
 *
 *  - un super-continent unique entouré de vide — monotone
 *  - une poussière de dix mille îlots — illisible
 *  - trois continents et quelques archipels — intéressant
 *
 * Ces trois cas se distinguent par le **nombre de masses continentales**, la
 * **part du plus grand continent** et la **longueur de littoral**. Sans ces
 * chiffres, on règle la génération à l'aveugle. Avec eux, on saura dire
 * objectivement si la tectonique du lot 1.4 améliore les choses ou non.
 */
class Geography private constructor(
    val landmassCount: Int,
    val continentCount: Int,
    val islandCount: Int,
    val largestLandmassFraction: Float,
    val landFraction: Float,
    val coastlineEdges: Int,
    val coastlineKm: Float,
    val inlandSeaCount: Int,
    val meanLandAltitudeM: Float,
    val mountainFraction: Float,
    /** Taille des cinq plus grandes masses continentales, en nombre de cellules. */
    val topLandmasses: List<Int>
) {

    /** Indice de fragmentation : 0 = un bloc unique, 1 = poussière d'îlots. */
    val fragmentation: Float
        get() = if (landmassCount <= 1) 0f else 1f - largestLandmassFraction

    fun summary(): String = buildString {
        append(continentCount).append(" continents, ")
        append(islandCount).append(" îles · plus grand ")
        append("%.0f".format(largestLandmassFraction * 100f)).append(" % des terres")
    }

    companion object {

        /** Une masse continentale est un « continent » au-delà de cette part des terres. */
        private const val CONTINENT_THRESHOLD = 0.06f

        fun analyze(data: PlanetData): Geography {
            val n = data.vertexCount
            val adjacency = data.sphere.buildAdjacency()
            val land = BooleanArray(n) { data.altitudeM[it] >= 0f }

            // --- Composantes connexes terrestres (remplissage itératif) ---
            val landmasses = connectedComponents(adjacency, n) { land[it] }
            val landCells = land.count { it }
            val sizes = landmasses.sortedDescending()

            val continents = sizes.count { it.toFloat() / maxOf(1, landCells) >= CONTINENT_THRESHOLD }
            val largest = if (sizes.isEmpty()) 0 else sizes[0]

            // --- Composantes océaniques : détection des mers intérieures ---
            val oceanComponents = connectedComponents(adjacency, n) { !land[it] }
            val oceanCells = n - landCells
            // Toute étendue d'eau isolée représentant moins de 8 % de l'eau
            // totale est considérée comme une mer intérieure ou un grand lac.
            val inlandSeas = oceanComponents.count {
                it.toFloat() / maxOf(1, oceanCells) < 0.08f
            }

            // --- Littoral : arêtes séparant une cellule terrestre d'une marine ---
            var coastEdges = 0
            for (i in 0 until n) {
                if (!land[i]) continue
                for (j in adjacency[i]) if (!land[j]) coastEdges++
            }

            // Longueur physique approchée. Sur une icosphère de niveau L, la
            // distance moyenne entre voisins vaut environ le rayon multiplié par
            // la racine de (4·pi / nombre de cellules).
            val meanEdgeRad = kotlin.math.sqrt(4f * PI_F / n)
            val edgeKm = meanEdgeRad * (data.params.radiusM / 1000f)
            val coastKm = coastEdges * edgeKm * 0.5f   // chaque arête vue deux fois

            // --- Relief ---
            var altSum = 0.0
            var mountainCells = 0
            for (i in 0 until n) {
                if (!land[i]) continue
                altSum += data.altitudeM[i]
                if (data.altitudeM[i] > 2000f) mountainCells++
            }
            val meanAlt = if (landCells > 0) (altSum / landCells).toFloat() else 0f

            return Geography(
                landmassCount = landmasses.size,
                continentCount = continents,
                islandCount = landmasses.size - continents,
                largestLandmassFraction = if (landCells > 0) largest.toFloat() / landCells else 0f,
                landFraction = landCells.toFloat() / n,
                coastlineEdges = coastEdges,
                coastlineKm = coastKm,
                inlandSeaCount = inlandSeas,
                meanLandAltitudeM = meanAlt,
                mountainFraction = if (landCells > 0) mountainCells.toFloat() / landCells else 0f,
                topLandmasses = sizes.take(5)
            )
        }

        /**
         * Composantes connexes par remplissage itératif.
         *
         * Volontairement itératif et non récursif : une récursion sur 40 000
         * cellules connexes déborderait la pile sur Android.
         */
        private fun connectedComponents(
            adjacency: Array<IntArray>,
            n: Int,
            belongs: (Int) -> Boolean
        ): List<Int> {
            val visited = BooleanArray(n)
            val sizes = ArrayList<Int>()
            val stack = IntArray(n)

            for (start in 0 until n) {
                if (visited[start] || !belongs(start)) continue
                var top = 0
                stack[top++] = start
                visited[start] = true
                var size = 0
                while (top > 0) {
                    val v = stack[--top]
                    size++
                    for (w in adjacency[v]) {
                        if (!visited[w] && belongs(w)) {
                            visited[w] = true
                            stack[top++] = w
                        }
                    }
                }
                sizes.add(size)
            }
            return sizes
        }
    }
}
