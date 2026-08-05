package com.terra.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleCommandTest {

    @Test
    fun `teleportation complete`() {
        val c = ConsoleCommand.parse("tp 45.5 -73.6 500")
        assertTrue(c is ConsoleCommand.Teleport)
        assertEquals(45.5, c.latDeg, 1e-9)
        assertEquals(-73.6, c.lonDeg, 1e-9)
        assertEquals(500.0, c.rangeM!!, 1e-9)
    }

    @Test
    fun `teleportation sans portee`() {
        val c = ConsoleCommand.parse("tp -12 170")
        assertTrue(c is ConsoleCommand.Teleport)
        assertEquals(null, c.rangeM)
    }

    @Test
    fun `virgule decimale et suffixe kilometrique`() {
        // Clavier français : « 45,5 » doit passer, et « 80k » se lire 80 000 m.
        val c = ConsoleCommand.parse("tp 45,5 2,3 80k")
        assertTrue(c is ConsoleCommand.Teleport)
        assertEquals(45.5, c.latDeg, 1e-9)
        assertEquals(80_000.0, c.rangeM!!, 1e-9)

        val km = ConsoleCommand.parse("tp 0 0 1,5km")
        assertTrue(km is ConsoleCommand.Teleport)
        assertEquals(1_500.0, km.rangeM!!, 1e-9)
    }

    @Test
    fun `latitude hors bornes rejetee`() {
        assertTrue(ConsoleCommand.parse("tp 91 0") is ConsoleCommand.Invalid)
        assertTrue(ConsoleCommand.parse("tp 0 181") is ConsoleCommand.Invalid)
        assertTrue(ConsoleCommand.parse("tp abc 0") is ConsoleCommand.Invalid)
        assertTrue(ConsoleCommand.parse("tp 0 0 -5") is ConsoleCommand.Invalid)
    }

    @Test
    fun `changement de monde avec nom compose`() {
        val c = ConsoleCommand.parse("monde Kaleth du Nord")
        assertTrue(c is ConsoleCommand.LoadWorld)
        assertEquals("Kaleth du Nord", c.name)
    }

    @Test
    fun `bascule de mode`() {
        val sol = ConsoleCommand.parse("mode sol")
        assertTrue(sol is ConsoleCommand.SetMode && sol.descent)
        val globe = ConsoleCommand.parse("MODE globe")
        assertTrue(globe is ConsoleCommand.SetMode && !globe.descent)
        assertTrue(ConsoleCommand.parse("mode avion") is ConsoleCommand.Invalid)
    }

    @Test
    fun `aide et commandes inconnues`() {
        assertTrue(ConsoleCommand.parse("aide") is ConsoleCommand.Help)
        assertTrue(ConsoleCommand.parse("?") is ConsoleCommand.Help)
        assertTrue(ConsoleCommand.parse("") is ConsoleCommand.Invalid)
        assertTrue(ConsoleCommand.parse("frobnique 3") is ConsoleCommand.Invalid)
    }
}
