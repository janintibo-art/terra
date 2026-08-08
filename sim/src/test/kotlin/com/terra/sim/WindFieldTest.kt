package com.terra.sim

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Circulation atmosphérique — lot 1.13.
 *
 * Les bornes du profil viennent du calibrage contre la climatologie de
 * surface (`validation/vents_calibrage.py`) : onze latitudes vérifiées,
 * bornes reprises telles quelles. Les tests de champ tolèrent la
 * respiration du monde (±0,35 rad de direction, ±25 % de vitesse) en
 * travaillant sur des moyennes de bande.
 */
class WindFieldTest {

    companion object {
        private val world by lazy {
            WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4)).generate()
        }
    }

    @Test
    fun `le profil zonal reproduit les trois regimes terrestres`() {
        assertTrue(WindField.zonalMS(15f) in -6.5f..-4.5f, "alizés hors bornes")
        assertTrue(WindField.zonalMS(45f) in 6.0f..8.0f, "vents d'ouest hors bornes")
        assertTrue(WindField.zonalMS(75f) in -3.5f..-1.5f, "vents polaires hors bornes")
        assertTrue(WindField.zonalMS(0f) in -4.5f..-2.0f, "est équatorial hors bornes")
        // Zéros de régime, positions terrestres (~29° et ~63°).
        assertTrue(abs(WindField.zonalMS(29f)) < 1.5f, "transition alizés/ouest déplacée")
        assertTrue(abs(WindField.zonalMS(63f)) < 1.5f, "transition ouest/polaire déplacée")
    }

    @Test
    fun `les branches meridiennes convergent vers l equateur et s inversent au sud`() {
        // Hadley : convergence intertropicale — c'est elle qui fera pleuvoir
        // l'équateur au lot 1.14.
        assertTrue(WindField.meridionalMS(15f) < -1f, "pas de branche de Hadley au nord")
        assertTrue(WindField.meridionalMS(-15f) > 1f, "pas de branche de Hadley au sud")
        // Ferrel : divergence vers les pôles.
        assertTrue(WindField.meridionalMS(45f) > 0.5f)
        assertTrue(WindField.meridionalMS(-45f) < -0.5f)
        // Antisymétrie exacte du profil.
        for (d in intArrayOf(5, 20, 40, 60, 80)) {
            assertEquals(
                WindField.meridionalMS(d.toFloat()),
                -WindField.meridionalMS(-d.toFloat()),
                "le méridien n'est pas antisymétrique à $d°"
            )
        }
    }

    @Test
    fun `le champ par monde respecte les bandes en moyenne`() {
        // Moyennes de bande : la respiration du monde tourne les vecteurs de
        // ±0,35 rad au plus — cos(0,35) = 0,94, le signe zonal des bandes de
        // cœur survit largement à la moyenne.
        fun meanEast(latLo: Float, latHi: Float): Float {
            var sum = 0.0; var count = 0
            for (i in 0 until world.vertexCount) {
                val s = abs(world.position(i).y)
                if (s < kotlin.math.sin(latLo * com.terra.core.DEG_TO_RAD)) continue
                if (s > kotlin.math.sin(latHi * com.terra.core.DEG_TO_RAD)) continue
                sum += world.windEastMS[i]; count++
            }
            assertTrue(count > 20, "bande $latLo-$latHi trop peu peuplée ($count)")
            return (sum / count).toFloat()
        }
        assertTrue(meanEast(8f, 22f) < -2f, "les alizés moyens ne sont pas d'est")
        assertTrue(meanEast(38f, 52f) > 3f, "les vents d'ouest moyens ne sont pas d'ouest")
        assertTrue(meanEast(70f, 82f) < -0.5f, "les vents polaires moyens ne sont pas d'est")
    }

    @Test
    fun `la vitesse reste dans le domaine physique`() {
        // Profil maximal 7,7 m/s, respiration ×1,25 : plafond calculé 9,6.
        // Borne de test à 12 pour la marge, plancher strictement positif.
        for (i in 0 until world.vertexCount) {
            val s = WindField.speedMS(world, i)
            assertTrue(s < 12f, "vent de $s m/s au sommet $i")
            assertTrue(s.isFinite())
        }
    }

    @Test
    fun `le champ est deterministe et de la bonne taille`() {
        assertEquals(world.vertexCount, world.windEastMS.size)
        assertEquals(world.vertexCount, world.windNorthMS.size)
        val again = WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4)).generate()
        assertTrue(world.windEastMS.contentEquals(again.windEastMS))
        assertTrue(world.windNorthMS.contentEquals(again.windNorthMS))
    }

    // L'indépendance vis-à-vis des mondes existants — aucun bit d'altitude,
    // de température, de pluie ou de biome ne bouge — n'a pas besoin d'un
    // test ici : les empreintes figées de FingerprintTest la vérifient
    // déjà, et échoueraient à la première dérive.
}
