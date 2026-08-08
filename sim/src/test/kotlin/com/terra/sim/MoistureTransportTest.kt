package com.terra.sim

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Transport d'humidité — lot 1.14.
 *
 * Les propriétés à grande échelle (ITCZ, creux subtropical, ombre
 * pluviométrique) sont vérifiées sur des statistiques CUMULÉES de trois
 * mondes — la leçon de la v0.15.2 : un contraste physique se teste sur des
 * populations stratifiées, jamais sur des cellules individuelles. Les
 * seuils sont dérivés du calibrage (`validation/humidite_calibrage.py` :
 * ITCZ ×6,5, ombre ×2,0 sur banc synthétique), divisés par la marge de la
 * géographie réelle.
 */
class MoistureTransportTest {

    companion object {
        private val worlds by lazy {
            listOf("Gaia", "Alpha", "Kaleth").map {
                WorldGenerator.fromName(it, PlanetParams(subdivisions = 4)).generate()
            }
        }
    }

    @Test
    fun `les bandes verticales des trois cellules sont en place`() {
        // Fonctions pures : ascendance équatoriale, subsidence des latitudes
        // des chevaux, ascendance du front polaire, valeurs du calibrage.
        assertTrue(MoistureTransport.wVertical(0f) > 0.8f)
        assertTrue(MoistureTransport.wVertical(28f) < -0.7f)
        assertTrue(MoistureTransport.wVertical(60f) > 0.3f)
        assertTrue(MoistureTransport.rainFactor(0f) > 2.0f)
        assertTrue(MoistureTransport.rainFactor(28f) < 0.5f)
        assertTrue(MoistureTransport.ventFactor(28f) < 0.75f)
        assertEquals(1f, MoistureTransport.ventFactor(0f), "pas de ventilation à l'ascendance")
    }

    @Test
    fun `l equateur pleut plus que les subtropiques, sur l ocean`() {
        // Bandes océaniques cumulées des trois mondes : la géographie des
        // continents ne peut pas masquer la circulation.
        var eqSum = 0.0; var eqN = 0
        var stSum = 0.0; var stN = 0
        for (w in worlds) {
            for (i in 0 until w.vertexCount) {
                if (w.altitudeM[i] >= 0f) continue
                val s = abs(w.position(i).y)
                when {
                    s < 0.14f -> { eqSum += w.precipMm[i]; eqN++ }        // < 8°
                    s in 0.34f..0.50f -> { stSum += w.precipMm[i]; stN++ } // 20-30°
                }
            }
        }
        assertTrue(eqN > 50 && stN > 50, "bandes trop peu peuplées : $eqN / $stN")
        val ratio = (eqSum / eqN) / (stSum / stN)
        assertTrue(
            ratio > 1.6,
            "ITCZ absente : équateur/subtropiques = $ratio (calibrage : 6,5 sur banc)"
        )
    }

    @Test
    fun `la pente au vent est plus arrosee que la descente sous le vent`() {
        // Pour chaque cellule terrestre, la voisine la mieux alignée avec
        // l'amont du vent donne le sens de la pente traversée : montée
        // (> 400 m) contre descente (< −400 m), cumulées sur trois mondes.
        var upSum = 0.0; var upN = 0
        var downSum = 0.0; var downN = 0
        for (w in worlds) {
            val adjacency = w.sphere.buildAdjacency()
            for (i in 0 until w.vertexCount) {
                if (w.altitudeM[i] <= 0f) continue
                val u = upwindNeighbor(w, adjacency, i) ?: continue
                val d = w.altitudeM[i] - w.altitudeM[u]
                if (d > 400f) { upSum += w.precipMm[i]; upN++ }
                else if (d < -400f) { downSum += w.precipMm[i]; downN++ }
            }
        }
        assertTrue(upN > 30 && downN > 30, "trop peu de versants : $upN / $downN")
        val ratio = (upSum / upN) / (downSum / downN)
        assertTrue(
            ratio > 1.25,
            "ombre pluviométrique absente : au vent/sous le vent = $ratio " +
                "(calibrage : 2,0 sur banc synthétique)"
        )
    }

    @Test
    fun `le budget planetaire est conserve et les bornes tenues`() {
        for (w in worlds) {
            var mean = 0.0
            for (i in 0 until w.vertexCount) {
                val p = w.precipMm[i]
                assertTrue(p in 0f..w.params.maxPrecipMm, "pluie hors bornes au sommet $i")
                assertTrue(p.isFinite())
                mean += p
            }
            mean /= w.vertexCount
            // Le budget vient des bandes du lot 1.3 (moyenne ~0,35-0,50 de
            // maxPrecip) ; l'écrêtage au plafond peut le rogner un peu.
            assertTrue(
                mean in 0.20 * w.params.maxPrecipMm..0.60 * w.params.maxPrecipMm,
                "${w.name} : budget planétaire de $mean mm/an improbable"
            )
        }
    }

    @Test
    fun `le transport est deterministe`() {
        val again = WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4)).generate()
        assertTrue(worlds[0].precipMm.contentEquals(again.precipMm))
    }

    /** Voisine la mieux alignée avec l'amont du vent — miroir du transport. */
    private fun upwindNeighbor(w: PlanetData, adjacency: Array<IntArray>, i: Int): Int? {
        val v = w.sphere.vertices[i]
        val we = w.windEastMS[i]; val wn = w.windNorthMS[i]
        val wl = sqrt(we * we + wn * wn)
        if (wl < 1e-4f) return null
        val ex = -v.z; val ez = v.x
        val el = sqrt(ex * ex + ez * ez)
        if (el < 1e-4f) return null
        var best = -1; var bestAlign = -2f
        for (j in adjacency[i]) {
            val o = w.sphere.vertices[j]
            val radial = v.x * o.x + v.y * o.y + v.z * o.z
            val tx = v.x - o.x * radial
            val ty = v.y - o.y * radial
            val tz = v.z - o.z * radial
            val east = (tx * ex + tz * ez) / el
            val north = (tx * (-v.y * v.x) + ty * (v.x * v.x + v.z * v.z) +
                tz * (-v.y * v.z)) / el
            val align = (east * we + north * wn) / wl
            if (align > bestAlign) { bestAlign = align; best = j }
        }
        return if (best >= 0) best else null
    }
}
