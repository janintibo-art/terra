package com.terra.sim

import kotlin.math.exp

/**
 * Relief structural — lot 1.6. Le bruit cesse ici d'être la cause du relief
 * pour n'en rester que l'habillage.
 *
 * ## Le modèle
 *
 * Pour chaque sommet de la grille, une élévation **structurale** en mètres :
 *
 *  - un socle par type de croûte — la continentale flotte plus haut que
 *    l'océanique, c'est l'isostasie résumée en deux constantes ;
 *  - les profils tectoniques, fonctions de la distance aux frontières :
 *    chaîne de collision, cordillère et sa fosse appariée, arc insulaire et
 *    la sienne, dorsale large, rift étroit à épaulements. Formes gaussiennes,
 *    hauteurs et largeurs calibrées par simulation contre les références
 *    terrestres (Tibet ~5 000 m, fosse Pérou-Chili −8 000, cordillère à
 *    ~130 km de sa fosse, flancs de dorsale sur ~800 km) ;
 *  - l'ampleur suit la vitesse relative de la frontière : une collision
 *    rapide soulève plus haut.
 *
 * Le champ est ensuite évalué partout par le [FieldSampler] : c'est lui qui
 * fait de ce tableau une fonction continue, exacte aux sommets — l'invariant
 * n°3 traverse donc le lot intact.
 *
 * ## Choix de conception assumé
 *
 * Le socle de croûte (±) déplace les côtes vers les frontières de plaques
 * sans redéfinir entièrement les continents : le masque continental reste
 * co-écrit par le bruit, pour des côtes organiques. L'alternative « les
 * continents sont exactement les plaques continentales » donnerait des côtes
 * plus polygonales ; elle reste possible plus tard en augmentant simplement
 * les deux constantes de socle.
 */
object TectonicRelief {

    /** Le bruit d'habillage est amorti à ce facteur de son ancienne ampleur. */
    const val NOISE_DAMP = 0.45f

    /** Socle isostatique, en mètres. */
    const val CRUST_CONTINENTAL_M = 250f
    const val CRUST_OCEANIC_M = -900f

    /** Intensité de référence : la vitesse relative moyenne (rad/Ma). */
    private const val REF_INTENSITY = 0.0095f

    fun build(sphere: Icosphere, plates: PlateSet, dist: BoundaryDistanceField): FloatArray {
        val n = sphere.vertexCount
        val out = FloatArray(n)

        for (v in 0 until n) {
            val plateHere = plates.plateId[v]
            val oceanicHere = plates.plates[plateHere].oceanic
            var e = if (oceanicHere) CRUST_OCEANIC_M else CRUST_CONTINENTAL_M

            // --- Convergences : chaînes, cordillères, fosses, arcs ---
            val dc = dist.distConvergent[v]
            if (dc < 0.25f) {
                val k = (dist.intensityConvergent[v] / REF_INTENSITY).coerceIn(0.45f, 1.6f)
                val upper = plateHere == dist.upperConvergent[v].toInt()
                when (dist.contextConvergent[v]) {
                    BoundaryDistanceField.CRUST_CC ->
                        // Collision continentale : symétrique, large, haute.
                        e += 3800f * k * gauss(dc, 0f, 0.055f)

                    BoundaryDistanceField.CRUST_OC ->
                        e += if (upper) {
                            // Côté chevauchant : la cordillère culmine en
                            // retrait de la frontière, comme les Andes.
                            3200f * k * gauss(dc, 0.020f, 0.030f)
                        } else {
                            // Côté plongeant : la fosse, étroite, collée à la
                            // frontière. Un sommet continental d'une plaque
                            // tierce (coin triple) ne doit pas se creuser en
                            // fosse : atténuation forte de ce cas absurde.
                            val trench = -4300f * k * gauss(dc, 0f, 0.013f)
                            if (oceanicHere) trench else trench * 0.25f
                        }

                    else ->
                        e += if (upper) {
                            3800f * 0.75f * k * gauss(dc, 0.022f, 0.020f)
                        } else {
                            val trench = -4800f * k * gauss(dc, 0f, 0.013f)
                            if (oceanicHere) trench else trench * 0.25f
                        }
                }
            }

            // --- Divergences : dorsales et rifts ---
            val dd = dist.distDivergent[v]
            if (dd < 0.3f) {
                val k = (dist.intensityDivergent[v] / REF_INTENSITY).coerceIn(0.45f, 1.6f)
                e += if (oceanicHere) {
                    // Dorsale : bombement large du plancher océanique.
                    1900f * k * gauss(dd, 0f, 0.080f)
                } else {
                    // Rift continental : fossé étroit entre épaulements.
                    -1300f * k * gauss(dd, 0f, 0.016f) +
                            450f * k * gauss(dd, 0.030f, 0.020f)
                }
            }

            out[v] = e
        }
        return out
    }

    private fun gauss(d: Float, mu: Float, sigma: Float): Float {
        val t = (d - mu) / sigma
        return exp(-t * t)
    }
}
