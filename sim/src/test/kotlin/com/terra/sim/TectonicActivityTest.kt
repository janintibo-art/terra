package com.terra.sim

import com.terra.core.Seed
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Activité tectonique — lot 1.18 b.
 *
 * Le lot repose sur un pari précis : un multiplicateur appliqué APRÈS les
 * tirages aléatoires, neutre à 1,0. Le premier test verrouille ce pari au
 * bit près — c'est lui qui autorise à ne pas incrémenter GENERATION_VERSION
 * ni re-figer les empreintes. S'il casse un jour (réordonnancement du
 * calcul, changement de la sémantique de tectonicScale), la réponse n'est
 * pas de l'assouplir : c'est d'incrémenter la version de génération.
 */
class TectonicActivityTest {

    @Test
    fun `l activite d usine est neutre au bit pres`() {
        val a = WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4))
            .generate().fingerprint
        val b = WorldGenerator.fromName(
            "Gaia", PlanetParams(subdivisions = 4, tectonicActivity = 1f)
        ).generate().fingerprint
        assertEquals(a, b, "×1,0 doit être exactement neutre — sinon, GENERATION_VERSION")
    }

    @Test
    fun `l activite change le monde des qu elle quitte l usine`() {
        val prints = listOf(0f, 0.5f, 1f, 2f).map { a ->
            WorldGenerator.fromName(
                "Gaia", PlanetParams(subdivisions = 4, tectonicActivity = a)
            ).generate().fingerprint
        }
        assertEquals(prints.size, prints.toSet().size, "deux activités distinctes, même monde")
    }

    @Test
    fun `a activite nulle il ne reste que le socle isostatique`() {
        // Test du mécanisme, sans passer par un monde complet : le champ
        // structural à activité 0 ne contient que la flottaison de croûte,
        // exactement ±[CRUST]. Toute autre valeur signifierait qu'un terme
        // d'orogenèse ou de panache échappe au curseur.
        val sphere = Icosphere(4)
        val seed = Seed.fromText("gaia")
        val plates = PlateSet.generate(seed, sphere, oceanFraction = 0.66f)
        val boundaries = BoundarySet.classify(sphere, plates)
        val dist = BoundaryDistanceField.generate(sphere, plates, boundaries)
        val hotspots = HotspotField.generate(seed, plates, sphere)

        val dead = TectonicRelief.build(
            sphere, plates, dist, tectonicScale = 0f, hotspots = hotspots, activity = 0f
        )
        for (v in 0 until sphere.vertexCount) {
            assertTrue(
                dead[v] == TectonicRelief.CRUST_CONTINENTAL_M ||
                    dead[v] == TectonicRelief.CRUST_OCEANIC_M,
                "sommet $v : ${dead[v]} m — un terme échappe à l'activité nulle"
            )
        }
    }

    @Test
    fun `l amplitude du relief croit avec l activite`() {
        // Sur le CHAMP structural, l'étendue (max − min) est déterministe
        // pour un monde donné et strictement croissante avec l'échelle :
        // pas de seuil deviné, juste un ordre.
        val sphere = Icosphere(4)
        val seed = Seed.fromText("gaia")
        val plates = PlateSet.generate(seed, sphere, oceanFraction = 0.66f)
        val boundaries = BoundarySet.classify(sphere, plates)
        val dist = BoundaryDistanceField.generate(sphere, plates, boundaries)
        val hotspots = HotspotField.generate(seed, plates, sphere)

        fun range(scale: Float): Float {
            val f = TectonicRelief.build(
                sphere, plates, dist, tectonicScale = scale,
                hotspots = hotspots, activity = scale
            )
            return (f.maxOrNull() ?: 0f) - (f.minOrNull() ?: 0f)
        }
        val r0 = range(0f)
        val r1 = range(1f)
        val r2 = range(2f)
        assertEquals(
            TectonicRelief.CRUST_CONTINENTAL_M - TectonicRelief.CRUST_OCEANIC_M, r0,
            "à zéro, l'étendue est exactement celle du socle"
        )
        assertTrue(r1 > r0, "l'orogenèse n'élargit pas le relief")
        assertTrue(r2 > r1, "doubler l'activité n'élargit pas le relief")
    }
}

/** Format de sauvegarde 3 : l'activité tectonique est persistée. */
class WorldSaveFormat3Test {

    @Test
    fun `l activite editee survit a l aller-retour`() {
        val edited = PlanetParams(tectonicActivity = 1.6f)
        val back = WorldSave.decode(WorldSave.encode(WorldSave.Snapshot("Orion", edited, 9L)))
        assertNotNull(back)
        assertEquals(1.6f, back.params.tectonicActivity)
    }

    @Test
    fun `une sauvegarde v2 se relit avec l activite d usine`() {
        // Flux v2 fabriqué à l'identique de l'encodeur de la v0.16.x :
        // les champs du format 2 inclus, sans celui du format 3.
        val p = PlanetParams(oceanThermalInertia = 0.75f, subdivisions = 4)
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { s ->
                s.writeInt(0x54455252)              // MAGIC "TERR"
                s.writeInt(2)                        // FORMAT_VERSION de la v0.16.x
                s.writeInt(WorldSave.GENERATION_VERSION)
                s.writeUTF("AncienV2")
                s.writeLong(3L)
                s.writeFloat(p.radiusM)
                s.writeFloat(p.oceanFraction)
                s.writeFloat(p.maxAltitudeM)
                s.writeFloat(p.maxDepthM)
                s.writeFloat(p.reliefExaggeration)
                s.writeFloat(p.axialTiltDeg)
                s.writeFloat(p.equatorTempC)
                s.writeFloat(p.poleTempDropC)
                s.writeFloat(p.lapseRateCPerKm)
                s.writeFloat(p.maxPrecipMm)
                s.writeInt(p.subdivisions)
                s.writeFloat(p.oceanThermalInertia)
                s.writeFloat(p.continentalityC)
            }
        }.toByteArray()

        val back = WorldSave.decode(bytes)
        assertNotNull(back, "une sauvegarde v2 valide doit se relire")
        assertEquals(2, back.formatVersion)
        assertEquals(0.75f, back.params.oceanThermalInertia, "les champs v2 doivent être intacts")
        assertEquals(PlanetParams().tectonicActivity, back.params.tectonicActivity)
    }
}
