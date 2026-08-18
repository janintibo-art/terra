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
    fun `commande arbre`() {
        val d = ConsoleCommand.parse("arbre")
        assertTrue(d is ConsoleCommand.ShowTree)
        assertEquals(TreeSpecies.FEUILLU, d.species)
        assertEquals(1L, d.seed)

        // Les arguments se reconnaissent par leur FORME, dans n'importe
        // quel ordre : un nombre est une graine, un mot une espèce.
        val g = ConsoleCommand.parse("arbre 42")
        assertTrue(g is ConsoleCommand.ShowTree)
        assertEquals(42L, g.seed)
        val sp = ConsoleCommand.parse("arbre conifère")
        assertTrue(sp is ConsoleCommand.ShowTree)
        assertEquals(TreeSpecies.CONIFERE, sp.species)
        val both = ConsoleCommand.parse("arbre palmier 7")
        assertTrue(both is ConsoleCommand.ShowTree)
        assertEquals(TreeSpecies.PALMIER, both.species)
        assertEquals(7L, both.seed)
        val reversed = ConsoleCommand.parse("arbre 7 palmier")
        assertTrue(reversed is ConsoleCommand.ShowTree)
        assertEquals(TreeSpecies.PALMIER, reversed.species)
        assertEquals(7L, reversed.seed)

        val lod = ConsoleCommand.parse("arbre conifère bas 3")
        assertTrue(lod is ConsoleCommand.ShowTree)
        assertEquals(TreeSpecies.CONIFERE, lod.species)
        assertEquals(TreeDetail.LOW, lod.detail)
        assertEquals(3L, lod.seed)
        assertEquals(TreeDetail.FULL, (ConsoleCommand.parse("arbre") as ConsoleCommand.ShowTree).detail)

        assertTrue(ConsoleCommand.parse("arbre off") is ConsoleCommand.HideTree)
        assertTrue(ConsoleCommand.parse("arbre chêne") is ConsoleCommand.Invalid)
    }

    @Test
    fun `commande foret`() {
        val d = ConsoleCommand.parse("foret")
        assertTrue(d is ConsoleCommand.BuildForest)
        assertEquals(400.0, d.radiusM)
        val r = ConsoleCommand.parse("forêt 800")
        assertTrue(r is ConsoleCommand.BuildForest)
        assertEquals(800.0, r.radiusM)
        assertTrue(ConsoleCommand.parse("foret off") is ConsoleCommand.HideForest)
        assertTrue(ConsoleCommand.parse("foret loin") is ConsoleCommand.Invalid)
    }

    @Test
    fun `commande flore`() {
        assertTrue(ConsoleCommand.parse("flore") is ConsoleCommand.ShowFlora)
        assertTrue(ConsoleCommand.parse("  FLORE  ") is ConsoleCommand.ShowFlora)
    }

    @Test
    fun `commande photo`() {
        assertTrue(ConsoleCommand.parse("photo") is ConsoleCommand.TakePhoto)
        assertTrue(ConsoleCommand.parse("  PHOTO  ") is ConsoleCommand.TakePhoto)
    }

    @Test
    fun `commande banc limbe`() {
        val d = ConsoleCommand.parse("banc limbe")
        assertTrue(d is ConsoleCommand.BenchLimb)
        assertEquals(12_000.0, d.altitudeKm)
        val a = ConsoleCommand.parse("banc limbe 5000")
        assertTrue(a is ConsoleCommand.BenchLimb)
        assertEquals(5_000.0, a.altitudeKm)
        // Sous la fenêtre de visibilité (le disque ne tiendrait pas au
        // champ), ou au-delà de la portée maximale : rejeté.
        assertTrue(ConsoleCommand.parse("banc limbe 1000") is ConsoleCommand.Invalid)
        assertTrue(ConsoleCommand.parse("banc limbe 90000") is ConsoleCommand.Invalid)
        assertTrue(ConsoleCommand.parse("banc") is ConsoleCommand.Invalid)
    }

    @Test
    fun `commande limbe`() {
        val g = ConsoleCommand.parse("limbe globe")
        assertTrue(g is ConsoleCommand.SetLimbMode)
        assertEquals(1, g.mode)
        val c = ConsoleCommand.parse("limbe collerette")
        assertTrue(c is ConsoleCommand.SetLimbMode)
        assertEquals(2, c.mode)
        val t = ConsoleCommand.parse("LIMBE Tuiles")
        assertTrue(t is ConsoleCommand.SetLimbMode)
        assertEquals(0, t.mode)
        val auto = ConsoleCommand.parse("limbe auto")
        assertTrue(auto is ConsoleCommand.SetLimbMode)
        assertEquals(3, auto.mode)
        assertTrue(ConsoleCommand.parse("limbe") is ConsoleCommand.Invalid)
        assertTrue(ConsoleCommand.parse("limbe rond") is ConsoleCommand.Invalid)
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
    fun `commande soleil`() {
        val c = ConsoleCommand.parse("soleil 12")
        assertTrue(c is ConsoleCommand.SetLocalHour)
        assertEquals(12.0, c.hour, 1e-9)

        val virgule = ConsoleCommand.parse("soleil 6,5")
        assertTrue(virgule is ConsoleCommand.SetLocalHour)
        assertEquals(6.5, virgule.hour, 1e-9)

        assertTrue(ConsoleCommand.parse("soleil") is ConsoleCommand.Invalid)
        assertTrue(ConsoleCommand.parse("soleil 24") is ConsoleCommand.Invalid)
        assertTrue(ConsoleCommand.parse("soleil -1") is ConsoleCommand.Invalid)
    }

    @Test
    fun `aide et commandes inconnues`() {
        assertTrue(ConsoleCommand.parse("aide") is ConsoleCommand.Help)
        assertTrue(ConsoleCommand.parse("?") is ConsoleCommand.Help)
        assertTrue(ConsoleCommand.parse("") is ConsoleCommand.Invalid)
        assertTrue(ConsoleCommand.parse("frobnique 3") is ConsoleCommand.Invalid)
    }
    @Test
    fun `teinte se bascule, s active et se desactive`() {
        assertEquals(
            ConsoleCommand.SetLevelTint(null), ConsoleCommand.parse("teinte")
        )
        assertEquals(
            ConsoleCommand.SetLevelTint(true), ConsoleCommand.parse("teinte on")
        )
        assertEquals(
            ConsoleCommand.SetLevelTint(false), ConsoleCommand.parse("TEINTE off")
        )
        assertTrue(ConsoleCommand.parse("teinte bleu") is ConsoleCommand.Invalid)
        assertTrue(ConsoleCommand.HELP_TEXT.contains("teinte"))
    }


    // ------------------------------------------------ saison (lot 3.4)

    @Test
    fun `saison par nom`() {
        for ((mot, jour) in listOf(
            "printemps" to ConsoleCommand.DAY_SPRING,
            "été" to ConsoleCommand.DAY_SUMMER,
            "ete" to ConsoleCommand.DAY_SUMMER,
            "automne" to ConsoleCommand.DAY_AUTUMN,
            "hiver" to ConsoleCommand.DAY_WINTER
        )) {
            val c = ConsoleCommand.parse("saison $mot")
            assertTrue(c is ConsoleCommand.SetSeasonDay, "« $mot » non reconnu : $c")
            assertEquals(jour, c.dayOfYear, mot)
        }
    }

    @Test
    fun `saison par jour`() {
        val c = ConsoleCommand.parse("saison 220")
        assertTrue(c is ConsoleCommand.SetSeasonDay)
        assertEquals(220, c.dayOfYear)
    }

    @Test
    fun `saison invalide`() {
        assertTrue(ConsoleCommand.parse("saison") is ConsoleCommand.Invalid)
        assertTrue(ConsoleCommand.parse("saison mardi") is ConsoleCommand.Invalid)
        assertTrue(ConsoleCommand.parse("saison 0") is ConsoleCommand.Invalid)
        assertTrue(ConsoleCommand.parse("saison -5") is ConsoleCommand.Invalid)
    }
}
