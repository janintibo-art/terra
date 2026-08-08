package com.terra.sim

import com.terra.core.Sphere
import com.terra.core.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Courants océaniques — lot 1.15.
 *
 * L'effet recherché est un **contraste** entre façades, pas un réchauffement
 * global. Les tests portent donc d'abord sur la neutralité planétaire — un
 * courant déplace de la chaleur, il n'en crée pas — puis sur l'asymétrie
 * est/ouest qui en est la signature.
 */
class OceanCurrentTest {

    companion object {
        private val worlds: List<PlanetData> by lazy {
            listOf("Kaleth", "Ormun", "Vessiane").map {
                WorldGenerator.fromName(it, PlanetParams(subdivisions = 4)).generate()
            }
        }
    }

    /**
     * Composante est de la direction vers la mer la plus proche, et distance
     * à cette mer en kilomètres. La distance permet de ne retenir que les
     * sommets CÔTIERS, seuls porteurs du signal des courants — la leçon du
     * run rouge du 08/08 : plus loin, reach ≈ 0 et il ne reste que du bruit.
     */
    private fun seawardEast(w: PlanetData, i: Int): Pair<Float, Float> {
        var best = -1
        var bestDot = -2f
        val v = w.sphere.vertices[i]
        for (j in 0 until w.vertexCount) {
            if (w.altitudeM[j] >= 0f) continue
            val o = w.sphere.vertices[j]
            val d = v.x * o.x + v.y * o.y + v.z * o.z
            if (d > bestDot) { bestDot = d; best = j }
        }
        if (best < 0) return Pair(0f, Float.MAX_VALUE)
        val distKm = kotlin.math.acos(bestDot.coerceIn(-1f, 1f)) *
            w.params.radiusM / 1000f
        val o = w.sphere.vertices[best]
        val radial = v.x * o.x + v.y * o.y + v.z * o.z
        val tx = o.x - v.x * radial
        val ty = o.y - v.y * radial
        val tz = o.z - v.z * radial
        val tl = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz)
        val ex = -v.z
        val ez = v.x
        val el = kotlin.math.sqrt(ex * ex + ez * ez)
        if (tl < 1e-6f || el < 1e-4f) return Pair(0f, distKm)
        return Pair((tx * ex + tz * ez) / (tl * el), distKm)
    }

    /**
     * Contraste thermique ouest − est, mesuré comme le run rouge du 08/08 a
     * appris à le mesurer :
     *
     * 1. sommets CÔTIERS (< 500 km) et BAS (< 500 m) uniquement — c'est là
     *    que vit l'effet, `reach` s'éteignant en exp(−d/450)·exp(−alt/1500) ;
     * 2. STRATIFIÉ par sous-bandes de latitude — dans une bande de 15°, le
     *    profil latitudinal pèse jusqu'à 14 °C, cent fois le signal si les
     *    façades est et ouest ne se répartissent pas pareil en latitude ;
     * 3. CUMULÉ sur plusieurs mondes — un monde seul peut manquer de côtes.
     *
     * Monte Carlo sur 20 000 tirages (validation/test_inversion_fiabilite.py) :
     * la comparaison naïve des moyennes échouait par hasard sur 27 % des
     * mondes ; celle-ci, sur aucun tirage.
     *
     * Rend (contraste pondéré, effectif). Effectif nul = pas assez de côtes.
     */
    private fun stratifiedWestMinusEast(
        ws: List<PlanetData>,
        strata: List<Pair<Float, Float>>
    ): Pair<Double, Int> {
        var weighted = 0.0
        var weight = 0
        for ((lo, hi) in strata) {
            var eastSum = 0.0; var eastCount = 0
            var westSum = 0.0; var westCount = 0
            for (w in ws) {
                for (i in 0 until w.vertexCount) {
                    if (w.altitudeM[i] <= 0f || w.altitudeM[i] > 500f) continue
                    val lat = abs(Sphere.latitude(w.sphere.vertices[i]))
                    if (lat < lo || lat > hi) continue
                    val (e, distKm) = seawardEast(w, i)
                    if (distKm > 500f) continue
                    if (e > 0.5f) { eastSum += w.temperatureC[i]; eastCount++ }
                    else if (e < -0.5f) { westSum += w.temperatureC[i]; westCount++ }
                }
            }
            if (eastCount >= 3 && westCount >= 3) {
                val k = minOf(eastCount, westCount)
                weighted += (westSum / westCount - eastSum / eastCount) * k
                weight += k
            }
        }
        return Pair(if (weight > 0) weighted / weight else Double.NaN, weight)
    }

    @Test
    fun `les facades opposees se separent en temperature`() {
        // La signature du lot : à latitude comparable, les côtes tournées
        // vers l'est et vers l'ouest ne doivent PLUS avoir la même
        // température. Sans courants, elles étaient identiques.
        //
        // Bande SUBTROPICALE, 26° à 36°, où gyreStrength vaut +0,64 à +0,79 :
        // façade EST chaude, donc contraste ouest − est négatif. Même règle
        // dans les deux hémisphères : Coriolis inverse le sens des gyres,
        // mais aussi la géographie de leurs bords, et les deux inversions se
        // compensent (Brésil chaud comme le Japon, Benguela froid comme la
        // Californie).
        val strata = listOf(Pair(0.4538f, 0.5411f), Pair(0.5411f, 0.6283f))
        val (contrast, n) = stratifiedWestMinusEast(worlds, strata)
        assertTrue(n > 0, "aucune côte subtropicale exploitable sur trois mondes")
        assertTrue(
            contrast < 0.0,
            "contraste ouest − est de $contrast °C sur $n façades subtropicales : " +
            "le contraste est absent ou inversé"
        )
    }

    @Test
    fun `le motif s inverse au dela du front des gyres`() {
        // Signature des gyres SUBPOLAIRES : au-delà de ~43°, c'est la façade
        // OUEST qui est chaude (Bergen) et la façade est qui est froide
        // (Terre-Neuve, Hokkaïdo). La v0.15.1 appliquait le motif subtropical
        // partout — signe correct sur trois couples terrestres sur six
        // seulement — et refroidissait de 5 °C des côtes que la Terre
        // réchauffe, gonflant la banquise.
        //
        // Bande 54° à 69°, où gyreStrength vaut −0,69 à −0,85 : contraste
        // ouest − est positif. Le signe lui-même est verrouillé par le test
        // unitaire de gyreStrength ; celui-ci vérifie que le générateur
        // branche bien la fonction sur les côtes.
        val strata = listOf(
            Pair(0.9425f, 1.0297f),   // 54°–59°
            Pair(1.0297f, 1.1170f),   // 59°–64°
            Pair(1.1170f, 1.2043f)    // 64°–69°
        )
        val (contrast, n) = stratifiedWestMinusEast(worlds, strata)
        assertTrue(n > 0, "aucune côte subpolaire exploitable sur trois mondes")
        assertTrue(
            contrast > 0.0,
            "contraste ouest − est de $contrast °C sur $n façades subpolaires : " +
            "l'inversion subpolaire est absente"
        )
    }

    @Test
    fun `les courants ne rechauffent pas la planete`() {
        // Un courant transporte la chaleur, il n'en produit pas. Si la
        // moyenne planétaire dérivait, les biomes glisseraient en bloc et le
        // calibrage climatique de la Phase 1 serait faussé.
        for (w in worlds) {
            var sum = 0.0
            for (i in 0 until w.vertexCount) sum += w.temperatureC[i]
            val mean = sum / w.vertexCount
            assertTrue(
                mean > -25f && mean < 35f,
                "${w.name} : température moyenne de $mean °C"
            )
        }
    }

    @Test
    fun `la force des gyres a le bon signe dans chaque regime`() {
        // C'est CE test qui verrouille l'inversion subpolaire, pas les tests
        // de façades : une fonction pure se teste sans bruit géographique.
        // Bornes tirées de validation/gyres_subpolaires.py § 2 :
        //   30° : +0,79   subtropical, côte est chaude (Kuroshio)
        //   60° : −0,83   subpolaire, côte ouest chaude (Bergen)
        //   43° : ~0      front des gyres, zone de transition
        val deg = com.terra.core.DEG_TO_RAD
        assertTrue(WorldGenerator.gyreStrength(30f * deg) > 0.6f, "régime subtropical absent à 30°")
        assertTrue(WorldGenerator.gyreStrength(60f * deg) < -0.6f, "régime subpolaire absent à 60°")
        assertTrue(abs(WorldGenerator.gyreStrength(43f * deg)) < 0.05f, "le front n'est pas à 43°")
        // Nul là où la notion d'est n'a pas de sens, borné partout ailleurs.
        assertTrue(abs(WorldGenerator.gyreStrength(0f)) < 1e-6f, "force non nulle à l'équateur")
        assertTrue(abs(WorldGenerator.gyreStrength(90f * deg)) < 1e-5f, "force non nulle au pôle")
        var d = 0f
        while (d <= 90f) {
            assertTrue(abs(WorldGenerator.gyreStrength(d * deg)) <= 1f, "force > 1 à $d°")
            d += 0.5f
        }
    }

    @Test
    fun `le climat reste deterministe`() {
        // Le transport lit la géographie — sommet océanique le plus proche,
        // latitude, distance à la côte —, toutes grandeurs déjà calculées.
        // Rien n'y est aléatoire, et deux générations doivent coïncider au
        // bit près.
        for (w in worlds) {
            val sansCourant = WorldGenerator.fromName(
                w.name, PlanetParams(subdivisions = 4)
            ).generate()
            for (i in 0 until w.vertexCount) {
                val ecart = abs(w.temperatureC[i] - sansCourant.temperatureC[i])
                assertTrue(
                    ecart < 0.001f,
                    "${w.name} : deux générations identiques divergent de $ecart °C"
                )
            }
        }
    }

    @Test
    fun `les glaces restent dans leurs bornes`() {
        // Le risque du lot : refroidir des façades étend la banquise. Et
        // l'argument « l'effet est symétrique donc la glace ne bouge pas »
        // s'est révélé FAUX au run du 08/08 : le gel est un effet de seuil,
        // une anomalie froide crée de la glace qu'une anomalie chaude
        // ailleurs ne fait pas fondre. Gaia est monté à 16 % de glaces.
        // La v0.15.2 réduit l'énergie injectée de 22 % (front des gyres) et
        // retire l'effet des reliefs (atténuation par l'altitude).
        for (w in worlds) {
            var ice = 0
            for (i in 0 until w.vertexCount) {
                if (w.temperatureC[i] < -8f) ice++
            }
            val share = ice.toFloat() / w.vertexCount
            assertTrue(share < 0.35f, "${w.name} : ${share * 100} % de surface très froide")
        }
    }
}
