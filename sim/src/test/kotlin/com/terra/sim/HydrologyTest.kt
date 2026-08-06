package com.terra.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Érosion et écoulement — lot 1.9.
 *
 * « Le terrain est mieux érodé » n'est pas une assertion. Ces tests portent
 * donc sur des propriétés vérifiables du réseau : tout écoulement descend,
 * tout chemin atteint la mer, le débit se conserve, et l'érosion n'invente
 * ni ne détruit de terrain hors des bornes annoncées.
 */
class HydrologyTest {

    companion object {
        private val worlds: List<PlanetData> by lazy {
            listOf("Kaleth", "Ormun").map {
                WorldGenerator.fromName(it, PlanetParams(subdivisions = 4)).generate()
            }
        }
    }

    @Test
    fun `tout ecoulement descend strictement`() {
        // Sans cette propriété, l'eau remonterait les pentes et le débit
        // cumulé n'aurait aucun sens.
        for (w in worlds) {
            val h = w.hydrology
            for (i in 0 until h.cellCount) {
                val r = h.receiver[i]
                if (r == i) continue
                assertTrue(
                    h.erodedM[r] < h.erodedM[i],
                    "${w.name} : la cellule $i s'écoule vers $r plus haut"
                )
            }
        }
    }

    @Test
    fun `toute cellule terrestre rejoint la mer sans cycle`() {
        // Le priority-flood garantit un chemin descendant vers la mer ; ce
        // test le vérifie en le parcourant, ce qui détecte aussi tout cycle
        // (un cycle est impossible si les altitudes décroissent strictement,
        // mais la borne d'itérations le prouve plutôt que de le supposer).
        for (w in worlds) {
            val h = w.hydrology
            for (start in 0 until h.cellCount) {
                if (w.altitudeM[start] <= 0f) continue
                var cur = start
                var steps = 0
                while (h.receiver[cur] != cur) {
                    cur = h.receiver[cur]
                    steps++
                    assertTrue(steps <= h.cellCount, "${w.name} : chemin sans fin depuis $start")
                }
                assertTrue(
                    h.erodedM[cur] <= 0f || h.fillDepthM[cur] > 0f,
                    "${w.name} : le chemin depuis $start s'arrête à ${h.erodedM[cur]} m sans être en mer"
                )
            }
        }
    }

    @Test
    fun `le debit se conserve le long du reseau`() {
        // Somme des débits des exutoires = nombre de cellules. Une cellule
        // perdue ou comptée deux fois casserait cette égalité — c'est le
        // test qui attraperait un tri d'accumulation mal ordonné.
        for (w in worlds) {
            val h = w.hydrology
            var outletSum = 0.0
            for (i in 0 until h.cellCount) {
                if (h.receiver[i] == i) outletSum += h.flowAccum[i].toDouble()
            }
            assertEquals(
                h.cellCount.toDouble(), outletSum, h.cellCount * 0.001,
                "${w.name} : débit total aux exutoires"
            )
            // Un débit vaut au moins 1 (sa propre cellule) et jamais plus que
            // la planète entière.
            for (i in 0 until h.cellCount) {
                assertTrue(h.flowAccum[i] >= 1f && h.flowAccum[i] <= h.cellCount.toFloat())
            }
        }
    }

    @Test
    fun `le debit croit vers l aval`() {
        for (w in worlds) {
            val h = w.hydrology
            for (i in 0 until h.cellCount) {
                val r = h.receiver[i]
                if (r == i) continue
                assertTrue(
                    h.flowAccum[r] >= h.flowAccum[i],
                    "${w.name} : le débit diminue de $i vers $r"
                )
            }
        }
    }

    @Test
    fun `l erosion abaisse les terres dans les bornes mesurees`() {
        // Calibrage mesuré par simulation : abaissement médian 8 m, 90ᵉ
        // centile 56 m. Le test borne largement (150 m de médiane, 800 m au
        // pire point) : il attrape un coefficient qui déraperait d'un ordre
        // de grandeur, sans rougir sur la variance entre mondes.
        for (w in worlds) {
            val h = w.hydrology
            val drops = ArrayList<Float>()
            for (i in 0 until h.cellCount) {
                if (w.altitudeM[i] <= 0f) continue
                val drop = w.altitudeM[i] - h.erodedM[i]
                // Le dépôt peut relever une cellule : borne des deux côtés.
                assertTrue(drop < 800f, "${w.name} : abaissement de $drop m en $i")
                assertTrue(drop > -400f, "${w.name} : dépôt de ${-drop} m en $i")
                drops.add(drop)
            }
            drops.sort()
            val median = drops[drops.size / 2]
            assertTrue(median in -20f..150f, "${w.name} : abaissement médian $median m")
        }
    }

    @Test
    fun `la mer garde son altitude et la generation reste deterministe`() {
        for (w in worlds) {
            for (i in 0 until w.vertexCount) {
                if (w.altitudeM[i] <= 0f) {
                    assertEquals(w.altitudeM[i], w.hydrology.erodedM[i], 0f, "mer modifiée en $i")
                }
            }
        }
        val a = WorldGenerator.fromName("Kaleth", PlanetParams(subdivisions = 4)).generate()
        assertTrue(a.hydrology.erodedM.contentEquals(worlds[0].hydrology.erodedM))
        assertTrue(a.hydrology.flowAccum.contentEquals(worlds[0].hydrology.flowAccum))
    }
}
