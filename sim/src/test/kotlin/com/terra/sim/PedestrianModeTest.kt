package com.terra.sim

import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Mode piéton — v0.29.0.
 *
 * La règle tient en une équation : la portée de visée place l'œil à
 * hauteur d'homme. Les bornes sont vérifiées contre la géométrie, pas
 * contre des valeurs recopiées.
 */
class PedestrianModeTest {

    @Test
    fun `la portee place l oeil a hauteur d homme`() {
        var d = PlanetCamera.PEDESTRIAN_MIN_TILT_RAD
        while (d <= PlanetCamera.MAX_TILT_RAD) {
            val r = PlanetCamera.pedestrianRangeM(d)
            val eyeHeight = r * cos(d)
            assertTrue(
                abs(eyeHeight - PlanetCamera.EYE_HEIGHT_M) < 0.01,
                "à ${Math.toDegrees(d)}° l'œil est à $eyeHeight m au lieu de 1,70"
            )
            d += 0.02
        }
    }

    @Test
    fun `la portee croit avec l inclinaison et reste raisonnable`() {
        val low = PlanetCamera.pedestrianRangeM(PlanetCamera.PEDESTRIAN_MIN_TILT_RAD)
        val high = PlanetCamera.pedestrianRangeM(PlanetCamera.MAX_TILT_RAD)
        assertTrue(high > low, "le regard doit porter plus loin en se redressant")
        // Se pencher : le sol à quelques mètres. Se redresser : l'horizon
        // à une douzaine de mètres. Jamais au-delà de la portée d'un regard
        // sur le sol proche.
        assertTrue(low in 2.0..4.0, "portée penchée improbable : $low")
        assertTrue(high in 8.0..20.0, "portée redressée improbable : $high")
    }

    @Test
    fun `les inclinaisons extremes sont ramenees dans le domaine`() {
        // Sous le seuil, on ne verrait que ses chaussures ; au-delà du
        // maximum, la portée exploserait vers l'infini (cos → 0).
        val below = PlanetCamera.pedestrianRangeM(0.1)
        val above = PlanetCamera.pedestrianRangeM(1.5)
        assertTrue(below in 2.0..4.0, "inclinaison basse non bornée : $below")
        assertTrue(above in 8.0..20.0, "inclinaison haute non bornée : $above")
        assertTrue(PlanetCamera.pedestrianRangeM(1.5707).isFinite(), "portée infinie à 90°")
    }

    @Test
    fun `les vitesses sont celles d un humain`() {
        assertTrue(
            PlanetCamera.WALK_SPEED_MS in 1.0..3.0,
            "vitesse de marche hors du domaine humain"
        )
        assertTrue(
            PlanetCamera.RUN_SPEED_MS > PlanetCamera.WALK_SPEED_MS &&
                PlanetCamera.RUN_SPEED_MS <= 9.0,
            "vitesse de course hors du domaine humain"
        )
        // Un tour de planète à pied : de l'ordre de l'année. C'est le
        // genre de chiffre qui donne son échelle au monde.
        val circumference = 2 * Math.PI * 6_371_000.0
        val days = circumference / PlanetCamera.WALK_SPEED_MS / 86_400.0
        assertTrue(days > 100, "la planète se ferait à pied en $days jours : trop petite")
    }
}
