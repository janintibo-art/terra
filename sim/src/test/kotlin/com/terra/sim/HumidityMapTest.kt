package com.terra.sim

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Carte d'humidité — lot 2.14 b. C'est elle qui fait enfin correspondre les
 * nuages aux pluies simulées ; les tests vérifient que la projection est
 * fidèle et que le contraste désert/forêt survit à l'échantillonnage.
 */
class HumidityMapTest {

    companion object {
        private val world by lazy {
            WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4)).generate()
        }
        private val map by lazy { HumidityMap.build(world) }
    }

    @Test
    fun `la carte a la bonne taille et reste dans ses bornes`() {
        assertEquals(HumidityMap.WIDTH * HumidityMap.HEIGHT, map.size)
        // Puissances de deux : condition du filtrage linéaire sur GLES2.
        assertEquals(0, HumidityMap.WIDTH and (HumidityMap.WIDTH - 1))
        assertEquals(0, HumidityMap.HEIGHT and (HumidityMap.HEIGHT - 1))
        var min = 1f
        var max = 0f
        for (b in map) {
            val v = (b.toInt() and 0xFF) / 255f
            if (v < min) min = v
            if (v > max) max = v
        }
        assertTrue(min >= HumidityMap.MIN_COVER - 0.01f, "couverture sous le plancher : $min")
        assertTrue(max <= 1.01f, "couverture au-dessus du plafond : $max")
        // Un monde réel doit présenter du contraste : une carte uniforme
        // signifierait que la projection ou la normalisation est morte.
        assertTrue(max - min > 0.25f, "carte sans contraste : $min à $max")
    }

    @Test
    fun `la couverture suit les precipitations de la cellule visee`() {
        // Fidélité de la projection : pour un échantillon de cellules de la
        // grille de simulation, la carte relue dans leur direction doit
        // donner la couverture attendue de LEUR pluie. Tolérance d'une
        // demi-cellule de carte, l'erreur d'échantillonnage inévitable.
        var checked = 0
        var worst = 0f
        for (i in 0 until world.vertexCount step 37) {
            val v = world.position(i)
            val expected = (HumidityMap.MIN_COVER + (1f - HumidityMap.MIN_COVER) *
                ((world.precipMm[i] - HumidityMap.DRY_MM) /
                    (HumidityMap.WET_MM - HumidityMap.DRY_MM)).coerceIn(0f, 1f))
            val got = HumidityMap.coverAt(map, v)
            val gap = abs(got - expected)
            if (gap > worst) worst = gap
            checked++
        }
        assertTrue(checked > 100, "échantillon trop petit : $checked")
        // La carte a 256×128 cellules pour 10 242 sommets : elle est PLUS
        // fine que la grille, l'écart ne vient que du plus proche voisin aux
        // frontières de cellules. 0,35 laisse la marge sans tolérer une
        // projection fausse (qui donnerait des écarts proches de 1).
        assertTrue(worst < 0.35f, "projection infidèle : écart maximal $worst")
    }

    @Test
    fun `les deserts sont plus secs que les forets, en moyenne`() {
        // Contraste stratifié : on compare les cellules les plus sèches aux
        // plus humides du monde, via la carte. Le rapport doit être franc,
        // sinon le ciel serait uniforme malgré la simulation.
        val values = ArrayList<Pair<Float, Float>>()   // (pluie, couverture)
        for (i in 0 until world.vertexCount step 7) {
            values.add(Pair(world.precipMm[i], HumidityMap.coverAt(map, world.position(i))))
        }
        val sorted = values.sortedBy { it.first }
        val q = sorted.size / 4
        val dryCover = sorted.take(q).map { it.second }.average()
        val wetCover = sorted.takeLast(q).map { it.second }.average()
        assertTrue(
            wetCover > dryCover * 1.5,
            "contraste insuffisant : sec $dryCover, humide $wetCover"
        )
    }
}
