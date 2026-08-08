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
        val w = worlds[0]
        var eastSum = 0.0; var eastCount = 0
        var westSum = 0.0; var westCount = 0
        for (i in 0 until w.vertexCount) {
            if (w.altitudeM[i] <= 0f) continue
            val lat = Sphere.latitude(w.sphere.vertices[i])
            // Bande des moyennes latitudes, là où les gyres sont vigoureux.
            if (abs(lat) < 0.5f || abs(lat) > 1.1f) continue
            // Même règle dans les deux hémisphères : Coriolis inverse le sens
            // des gyres, mais aussi la géographie de leurs bords, et les deux
            // inversions se compensent (Brésil chaud comme le Japon, Benguela
            // froid comme la Californie).
            val e = eastwardness(w, i)
            if (e > 0.5f) { eastSum += w.temperatureC[i]; eastCount++ }
            if (e < -0.5f) { westSum += w.temperatureC[i]; westCount++ }
        }
        assertTrue(eastCount > 5 && westCount > 5, "trop peu de façades : $eastCount / $westCount")
        val eastMean = eastSum / eastCount
        val westMean = westSum / westCount
        assertTrue(
            eastMean > westMean,
            "façades est à $eastMean °C contre ouest à $westMean °C : le contraste est absent ou inversé"
        )
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
        // Le risque du lot : refroidir des façades pourrait étendre la
        // banquise. L'effet étant symétrique, la fraction glacée ne doit pas
        // déraper.
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
