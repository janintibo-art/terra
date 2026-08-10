package com.terra.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lot 2.20-a — le nom de capture est pur, l'horodatage UTC le rend sûr. */
class CaptureNameTest {

    @Test
    fun horodatageUtcDeterministe() {
        // L'époque zéro est le 1er janvier 1970 à minuit UTC, partout.
        val n = CaptureName.build("Gaia", "0.42.0", null, 0L)
        assertEquals("terra-Gaia-v0.42.0-globe-19700101-000000.png", n)
    }

    @Test
    fun altitudeEnMetresPuisKilometres() {
        assertTrue(CaptureName.build("G", "1", 512.7, 0L).contains("-512m-"))
        assertTrue(CaptureName.build("G", "1", 9_999.0, 0L).contains("-9999m-"))
        assertTrue(CaptureName.build("G", "1", 10_000.0, 0L).contains("-10km-"))
        assertTrue(CaptureName.build("G", "1", 2_465_000.0, 0L).contains("-2465km-"))
    }

    @Test
    fun nomDeMondeAssaini() {
        // Espaces et ponctuation deviennent des tirets, rognés aux bords.
        val n = CaptureName.build("  L'Île Perdue! ", "1", null, 0L)
        assertTrue(n.startsWith("terra-L-Île-Perdue-v1-"), n)
        // Vide ou tout-ponctuation : repli sur « monde ».
        assertTrue(CaptureName.build("***", "1", null, 0L).startsWith("terra-monde-"))
        assertTrue(CaptureName.build("", "1", null, 0L).startsWith("terra-monde-"))
    }

    @Test
    fun horodatageArbitraire() {
        // 2026-08-10 14:30:05 UTC = 1 786 372 205 000 ms — vérifié en
        // Python, PAS calculé de tête : la première valeur posée était
        // fausse de quatre jours.
        val n = CaptureName.build("G", "1", null, 1_786_372_205_000L)
        assertTrue(n.endsWith("-20260810-143005.png"), n)
    }
}
