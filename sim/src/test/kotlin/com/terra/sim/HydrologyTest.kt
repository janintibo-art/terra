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
    fun `tout ecoulement descend sur le terrain de routage`() {
        // Le réseau est calculé sur la roche dont les cuvettes sont comblées
        // — le trajet réel de l'eau, lacs traversés compris. Sur la roche
        // nue, un écoulement peut donc franchir un seuil ; la propriété
        // vérifiable est qu'il descend sur le terrain « roche + comblement »,
        // qui est celui que l'eau voit.
        for (w in worlds) {
            val h = w.hydrology
            for (i in 0 until h.cellCount) {
                val r = h.receiver[i]
                if (r == i) continue
                val here = h.erodedM[i] + h.fillDepthM[i]
                val there = h.erodedM[r] + h.fillDepthM[r]
                assertTrue(
                    there < here + 1e-3f,
                    "${w.name} : la cellule $i s'écoule vers $r plus haut ($here vs $there)"
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
                    h.erodedM[cur] <= 0f,
                    "${w.name} : le chemin depuis $start s'arrête à ${h.erodedM[cur]} m sans atteindre la mer"
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
    fun `l erosion respecte son enveloppe par construction`() {
        // L'enveloppe (au plus 25 % de l'altitude locale et 500 m d'érosion,
        // 120 m de dépôt) est appliquée à chaque passe : ces bornes ne
        // dépendent d'aucun calibrage et doivent tenir exactement. Chercher
        // un coefficient qui « tombe juste » partout avec trois décades de
        // débit était le pari perdu de la v0.9.6.
        for (w in worlds) {
            val h = w.hydrology
            var eroded = 0
            for (i in 0 until h.cellCount) {
                val a = w.altitudeM[i]
                if (a <= 0f) continue
                val maxDrop = kotlin.math.min(
                    HydrologyField.MAX_EROSION_M, a * HydrologyField.MAX_EROSION_RATIO
                )
                val drop = a - h.erodedM[i]
                assertTrue(
                    drop <= maxDrop + 0.5f,
                    "${w.name} : abaissement de $drop m pour une enveloppe de $maxDrop m en $i"
                )
                assertTrue(
                    drop >= -HydrologyField.MAX_DEPOSIT_M - 0.5f,
                    "${w.name} : dépôt de ${-drop} m en $i"
                )
                if (drop > 1f) eroded++
            }
            // Garde-fou d'échantillon : une enveloppe est trivialement
            // respectée par une érosion débranchée. On exige qu'elle ait agi.
            assertTrue(eroded > 20, "${w.name} : érosion quasi nulle ($eroded cellules)")
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

/**
 * La clé de tri de l'hydrologie, testée pour elle-même.
 *
 * Le bug de la v0.9.6 tenait dans cette seule fonction : le décalage de 32
 * bits posait le bit de signe du Long et inversait l'ordre pour les
 * altitudes positives. Aucun test ne la regardait directement — seuls ses
 * effets lointains (débit) rougissaient, ce qui rend le diagnostic bien plus
 * long. Une primitive dont la justesse n'est pas évidente mérite son test.
 */
class HydrologyKeyTest {

    @Test
    fun `l ordre des cles suit l ordre des altitudes`() {
        val altitudes = floatArrayOf(-9000f, -1500f, -0.5f, 0f, 0.5f, 120f, 3400f, 8848f)
        var previous = Long.MIN_VALUE
        for ((i, a) in altitudes.withIndex()) {
            val key = HydrologyField.packKeyForTest(a, i)
            assertTrue(key > previous, "clé non croissante à l'altitude $a")
            previous = key
        }
    }

    @Test
    fun `l altitude se relit exactement`() {
        for (a in floatArrayOf(-6500f, -12.25f, 0f, 1f, 987.5f, 8848f)) {
            assertEquals(a, HydrologyField.unpackAltitudeForTest(HydrologyField.packKeyForTest(a, 42)), 0f)
        }
    }

    @Test
    fun `les ex aequo sont departages par indice`() {
        // C'est cette propriété qui rend le Dijkstra et le tri déterministes
        // d'une machine à l'autre.
        assertTrue(
            HydrologyField.packKeyForTest(100f, 7) < HydrologyField.packKeyForTest(100f, 9)
        )
    }
}
