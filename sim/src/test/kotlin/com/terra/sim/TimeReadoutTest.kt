package com.terra.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lecture du temps affichée au HUD — lot v0.37.0.
 *
 * Ces chiffres sont montrés à l'utilisateur pour expliquer un comportement
 * qui, sans eux, paraît incohérent : le même bouton ×200 fait passer un jour
 * planétaire en 0,2 s depuis l'orbite et en 12 min au sol. Un chiffre faux
 * serait pire que pas de chiffre du tout — d'où ces tests.
 */
class TimeReadoutTest {

    private val time = WorldTime()
    private val step = 1f / 30f

    @Test
    fun `un jour dure quarante-huit secondes en orbite a vitesse un`() {
        // Le chiffre annoncé dans la documentation du projet, vérifié :
        // 1 440 minutes par jour, 1 minute par tick, 1/30 s par tick.
        val d = TimeReadout.dayRealSeconds(time, 1f, 1.0, step)
        assertEquals(48.0, d, 0.001)
    }

    @Test
    fun `la dilatation de descente allonge le jour d autant`() {
        // Plancher réel de PlanetCamera : 1/2880 au ras du sol.
        val sol = TimeReadout.dayRealSeconds(time, 200f, PlanetCamera.MIN_TIME_DILATION, step)
        val orbite = TimeReadout.dayRealSeconds(time, 200f, 1.0, step)
        assertEquals(2880.0, sol / orbite, 1e-6)
        // Et le chiffre que verra l'utilisateur : ~12 minutes.
        assertTrue(sol in 600.0..800.0, "jour au sol à ×200 : $sol s")
    }

    @Test
    fun `la pause ne produit pas de duree absurde`() {
        val d = TimeReadout.dayRealSeconds(time, 0f, 1.0, step)
        assertTrue(d.isInfinite())
        assertEquals("—", TimeReadout.formatDuration(d))
        assertEquals("pause", TimeReadout.formatScale(0.0))
    }

    @Test
    fun `les durees changent d unite aux bons seuils`() {
        assertEquals("48,0 s", TimeReadout.formatDuration(48.0))
        assertEquals("2 min", TimeReadout.formatDuration(120.0))
        assertEquals("1,5 h", TimeReadout.formatDuration(5_400.0))
    }

    @Test
    fun `une vitesse sous un s ecrit en fraction`() {
        // « ×1/14 » se lit mieux que « ×0,07 » quand on cherche à comprendre
        // pourquoi le soleil ne bouge pas.
        assertEquals("×1/14", TimeReadout.formatScale(200.0 * PlanetCamera.MIN_TIME_DILATION))
        assertEquals("×200", TimeReadout.formatScale(200.0))
        assertEquals("×1,0", TimeReadout.formatScale(1.0))
    }

    @Test
    fun `la vitesse effective est bien le produit`() {
        assertEquals(20.0, TimeReadout.effectiveScale(200f, 0.1), 1e-9)
        assertEquals(0.0, TimeReadout.effectiveScale(0f, 1.0), 1e-9)
    }
}
