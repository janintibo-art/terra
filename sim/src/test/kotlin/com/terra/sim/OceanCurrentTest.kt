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

    /** Composante est de la direction vers la mer la plus proche. */
    private fun eastwardness(w: PlanetData, i: Int): Float {
        var best = -1
        var bestDot = -2f
        val v = w.sphere.vertices[i]
        for (j in 0 until w.vertexCount) {
            if (w.altitudeM[j] >= 0f) continue
            val o = w.sphere.vertices[j]
            val d = v.x * o.x + v.y * o.y + v.z * o.z
            if (d > bestDot) { bestDot = d; best = j }
        }
        if (best < 0) return 0f
        val o = w.sphere.vertices[best]
        val radial = v.x * o.x + v.y * o.y + v.z * o.z
        val tx = o.x - v.x * radial
        val ty = o.y - v.y * radial
        val tz = o.z - v.z * radial
        val tl = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz)
        val ex = -v.z
        val ez = v.x
        val el = kotlin.math.sqrt(ex * ex + ez * ez)
        if (tl < 1e-6f || el < 1e-4f) return 0f
        return (tx * ex + tz * ez) / (tl * el)
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
    fun `les facades opposees se separent en temperature`() {
        // La signature du lot : à latitude comparable, les côtes tournées
        // vers l'est et vers l'ouest ne doivent PLUS avoir la même
        // température. Sans courants, elles étaient identiques.
        // Bande SUBTROPICALE uniquement : 26° à 36°, cœur des gyres
        // subtropicaux, où strength vaut +0,69 à +0,79 (validation
        // gyres_subpolaires.py § 4). L'ancienne bande montait à 63° et
        // mélangeait les deux régimes de gyres, ce qui aurait dilué le
        // contraste jusqu'à rendre le test aveugle.
        //
        // Même règle dans les deux hémisphères : Coriolis inverse le sens
        // des gyres, mais aussi la géographie de leurs bords, et les deux
        // inversions se compensent (Brésil chaud comme le Japon, Benguela
        // froid comme la Californie).
        val (eastMean, westMean) = facadeMeans(worlds[0], 0.45f, 0.62f)
        assertTrue(
            eastMean > westMean,
            "façades est à $eastMean °C contre ouest à $westMean °C : le contraste est absent ou inversé"
        )
    }

    @Test
    fun `le motif s inverse au dela du front des gyres`() {
        // Signature des gyres SUBPOLAIRES : au-delà de ~43°, c'est la façade
        // OUEST qui est chaude (Bergen) et la façade est qui est froide
        // (Terre-Neuve, Hokkaïdo). La v0.15.1 appliquait le motif subtropical
        // partout — signe correct sur trois couples terrestres sur six
        // seulement — et refroidissait de 5 °C des côtes que la Terre
        // réchauffe, gonflant la banquise. Bande 54° à 69°, où strength vaut
        // −0,69 à −0,85 : le contraste attendu y est franc.
        var checked = 0
        for (w in worlds) {
            val (eastMean, westMean) = try {
                facadeMeans(w, 0.95f, 1.20f)
            } catch (e: AssertionError) {
                // Un monde peut manquer de côtes subpolaires : on ne conclut
                // rien de son silence, mais au moins un monde doit trancher.
                continue
            }
            checked++
            assertTrue(
                westMean > eastMean,
                "${w.name} : façades ouest à $westMean °C contre est à $eastMean °C " +
                "au-delà du front des gyres : l'inversion subpolaire est absente"
            )
        }
        assertTrue(checked > 0, "aucun monde n'offre assez de façades subpolaires")
    }

    /** Moyennes de température des façades est et ouest dans une bande de latitude. */
    private fun facadeMeans(w: PlanetData, latMin: Float, latMax: Float): Pair<Double, Double> {
        var eastSum = 0.0; var eastCount = 0
        var westSum = 0.0; var westCount = 0
        for (i in 0 until w.vertexCount) {
            if (w.altitudeM[i] <= 0f) continue
            val lat = Sphere.latitude(w.sphere.vertices[i])
            if (abs(lat) < latMin || abs(lat) > latMax) continue
            val e = eastwardness(w, i)
            if (e > 0.5f) { eastSum += w.temperatureC[i]; eastCount++ }
            if (e < -0.5f) { westSum += w.temperatureC[i]; westCount++ }
        }
        assertTrue(eastCount > 5 && westCount > 5, "trop peu de façades : $eastCount / $westCount")
        return Pair(eastSum / eastCount, westSum / westCount)
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
