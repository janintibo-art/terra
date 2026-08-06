package com.terra.sim

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Le relief a désormais une cause — lot 1.6. Ces tests vérifient les
 * conséquences statistiques du modèle sur de vrais mondes, avec des seuils
 * calibrés par simulation au **tiers** de l'effet attendu : depuis que la
 * tectonique suit le caractère du monde (v0.9.3), un monde doux atténue tous
 * les effets — les seuils doivent attraper un modèle débranché sans rougir
 * sur une pénéplaine légitime.
 */
class TectonicReliefTest {

    companion object {
        private val worlds: List<PlanetData> by lazy {
            listOf("Kaleth", "Ormun", "Vessiane").map {
                WorldGenerator.fromName(it, PlanetParams(subdivisions = 4)).generate()
            }
        }
    }

    @Test
    fun `la fraction oceanique demandee survit au relief tectonique`() {
        // Le second calibrage (percentile de la somme) le garantit par
        // construction : l'écart ne peut venir que de la granularité de la
        // grille et des égalités au seuil.
        for (w in worlds) {
            val gap = kotlin.math.abs(w.stats.oceanFractionActual - w.params.oceanFraction)
            assertTrue(gap < 0.02f, "${w.name} : fraction océanique à ${w.stats.oceanFractionActual}")
        }
    }

    @Test
    fun `les convergences soulevent la terre qui les borde`() {
        for (w in worlds) {
            val bd = w.boundaryDistance
            var nearSum = 0.0; var nearCount = 0
            var landSum = 0.0; var landCount = 0
            for (v in 0 until w.vertexCount) {
                val a = w.altitudeM[v]
                if (a <= 0f) continue
                landSum += a; landCount++
                val upper = w.plates.plateId[v] == bd.upperConvergent[v].toInt()
                val cc = bd.contextConvergent[v] == BoundaryDistanceField.CRUST_CC
                if (bd.distConvergent[v] < 0.03f && (upper || cc)) {
                    nearSum += a; nearCount++
                }
            }
            assertTrue(nearCount > 30, "${w.name} : échantillon trop maigre ($nearCount)")
            val lift = nearSum / nearCount - landSum / landCount
            assertTrue(lift > 450.0, "${w.name} : soulèvement de ${lift.toInt()} m seulement")
        }
    }

    @Test
    fun `les fosses creusent le plancher au bord des subductions`() {
        for (w in worlds) {
            val bd = w.boundaryDistance
            var trenchMin = Float.MAX_VALUE
            var openFloorSum = 0.0; var openFloorCount = 0
            for (v in 0 until w.vertexCount) {
                val a = w.altitudeM[v]
                if (a >= 0f) continue
                val oceanicHere = w.plates.plateOf(v).oceanic
                val nearSubduction = bd.distConvergent[v] < 0.02f &&
                        bd.contextConvergent[v] != BoundaryDistanceField.CRUST_CC &&
                        w.plates.plateId[v] != bd.upperConvergent[v].toInt()
                if (nearSubduction && oceanicHere) {
                    if (a < trenchMin) trenchMin = a
                } else if (bd.distConvergent[v] > 0.08f && oceanicHere) {
                    openFloorSum += a; openFloorCount++
                }
            }
            if (trenchMin == Float.MAX_VALUE || openFloorCount < 100) continue
            val floor = openFloorSum / openFloorCount
            assertTrue(
                trenchMin < floor - 600.0,
                "${w.name} : fosse à ${trenchMin.toInt()} m pour un plancher à ${floor.toInt()} m"
            )
        }
    }

    @Test
    fun `les dorsales bombent le plancher oceanique`() {
        var tested = 0
        for (w in worlds) {
            val bd = w.boundaryDistance
            var ridgeSum = 0.0; var ridgeCount = 0
            var farSum = 0.0; var farCount = 0
            for (v in 0 until w.vertexCount) {
                val a = w.altitudeM[v]
                if (a >= 0f || !w.plates.plateOf(v).oceanic) continue
                // Loin de toute convergence, pour isoler l'effet de la dorsale.
                if (bd.distConvergent[v] < 0.08f) continue
                if (bd.distDivergent[v] < 0.05f) { ridgeSum += a; ridgeCount++ }
                else if (bd.distDivergent[v] > 0.15f) { farSum += a; farCount++ }
            }
            if (ridgeCount < 50 || farCount < 50) continue
            tested++
            val rise = ridgeSum / ridgeCount - farSum / farCount
            assertTrue(rise > 320.0, "${w.name} : bombement de ${rise.toInt()} m seulement")
        }
        assertTrue(tested >= 1, "aucun monde n'a fourni d'échantillon de dorsale")
    }

    @Test
    fun `le relief reste dans les bornes physiques declarees`() {
        // Bornes STRICTES, comme SimTest les exige : la compression
        // asymptotique de softLimit les garantit par construction — la
        // première version pariait sur le calibrage des superpositions et a
        // perdu (v0.9.2).
        for (w in worlds) {
            for (v in 0 until w.vertexCount) {
                val a = w.altitudeM[v]
                assertTrue(a <= w.params.maxAltitudeM + 1f, "${w.name} : pic à $a m")
                assertTrue(a >= -w.params.maxDepthM - 1f, "${w.name} : fond à $a m")
            }
        }
    }
}
