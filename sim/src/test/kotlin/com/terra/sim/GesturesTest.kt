package com.terra.sim

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Conventions de gestes, fixées par l'essai sur appareil.
 *
 * Ces tests ne prouvent pas que la convention est « la bonne » — aucun test
 * JVM ne le peut, seul un doigt sur l'écran tranche. Ils la **figent** : si
 * un refactor retourne un signe, ils rougissent, et la correction se refait
 * consciemment plutôt que de ressurgir en rapport de bug.
 */
class GesturesTest {

    private fun camera() = PlanetCamera(
        6_371_000.0,
        focusLatRad = 0.2,
        focusLonRad = 0.4,
        rangeM = 10_000.0
    )

    @Test
    fun `glisser vers le bas fait venir le terrain du haut de l ecran`() {
        // Convention carte, mesurée sur appareil (v0.8.4) : le terrain suit le
        // doigt. Doigt vers le bas (dy > 0 en coordonnées écran Android), cap
        // nul : le point visé recule vers le sud local — la latitude diminue.
        val cam = camera()
        val before = cam.focusLatRad
        cam.pan(0.0, 300.0, 2000.0)
        assertTrue(
            cam.focusLatRad < before,
            "dy > 0 doit réduire la latitude (cap nul) : ${cam.focusLatRad} vs $before"
        )
    }

    @Test
    fun `glisser vers la droite fait venir le terrain de droite`() {
        // L'axe horizontal était correct dès la première version : ce test
        // l'empêche de se dérégler pendant qu'on corrige l'autre.
        val cam = camera()
        val before = cam.focusLonRad
        cam.pan(300.0, 0.0, 2000.0)
        assertTrue(
            cam.focusLonRad < before,
            "dx > 0 doit réduire la longitude (cap nul) : ${cam.focusLonRad} vs $before"
        )
    }

    @Test
    fun `les deux axes se compensent par un aller-retour`() {
        // Quatre glissements en carré ramènent près du départ : détecte une
        // asymétrie d'échelle entre axes que les deux tests de signe ne
        // verraient pas.
        val cam = camera()
        val lat0 = cam.focusLatRad
        val lon0 = cam.focusLonRad
        cam.pan(250.0, 0.0, 2000.0)
        cam.pan(0.0, 250.0, 2000.0)
        cam.pan(-250.0, 0.0, 2000.0)
        cam.pan(0.0, -250.0, 2000.0)
        assertTrue(
            kotlin.math.abs(cam.focusLatRad - lat0) < 1e-5 &&
                    kotlin.math.abs(cam.focusLonRad - lon0) < 1e-5,
            "l'aller-retour ne revient pas au départ"
        )
    }
}

/**
 * Arithmétique de parenté sur clés compactées — le socle du repli sur
 * l'ancêtre. Testée face à la version objet : les deux écritures doivent
 * coïncider sur toute la hiérarchie, sinon les trous de couverture (v0.8.6)
 * reviendraient sans qu'aucun test ne rougisse.
 */
class TileKeyTest {

    @Test
    fun `parentKey coincide avec la version objet sur toute la hierarchie`() {
        val rng = kotlin.random.Random(13)
        repeat(2000) {
            val level = rng.nextInt(0, TileId.MAX_LEVEL + 1)
            val grid = 1 shl level
            val tile = TileId(rng.nextInt(6), level, rng.nextInt(grid), rng.nextInt(grid))
            val fromKey = TileId.parentKey(tile.packed())
            val fromObject = tile.parent?.packed() ?: -1L
            kotlin.test.assertEquals(fromObject, fromKey, "divergence pour $tile")
        }
    }

    @Test
    fun `la remontee atteint la racine puis s arrete`() {
        var key = TileId(3, TileId.MAX_LEVEL, 1234, 4321).packed()
        var steps = 0
        while (key != -1L) {
            key = TileId.parentKey(key)
            steps++
            assertTrue(steps <= TileId.MAX_LEVEL + 1, "remontée sans fin")
        }
        kotlin.test.assertEquals(TileId.MAX_LEVEL + 1, steps)
    }
}
