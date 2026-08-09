package com.terra.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Météo locale — lot 2.15. La décision vit dans :sim, donc elle se teste :
 * seuils, bascule pluie/neige, budget de particules. Le rendu n'en est
 * que l'obéissance.
 */
class LocalWeatherTest {

    @Test
    fun `les deserts restent secs et la mer sans particules`() {
        assertEquals(
            LocalWeather.Form.NONE,
            LocalWeather.stateAt(120f, 25f, overOcean = false).form,
            "un désert à 120 mm/an ne doit rien recevoir"
        )
        assertEquals(
            LocalWeather.Form.NONE,
            LocalWeather.stateAt(3000f, 20f, overOcean = true).form,
            "pas de particules au-dessus de l'océan"
        )
        assertTrue(!LocalWeather.CLEAR.active)
    }

    @Test
    fun `la forme bascule a la temperature du moment`() {
        val wet = 1500f
        assertEquals(
            LocalWeather.Form.RAIN,
            LocalWeather.stateAt(wet, 18f, false).form, "l'été doit pleuvoir"
        )
        assertEquals(
            LocalWeather.Form.SNOW,
            LocalWeather.stateAt(wet, -8f, false).form, "l'hiver doit neiger"
        )
        // Juste de part et d'autre du seuil : la bascule est nette.
        assertEquals(
            LocalWeather.Form.SNOW,
            LocalWeather.stateAt(wet, LocalWeather.SNOW_TEMP_C - 0.1f, false).form
        )
        assertEquals(
            LocalWeather.Form.RAIN,
            LocalWeather.stateAt(wet, LocalWeather.SNOW_TEMP_C + 0.1f, false).form
        )
    }

    @Test
    fun `l intensite croit avec les precipitations et sature`() {
        val a = LocalWeather.stateAt(600f, 15f, false).intensity
        val b = LocalWeather.stateAt(1400f, 15f, false).intensity
        val c = LocalWeather.stateAt(5000f, 15f, false).intensity
        assertTrue(a in 0f..1f && b in 0f..1f && c in 0f..1f)
        assertTrue(b > a, "l'intensité doit croître avec la pluie annuelle")
        assertEquals(1f, c, "l'intensité doit saturer à 1")
        // Continuité au seuil sec : pas de saut visible à l'entrée.
        val justWet = LocalWeather.stateAt(
            LocalWeather.DRY_THRESHOLD_MM + 1f, 15f, false
        )
        assertTrue(justWet.intensity < 0.01f, "saut d'intensité au seuil sec")
    }

    @Test
    fun `le budget de particules est respecte et la neige plus clairsemee`() {
        val budget = 1400
        assertEquals(0, LocalWeather.particleCount(LocalWeather.CLEAR, budget))
        val rain = LocalWeather.State(LocalWeather.Form.RAIN, 1f)
        val snow = LocalWeather.State(LocalWeather.Form.SNOW, 1f)
        assertEquals(budget, LocalWeather.particleCount(rain, budget))
        val snowCount = LocalWeather.particleCount(snow, budget)
        assertTrue(snowCount in 1 until budget, "neige hors budget : $snowCount")
        assertTrue(
            LocalWeather.fallSpeedMS(LocalWeather.Form.SNOW) <
                LocalWeather.fallSpeedMS(LocalWeather.Form.RAIN),
            "un flocon tombe moins vite qu'une goutte"
        )
    }
}
