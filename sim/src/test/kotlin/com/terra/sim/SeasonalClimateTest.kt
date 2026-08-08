package com.terra.sim

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Saisons thermiques — lot 1.12.
 *
 * Les bornes viennent du calibrage (`validation/saisons_calibrage.py`) :
 * chaque station terrestre doit être reproduite à un facteur 2, l'erreur
 * médiane rester sous 30 % — le calibrage a mesuré 13 %, la marge est
 * réelle. Le calendrier (retard sur le solstice) est vérifié par rapport
 * au pic de déclinaison MESURÉ, pas à un jour codé en dur : la convention
 * de phase de WorldTime peut changer sans casser ce test.
 */
class SeasonalClimateTest {

    private val time = WorldTime()   // inclinaison terrestre, année de 360 jours

    private data class Station(
        val name: String, val latDeg: Float, val halfRangeC: Float,
        val cont: Float, val oceanSurface: Boolean
    )

    private val stations = listOf(
        Station("Singapour", 1.4f, 0.9f, 0.05f, false),
        Station("Manaus", 3.1f, 1.4f, 0.55f, false),
        Station("Lima", 12.0f, 3.7f, 0.05f, false),
        Station("Hong Kong", 22.3f, 6.7f, 0.30f, false),
        Station("Riyad", 24.6f, 10.8f, 0.60f, false),
        Station("Le Caire", 30.0f, 7.2f, 0.20f, false),
        Station("La Nouvelle-Orléans", 30.0f, 8.3f, 0.05f, false),
        Station("San Francisco", 37.8f, 4.4f, 0.02f, false),
        Station("Chicago", 41.9f, 14.3f, 0.45f, false),
        Station("Bordeaux", 44.8f, 7.5f, 0.08f, false),
        Station("Astana", 51.2f, 17.8f, 0.80f, false),
        Station("Bergen", 60.4f, 6.6f, 0.02f, false),
        Station("Iakoutsk", 62.0f, 29.4f, 0.90f, false),
        Station("Reykjavik", 64.1f, 5.6f, 0.02f, false),
        Station("Océan tropical", 10.0f, 1.0f, 0.00f, true),
        Station("Océan tempéré", 45.0f, 4.5f, 0.00f, true),
        Station("Océan subpolaire", 60.0f, 3.5f, 0.00f, true)
    )

    private fun sinLat(deg: Float) =
        kotlin.math.sin(deg * com.terra.core.DEG_TO_RAD)

    @Test
    fun `chaque station terrestre tient dans un facteur deux`() {
        val rels = ArrayList<Float>()
        for (st in stations) {
            val a = SeasonalClimate.amplitudeC(
                abs(sinLat(st.latDeg)), st.cont, st.oceanSurface,
                SeasonalClimate.REF_TILT_DEG
            )
            assertTrue(
                a in st.halfRangeC / 2f..st.halfRangeC * 2f,
                "${st.name} : ${st.halfRangeC} °C observés, $a modélisés"
            )
            rels.add(abs(a - st.halfRangeC) / st.halfRangeC)
        }
        rels.sort()
        assertTrue(
            rels[rels.size / 2] < 0.30f,
            "erreur médiane ${rels[rels.size / 2]} au-dessus des 30 % promis"
        )
    }

    @Test
    fun `l amplitude croit avec la continentalite et decroit en mer`() {
        val s60 = abs(sinLat(60f))
        val ocean = SeasonalClimate.amplitudeC(s60, 0f, true, 23.4f)
        val coast = SeasonalClimate.amplitudeC(s60, 0.05f, false, 23.4f)
        val interior = SeasonalClimate.amplitudeC(s60, 0.9f, false, 23.4f)
        assertTrue(ocean < coast, "la surface marine devrait varier moins que la côte")
        assertTrue(coast < interior, "la côte devrait varier moins que l'intérieur")
    }

    @Test
    fun `une planete droite n a pas de saisons`() {
        val droit = WorldTime(axialTiltDeg = 0f)
        for (d in 0 until 360 step 30) {
            val tick = d.toLong() * droit.minutesPerDay
            assertEquals(0f, SeasonalClimate.deltaC(0.7f, 0.8f, false, droit, tick))
        }
    }

    @Test
    fun `les hemispheres vivent des saisons opposees`() {
        for (d in intArrayOf(0, 45, 97, 180, 233, 300)) {
            val tick = d.toLong() * time.minutesPerDay
            val nord = SeasonalClimate.deltaC(0.71f, 0.5f, false, time, tick)
            val sud = SeasonalClimate.deltaC(-0.71f, 0.5f, false, time, tick)
            assertTrue(
                abs(nord + sud) < 1e-4f,
                "jour $d : nord $nord et sud $sud ne sont pas opposés"
            )
        }
    }

    @Test
    fun `la moyenne annuelle de la modulation est nulle`() {
        // La saison redistribue la chaleur dans l'année, elle n'en crée
        // pas : sinon la moyenne annuelle générée serait un mensonge.
        var sum = 0.0
        for (d in 0 until time.daysPerYear) {
            sum += SeasonalClimate.deltaC(
                0.8f, 0.9f, false, time, d.toLong() * time.minutesPerDay
            )
        }
        val mean = (sum / time.daysPerYear).toFloat()
        assertTrue(abs(mean) < 0.05f, "dérive annuelle de $mean °C")
    }

    @Test
    fun `le pic thermique retarde sur le solstice, plus en mer qu a terre`() {
        fun peakDay(f: (Long) -> Float): Int {
            var best = 0; var bestV = -Float.MAX_VALUE
            for (d in 0 until time.daysPerYear) {
                val v = f(d.toLong() * time.minutesPerDay)
                if (v > bestV) { bestV = v; best = d }
            }
            return best
        }
        val solstice = peakDay { time.sunDeclinationDeg(it) }
        val landPeak = peakDay { SeasonalClimate.deltaC(0.8f, 1f, false, time, it) }
        val seaPeak = peakDay { SeasonalClimate.deltaC(0.8f, 0f, true, time, it) }
        val landLag = ((landPeak - solstice) + time.daysPerYear) % time.daysPerYear
        val seaLag = ((seaPeak - solstice) + time.daysPerYear) % time.daysPerYear
        // Retards prescrits : 27 jours à terre, 55 en mer, à un jour
        // d'échantillonnage près.
        assertTrue(abs(landLag - 27) <= 1, "retard continental de $landLag jours")
        assertTrue(abs(seaLag - 55) <= 1, "retard marin de $seaLag jours")
    }

    @Test
    fun `le generateur conserve la continentalite`() {
        val w = WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4)).generate()
        assertEquals(w.vertexCount, w.continentality.size)
        var landMax = 0f
        for (i in 0 until w.vertexCount) {
            assertTrue(w.continentality[i] in 0f..1f, "continentalité hors bornes au sommet $i")
            if (w.altitudeM[i] < 0f) {
                assertEquals(0f, w.continentality[i], "une cellule marine doit être à zéro")
            } else if (w.continentality[i] > landMax) {
                landMax = w.continentality[i]
            }
        }
        // Une arête de niveau 4 couvre ~446 km : toute cellule à un pas du
        // littoral vaut déjà 446/1800 ≈ 0,25. Un continent en possède
        // nécessairement — seuil calculé, pas deviné.
        assertTrue(landMax > 0.2f, "aucun intérieur de continent détecté ($landMax)")
    }
}
