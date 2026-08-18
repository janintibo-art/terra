package com.terra.sim

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coloration du feuillage — lot 3.4.
 *
 * Tous les seuils de ce fichier viennent de `validation/couleur_feuillage.py`
 * et sont MESURÉS, avec une marge sous la valeur observée. Aucun n'est
 * estimé : trois échecs de lots antérieurs venaient de seuils devinés (LOD
 * v0.48, faux d'un tiers).
 *
 * Valeurs de l'instruction, pour mémoire :
 *   contraste été/automne, pire cas . . . 0,305
 *   contraste climatique, pire cas . . . . 0,088
 *   dispersion individuelle . . . . . . . 7,6 % de la moyenne
 *   étalement à mi-descente . . . . . . . 0,33 à 0,67
 *   composante maximale . . . . . . . . . 0,805
 */
class FoliageTintTest {

    /** Climats types de l'instruction : (T annuelle, pluie, latitude, continentalité). */
    private data class Climat(
        val nom: String, val tempC: Float, val precipMm: Float,
        val latDeg: Float, val cont: Float
    ) {
        val sinLat: Float get() = sin(Math.toRadians(latDeg.toDouble())).toFloat()
    }

    private val climats = listOf(
        Climat("tropicale humide", 26f, 2600f, 5f, 0.30f),
        Climat("savane", 25f, 700f, 12f, 0.60f),
        Climat("tempérée océanique", 12f, 1000f, 47f, 0.25f),
        Climat("tempérée continentale", 9f, 800f, 47f, 0.85f),
        Climat("steppe", 10f, 350f, 45f, 0.90f),
        Climat("boréale", 3f, 500f, 60f, 0.80f),
        Climat("toundra", -1f, 300f, 70f, 0.70f)
    )

    private fun couleur(
        species: TreeSpecies, c: Climat, localTempC: Float,
        saltPhase: Float = 0.5f, saltHue: Float = 0.5f
    ): FloatArray {
        val out = FloatArray(3)
        FoliageTint.color(species, c.tempC, c.precipMm, localTempC, saltPhase, saltHue, out)
        return out
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in 0..2) sum += (a[i] - b[i]) * (a[i] - b[i])
        return sqrt(sum)
    }

    private fun luminance(c: FloatArray, shade: Float = 1f): Float =
        (0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]) * shade

    // ------------------------------------------------------ sénescence

    @Test
    fun lesPersistantsNeRoussissentJamais() {
        for (species in TreeSpecies.values()) {
            if (!FoliageTint.isEvergreen(species)) continue
            val chaud = couleur(species, climats.last(), 25f)
            val glacial = couleur(species, climats.last(), -30f)
            assertEquals(
                0f, distance(chaud, glacial), 1e-6f,
                "${species.label} est persistant : sa couleur ne doit pas suivre la saison"
            )
        }
    }

    @Test
    fun laSenescenceCroitQuandLaTemperatureBaisse() {
        var precedent = -1f
        var t = 30f
        while (t >= -20f) {
            val s = FoliageTint.senescence(t, evergreen = false, saltPhase = 0.5f)
            assertTrue(s in 0f..1f, "sénescence hors bornes à $t °C : $s")
            assertTrue(
                s >= precedent - 1e-6f,
                "sénescence non monotone : $s à $t °C après $precedent"
            )
            precedent = s
            t -= 0.5f
        }
        // Les deux extrémités sont franches : plein été d'un côté, feuillage
        // entièrement passé de l'autre.
        assertEquals(0f, FoliageTint.senescence(30f, false, 0.5f), 1e-6f)
        assertEquals(1f, FoliageTint.senescence(-20f, false, 0.5f), 1e-6f)
    }

    /**
     * L'étalement se mesure à MI-DESCENTE et non au seuil : au seuil, la
     * définition de la rampe lisse veut que tout le monde soit encore vert,
     * et la première version de ce contrôle mesurait donc un étalement nul
     * sur un modèle parfaitement sain. Le test était faux, pas le code.
     */
    @Test
    fun lesIndividusNeRoussissentPasTousLeMemeJour() {
        val mi = FoliageTint.T_GREEN_C - FoliageTint.SENESCENCE_SPAN_C / 2f
        var mini = 1f
        var maxi = 0f
        for (i in 0 until 1000) {
            val s = FoliageTint.senescence(mi, false, i / 1000f)
            mini = minOf(mini, s)
            maxi = maxOf(maxi, s)
        }
        // Mesuré : 0,33 à 0,67, soit 0,34 d'étalement.
        assertTrue(
            maxi - mini > 0.15f,
            "étalement individuel de seulement ${maxi - mini} : la forêt roussirait d'un bloc"
        )
    }

    // ------------------------------------------------------ contrastes

    @Test
    fun leContrasteEteAutomneEstVisible() {
        val miDescente = FoliageTint.T_GREEN_C - FoliageTint.SENESCENCE_SPAN_C / 2f
        for (species in TreeSpecies.values()) {
            if (FoliageTint.isEvergreen(species)) continue
            for (c in climats) {
                val ete = couleur(species, c, 30f)
                val automne = couleur(species, c, miDescente)
                // Mesuré : 0,305 au pire (herbacée de savane).
                assertTrue(
                    distance(ete, automne) > 0.25f,
                    "${species.label} en ${c.nom} : contraste été/automne de " +
                        "${distance(ete, automne)}"
                )
            }
        }
    }

    /**
     * Deux climats opposés doivent donner deux verts distincts EN PLEIN ÉTÉ,
     * saison neutralisée. C'est ce contrôle qui a fait tomber la première
     * conception : l'aridité prise sur la pluie brute jaunissait la forêt
     * boréale autant que la steppe, ce jaunissement annulait presque
     * exactement l'assombrissement du froid, et boréale et tropicale se
     * retrouvaient à 0,061 l'une de l'autre — identiques à l'œil.
     */
    @Test
    fun deuxClimatsOpposesDonnentDeuxVerts() {
        val paires = listOf(
            "tropicale humide" to "boréale",
            "tropicale humide" to "steppe",
            "tempérée océanique" to "steppe"
        )
        for ((a, b) in paires) {
            val ca = climats.first { it.nom == a }
            val cb = climats.first { it.nom == b }
            val d = distance(couleur(TreeSpecies.FEUILLU, ca, 30f),
                couleur(TreeSpecies.FEUILLU, cb, 30f))
            // Mesuré : 0,088 au pire.
            assertTrue(d > 0.07f, "$a et $b se ressemblent trop en été : $d")
        }
    }

    @Test
    fun laForetBorealeEstPlusSombreQueLaTropicale() {
        val tropicale = couleur(TreeSpecies.FEUILLU, climats.first { it.nom == "tropicale humide" }, 30f)
        val boreale = couleur(TreeSpecies.FEUILLU, climats.first { it.nom == "boréale" }, 30f)
        assertTrue(
            luminance(boreale) < luminance(tropicale),
            "le froid doit assombrir : boréale ${luminance(boreale)} " +
                "contre tropicale ${luminance(tropicale)}"
        )
    }

    @Test
    fun leClimatSecJaunitLeFeuillage() {
        val humide = couleur(TreeSpecies.HERBACEE, climats.first { it.nom == "tempérée océanique" }, 30f)
        val sec = couleur(TreeSpecies.HERBACEE, climats.first { it.nom == "steppe" }, 30f)
        // Jaunir, c'est monter le rouge par rapport au vert.
        assertTrue(
            sec[0] / sec[1] > humide[0] / humide[1],
            "la steppe devrait être plus jaune : ${sec[0] / sec[1]} contre ${humide[0] / humide[1]}"
        )
    }

    // ------------------------------------------------------ bornes

    @Test
    fun toutesLesCouleursTiennentDansLeShader() {
        var pire = 0f
        var minLum = 1f
        for (species in TreeSpecies.values()) {
            for (c in climats) {
                var jour = 0
                while (jour < 360) {
                    val local = c.tempC + SeasonalClimate.deltaC(
                        c.sinLat, c.cont, false, WorldTime(), tickOfDay(jour)
                    )
                    for (sp in listOf(0f, 0.5f, 0.999f)) {
                        for (sh in listOf(0f, 0.5f, 0.999f)) {
                            val col = couleur(species, c, local, sp, sh)
                            for (x in col) {
                                assertTrue(x.isFinite(), "${species.label} : composante non finie")
                                assertTrue(x in 0f..1f, "${species.label} : composante $x")
                            }
                            for (ss in listOf(0f, 0.999f)) {
                                val shade = FoliageTint.shade(ss)
                                for (x in col) pire = maxOf(pire, x * shade)
                                minLum = minOf(minLum, luminance(col, shade))
                            }
                        }
                    }
                    jour += 5
                }
            }
        }
        // Le shader multiplie ensuite par l'éclairage, qui monte à 1,10 :
        // c'est ce produit qui doit rester sous 1, sans quoi les hautes
        // lumières s'écrêteraient canal par canal et vireraient de teinte.
        assertTrue(pire * 1.10f <= 1f, "saturation possible : $pire × 1,10")
        assertTrue(minLum > 0.10f, "feuillage trop sombre : luminance $minLum")
    }

    @Test
    fun laLuminositeIndividuelleResteDansSaPlage() {
        assertEquals(FoliageTint.SHADE_MIN, FoliageTint.shade(0f), 1e-6f)
        assertEquals(
            FoliageTint.SHADE_MIN + FoliageTint.SHADE_SPAN,
            FoliageTint.shade(1f), 1e-6f
        )
        // Les sels arrivent de micro01, borné par construction, mais une
        // valeur hors bornes ne doit pas produire un arbre fluorescent.
        assertEquals(FoliageTint.SHADE_MIN, FoliageTint.shade(-3f), 1e-6f)
        assertEquals(
            FoliageTint.SHADE_MIN + FoliageTint.SHADE_SPAN,
            FoliageTint.shade(7f), 1e-6f
        )
    }

    @Test
    fun laDispersionIndividuelleEstPerceptibleSansEtreTachetee() {
        val c = climats.first { it.nom == "tempérée océanique" }
        val lum = ArrayList<Float>(10000)
        for (i in 0 until 10000) {
            val ss = (i * 7919 % 10000) / 10000f
            val sh = (i * 5011 % 10000) / 10000f
            lum += luminance(couleur(TreeSpecies.FEUILLU, c, 30f, 0.5f, sh), FoliageTint.shade(ss))
        }
        val moy = lum.sum() / lum.size
        var v = 0f
        for (x in lum) v += (x - moy) * (x - moy)
        val ecart = sqrt(v / lum.size)
        // Mesuré : 7,6 % de la moyenne.
        assertTrue(ecart / moy > 0.03f, "variation imperceptible : ${ecart / moy}")
        assertTrue(ecart / moy < 0.12f, "forêt tachetée : ${ecart / moy}")
    }

    // ------------------------------------------------------ saison réelle

    private fun tickOfDay(day: Int): Long {
        val t = WorldTime()
        return (day.toDouble() * t.minutesPerDay / t.minutesPerTick).toLong()
    }

    /**
     * Le contrôle qui compte : sur une ANNÉE ENTIÈRE de temps simulé, avec
     * la vraie modulation saisonnière du lot 1.12, chaque climat passe la
     * bonne fraction de l'année en feuillage d'été.
     */
    @Test
    fun lesFractionsDAnneeSontCellesDeLInstruction() {
        val time = WorldTime()
        val mesures = HashMap<String, Triple<Int, Int, Int>>()
        for (c in climats) {
            var vert = 0
            var hiver = 0
            for (jour in 0 until 360) {
                val local = c.tempC +
                    SeasonalClimate.deltaC(c.sinLat, c.cont, false, time, tickOfDay(jour))
                val s = FoliageTint.senescence(local, false, 0.5f)
                if (s < 0.15f) vert++ else if (s > 0.70f) hiver++
            }
            mesures[c.nom] = Triple(vert, 360 - vert - hiver, hiver)
        }

        // Sous les tropiques, aucun automne : l'excursion thermique y est de
        // ±1,6 °C, très loin du seuil.
        assertEquals(360, mesures["tropicale humide"]!!.first,
            "la forêt tropicale doit rester verte toute l'année")
        // La savane non plus — et pour une raison qu'il faut connaître :
        // seule la TEMPÉRATURE est saisonnière dans Terra, pas la pluie. Une
        // savane qui jaunit en saison sèche demandera une précipitation
        // saisonnière, hors de ce lot.
        assertEquals(360, mesures["savane"]!!.first,
            "la savane ne roussit pas par le froid")
        val ocean = mesures["tempérée océanique"]!!.first / 360f
        assertTrue(ocean in 0.35f..0.75f, "forêt tempérée verte $ocean de l'année")
        assertTrue(mesures["tempérée continentale"]!!.third > 54,
            "un hiver continental doit ternir le feuillage")
        assertTrue(mesures["toundra"]!!.third > 144,
            "la toundra doit passer plus de 40 % de l'année en feuillage terne")
    }

    @Test
    fun lesHemispheresRoussissentEnOpposition() {
        val time = WorldTime()
        val tick = tickOfDay(300)
        val nord = FoliageTint.localTemperatureC(10f, 0.71f, 0.5f, time, tick)
        val sud = FoliageTint.localTemperatureC(10f, -0.71f, 0.5f, time, tick)
        assertTrue(
            abs((nord - 10f) + (sud - 10f)) < 1e-4f,
            "les écarts saisonniers des deux hémisphères doivent s'opposer : $nord et $sud"
        )
    }

    @Test
    fun unePlaneteSansInclinaisonNaPasDAutomne() {
        val droit = WorldTime(axialTiltDeg = 0f)
        for (jour in 0 until 360 step 15) {
            val local = FoliageTint.localTemperatureC(12f, 0.71f, 0.6f, droit, tickOfDay(jour))
            assertEquals(12f, local, 1e-4f, "jour $jour sur une planète droite")
        }
    }

    // ------------------------------------------------------ déterminisme

    @Test
    fun memesEntreesMemeCouleur() {
        val a = FloatArray(3)
        val b = FloatArray(3)
        FoliageTint.color(TreeSpecies.FEUILLU, 11f, 900f, 6f, 0.31f, 0.72f, a)
        FoliageTint.color(TreeSpecies.FEUILLU, 11f, 900f, 6f, 0.31f, 0.72f, b)
        for (i in 0..2) assertEquals(a[i], b[i], 0f, "composante $i")
    }

    @Test
    fun leTableauDeSortiePeutEtreReutilise() {
        // Le rendu réutilise un unique tableau pour les 264 arbres : une
        // sortie qui dépendrait de son contenu antérieur donnerait des
        // couleurs différentes selon l'ordre de dessin.
        val out = FloatArray(4)
        out[0] = 0.9f; out[1] = 0.9f; out[2] = 0.9f
        val neuf = FloatArray(3)
        FoliageTint.color(TreeSpecies.ARBUSTE, 5f, 600f, 2f, 0.2f, 0.8f, out)
        FoliageTint.color(TreeSpecies.ARBUSTE, 5f, 600f, 2f, 0.2f, 0.8f, neuf)
        for (i in 0..2) assertEquals(neuf[i], out[i], 0f, "composante $i polluée")
    }
}
