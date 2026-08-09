package com.terra.sim

import com.terra.core.Vec3

/**
 * Ressources exploitables, lot 1.17.
 *
 * Six ressources suffisent à porter la Phase 6 : de quoi manger, bâtir,
 * chauffer, et franchir — ou non — les marches de l'arbre technologique du
 * lot 6.8. Sans dépôt de cuivre à portée, une tribu restera à la pierre :
 * c'est cette carte qui rendra cette phrase vraie.
 */
enum class Resource(
    val label: String,
    /** Part des terres émergées couverte par la ressource. */
    val landFraction: Float
) {
    /** Sol cultivable : plaines alluviales, pente douce, climat clément. */
    FARMLAND("Sol arable", 0.22f),

    /** Bois d'œuvre et de feu : suit la biomasse forestière. */
    WOOD("Bois", 0.30f),

    /** Pierre à bâtir : roche affleurante, reliefs. */
    STONE("Pierre", 0.25f),

    /** Cuivre : arcs volcaniques de subduction et panaches. */
    COPPER("Cuivre", 0.030f),

    /** Fer : vieux boucliers continentaux, loin de toute frontière active. */
    IRON("Fer", 0.055f),

    /** Étain : granites des collisions continentales. Le plus rare. */
    TIN("Étain", 0.014f);

    companion object {
        val ALL: Array<Resource> = values()
    }
}

/**
 * Carte d'abondance des ressources, par cellule d'icosphère.
 *
 * ## Pourquoi un champ DÉRIVÉ
 *
 * Rien de ceci n'entre dans l'empreinte du monde ni dans la sauvegarde :
 * tout se recalcule à partir de données déjà figées (plaques, frontières,
 * panaches, hydrologie, biomes) et d'une graine dérivée. C'est l'invariant
 * n°6, et c'est ce qui permet d'ajouter ce lot sans toucher
 * `GENERATION_VERSION` — les mondes existants gardent leur empreinte.
 *
 * ## Pourquoi des PERCENTILES et pas des seuils
 *
 * Un gisement se décrit spontanément par un seuil : « du cuivre là où le
 * score dépasse 0,7 ». Mais ce score est une somme de bruits et de distances
 * dont la distribution dépend du monde tiré : sur trois mondes simulés, le
 * même seuil couvrait 0 %, 12 % et 79 % des terres (validation
 * `ressources.py`). On trie donc les cellules et on garde la fraction
 * voulue : la couverture devient exacte par construction, sur tout monde et
 * à toute graine. « Borner par construction plutôt que calibrer. »
 */
class ResourceField private constructor(
    /** Abondance dans [0,1] par ressource puis par cellule ; 0 = absente. */
    private val abundance: Array<FloatArray>,
    val vertexCount: Int
) {

    fun abundanceAt(resource: Resource, vertexIndex: Int): Float =
        abundance[resource.ordinal][vertexIndex]

    fun hasResource(resource: Resource, vertexIndex: Int): Boolean =
        abundance[resource.ordinal][vertexIndex] > 0f

    /** Nombre de cellules portant la ressource — sert aux tests et au HUD. */
    fun cellCount(resource: Resource): Int =
        abundance[resource.ordinal].count { it > 0f }

    /** La ressource la plus abondante en ce point, ou `null` si aucune. */
    fun dominantAt(vertexIndex: Int): Resource? {
        var best: Resource? = null
        var bestValue = 0f
        for (r in Resource.ALL) {
            val v = abundance[r.ordinal][vertexIndex]
            if (v > bestValue) {
                bestValue = v
                best = r
            }
        }
        return best
    }

    companion object {

        /**
         * Portée d'influence d'une frontière ou d'un panache, en radians.
         *
         * Un arc volcanique s'étend sur quelques centaines de kilomètres
         * derrière la fosse : 0,05 rad ≈ 320 km sur une planète terrestre.
         * Exprimé en angle et non en mètres pour rester juste si le rayon
         * de la planète change.
         */
        private val BIOMES = Biome.values()

        private const val ARC_REACH_RAD = 0.05f

        /** Distance au-delà de laquelle une croûte est dite « ancienne ». */
        private const val SHIELD_REACH_RAD = 0.18f

        fun generate(data: PlanetData): ResourceField {
            val n = data.vertexCount
            val scores = Array(Resource.ALL.size) { FloatArray(n) }

            // Grain des gisements : sans lui, une ressource occuperait une
            // tache continue parfaitement lisse autour de son masque
            // géologique. Le bruit la fragmente en dépôts. Graine dérivée par
            // ressource : ajouter une ressource plus tard ne déplacera pas
            // les gisements des autres (lot 0.3, la raison d'être des graines
            // hiérarchiques).
            val grain = Resource.ALL.map { r ->
                Noise(data.seed.derive("ressources/${r.name.lowercase()}"))
            }

            val land = BooleanArray(n) { data.altitudeM[it] > 0f }

            for (i in 0 until n) {
                if (!land[i]) continue
                val p = data.sphere.vertices[i]
                val alt = data.altitudeM[i]
                val biome = BIOMES[data.biomeId[i].toInt()]

                // Distances tectoniques, en radians.
                val dConv = data.boundaryDistance.distConvergent[i]
                val dDiv = data.boundaryDistance.distDivergent[i]
                val dTrans = data.boundaryDistance.distTransform[i]
                val dAny = minOf(dConv, dDiv, dTrans)
                val ctx = data.boundaryDistance.contextConvergent[i]

                // Proximité au panache le plus proche : les points chauds
                // portent aussi du cuivre (systèmes hydrothermaux).
                // Distance angulaire par le produit scalaire : les deux
                // vecteurs sont unitaires, acos(p·c) EST l'angle. L'aide
                // géodésique de :core travaille en double et imposerait une
                // conversion pour un gain nul — on ne cherche pas ici une
                // position métrique, seulement un masque de gisement.
                var bestDot = -1f
                for (c in data.hotspots.centers) {
                    val d = p dot c
                    if (d > bestDot) bestDot = d
                }
                val dPlume = if (data.hotspots.centers.isEmpty()) Float.MAX_VALUE
                             else kotlin.math.acos(bestDot.coerceIn(-1f, 1f))

                // --- Sol arable -------------------------------------------
                // Les alluvions font les bonnes terres : débit cumulé élevé,
                // altitude basse, climat qui n'est ni gelé ni aride. Le
                // débit est pris en racine : il varie sur cinq ordres de
                // grandeur, une échelle linéaire ne retiendrait que les
                // grands fleuves.
                val fertile = when (biome) {
                    Biome.GRASSLAND, Biome.TEMPERATE_FOREST, Biome.WETLAND,
                    Biome.SAVANNA, Biome.RAINFOREST -> 1f
                    Biome.STEPPE, Biome.BOREAL_FOREST -> 0.55f
                    Biome.SEMI_DESERT -> 0.2f
                    else -> 0f
                }
                if (fertile > 0f) {
                    val flow = kotlin.math.sqrt(data.hydrology.flowAccum[i])
                    val lowland = clamp01(1f - alt / 1_200f)
                    scores[Resource.FARMLAND.ordinal][i] =
                        fertile * lowland * (0.45f + 0.55f * clamp01(flow / 40f)) *
                            grainAt(grain, Resource.FARMLAND, p, 90f)
                }

                // --- Bois --------------------------------------------------
                val woody = when (biome) {
                    Biome.RAINFOREST -> 1f
                    Biome.TEMPERATE_FOREST -> 0.95f
                    Biome.BOREAL_FOREST -> 0.8f
                    Biome.WETLAND -> 0.5f
                    Biome.SAVANNA -> 0.3f
                    else -> 0f
                }
                if (woody > 0f) {
                    scores[Resource.WOOD.ordinal][i] =
                        woody * grainAt(grain, Resource.WOOD, p, 120f)
                }

                // --- Pierre ------------------------------------------------
                // Roche affleurante : les biomes minéraux d'abord, l'altitude
                // ensuite. Une plaine herbeuse a de la pierre en profondeur,
                // pas à portée de main d'une tribu néolithique.
                val rocky = when (biome) {
                    Biome.BARE_ROCK, Biome.ALPINE -> 1f
                    Biome.TUNDRA, Biome.DESERT, Biome.SEMI_DESERT -> 0.45f
                    else -> 0.18f
                }
                scores[Resource.STONE.ordinal][i] =
                    rocky * (0.3f + 0.7f * clamp01(alt / 2_500f)) *
                        grainAt(grain, Resource.STONE, p, 150f)

                // --- Cuivre ------------------------------------------------
                // Arcs de subduction (croûte océanique sous l'autre plaque)
                // et panaches. La croûte CC — collision continentale — n'en
                // porte pas : c'est ce qui distingue le cuivre de l'étain.
                val subduction = if (ctx == BoundaryDistanceField.CRUST_OC ||
                    ctx == BoundaryDistanceField.CRUST_OO
                ) proximity(dConv, ARC_REACH_RAD) else 0f
                val plume = proximity(dPlume, ARC_REACH_RAD * 0.8f)
                val copperMask = maxOf(subduction, plume * 0.85f)
                if (copperMask > 0f) {
                    scores[Resource.COPPER.ordinal][i] =
                        copperMask * grainAt(grain, Resource.COPPER, p, 260f)
                }

                // --- Fer ---------------------------------------------------
                // Vieux boucliers : croûte continentale, LOIN de toute
                // frontière active — c'est la définition d'un craton. Le
                // masque est donc l'inverse d'une proximité.
                if (!data.plates.plateOf(i).oceanic) {
                    val shield = clamp01(dAny / SHIELD_REACH_RAD)
                    scores[Resource.IRON.ordinal][i] =
                        shield * shield * grainAt(grain, Resource.IRON, p, 200f)
                }

                // --- Étain -------------------------------------------------
                // Granites d'anatexie : collision de deux croûtes
                // continentales, et seulement là.
                if (ctx == BoundaryDistanceField.CRUST_CC) {
                    scores[Resource.TIN.ordinal][i] =
                        proximity(dConv, ARC_REACH_RAD * 1.2f) *
                            grainAt(grain, Resource.TIN, p, 300f)
                }
            }

            // Percentile par ressource : la couverture est fixée par
            // construction, pas par un seuil qui dériverait d'un monde à
            // l'autre.
            val landCount = land.count { it }
            for (r in Resource.ALL) {
                keepTopFraction(scores[r.ordinal], land, landCount, r.landFraction)
            }
            return ResourceField(scores, n)
        }

        /** Proximité décroissante : 1 sur la frontière, 0 au-delà de la portée. */
        private fun proximity(distanceRad: Float, reachRad: Float): Float {
            if (distanceRad >= reachRad) return 0f
            val t = 1f - distanceRad / reachRad
            return t * t
        }

        /** Grain de dépôt, dans [0,1], à la fréquence donnée. */
        private fun grainAt(noises: List<Noise>, r: Resource, p: Vec3, freq: Float): Float {
            val v = noises[r.ordinal].fbm(p.x * freq, p.y * freq, p.z * freq, 3)
            return clamp01(0.5f + v)
        }

        /**
         * Ne garde que la fraction demandée des cellules terrestres, celles
         * de plus haut score ; le reste est mis à zéro.
         *
         * Le tri porte sur une copie des scores terrestres seulement : une
         * cellule océanique ne doit pas peser dans le percentile, sinon la
         * couverture d'un monde à 70 % d'océan serait trois fois trop faible.
         */
        private fun keepTopFraction(
            score: FloatArray, land: BooleanArray, landCount: Int, fraction: Float
        ) {
            if (landCount == 0) return
            val keep = Math.round(fraction * landCount).coerceIn(1, landCount)
            val values = FloatArray(landCount)
            var k = 0
            for (i in score.indices) if (land[i]) values[k++] = score[i]
            values.sort()
            // Seuil = valeur de rang (landCount - keep), tri croissant.
            val threshold = values[(landCount - keep).coerceIn(0, landCount - 1)]
            for (i in score.indices) {
                if (!land[i] || score[i] < threshold || score[i] <= 0f) score[i] = 0f
            }
        }

        private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v
    }
}
