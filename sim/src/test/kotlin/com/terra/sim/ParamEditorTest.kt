package com.terra.sim

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Éditeur de paramètres — lot 1.18.
 *
 * Les tests sont génériques : ajouter une ligne au registre suffit à la
 * couvrir. Les seuils sont calculés — la tolérance d'affichage est le
 * demi-pas, seule garantie que la grille entière peut offrir (voir le
 * commentaire « piège du pas flottant » dans ParamEditor).
 */
class ParamEditorTest {

    private val defaults = PlanetParams()

    @Test
    fun `les identifiants sont uniques et les bornes coherentes`() {
        assertEquals(ParamEditor.specs.size, ParamEditor.specs.map { it.id }.toSet().size)
        for (s in ParamEditor.specs) {
            assertTrue(s.min < s.max, "${s.id} : bornes inversées")
            assertTrue(s.step > 0f, "${s.id} : pas nul ou négatif")
            assertTrue(s.steps in 2..2000, "${s.id} : ${s.steps} positions, curseur inutilisable")
        }
    }

    @Test
    fun `les valeurs d usine tombent dans les bornes, a un demi-pas pres sur la grille`() {
        for (s in ParamEditor.specs) {
            val d = s.read(defaults)
            assertTrue(d in s.min..s.max, "${s.id} : défaut ${d} hors [${s.min}, ${s.max}]")
            val onGrid = s.clamp(d)
            assertTrue(
                abs(onGrid - d) <= s.step * 0.51f,
                "${s.id} : le défaut $d s'affiche $onGrid, à plus d'un demi-pas"
            )
        }
    }

    @Test
    fun `l index et la valeur font un aller-retour exact sur toute la grille`() {
        // C'est CETTE propriété qui rend l'éditeur déterministe : la même
        // position de curseur produit le même Float sur tout appareil.
        for (s in ParamEditor.specs) {
            for (k in 0..s.steps) {
                assertEquals(k, s.indexOf(s.valueAt(k)), "${s.id} : index $k perdu")
            }
        }
    }

    @Test
    fun `clamp ramene les valeurs hors bornes sur les bornes`() {
        for (s in ParamEditor.specs) {
            assertEquals(s.valueAt(0), s.clamp(s.min - 1e6f), "${s.id} : plancher")
            assertEquals(s.valueAt(s.steps), s.clamp(s.max + 1e6f), "${s.id} : plafond")
        }
    }

    @Test
    fun `ecrire un parametre ne touche que lui`() {
        for (s in ParamEditor.specs) {
            val v = s.valueAt(1)
            val edited = s.write(defaults, v)
            assertEquals(v, s.read(edited), "${s.id} : l'écriture ne se relit pas")
            for (other in ParamEditor.specs) {
                if (other.id == s.id) continue
                assertEquals(
                    other.read(defaults), other.read(edited),
                    "${s.id} : écrire a modifié ${other.id}"
                )
            }
        }
    }

    @Test
    fun `generationDiffers distingue generation et rendu`() {
        assertFalse(ParamEditor.generationDiffers(defaults, defaults.copy()))
        assertTrue(
            ParamEditor.generationDiffers(defaults, defaults.copy(equatorTempC = 20f)),
            "un paramètre de climat doit compter"
        )
        assertFalse(
            ParamEditor.generationDiffers(
                defaults, defaults.copy(axialTiltDeg = 5f, reliefExaggeration = 0.1f)
            ),
            "l'inclinaison et l'exagération n'entrent pas dans la génération"
        )
    }

    @Test
    fun `la generation honore les parametres edites`() {
        // Preuve de bout en bout, sur deux paramètres représentatifs : la
        // valeur du curseur atteint bien le monde généré. Subdivisions 4
        // pour rester dans le budget de la CI.
        val base = WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4))
            .generate().fingerprint
        val colder = WorldGenerator.fromName(
            "Gaia", PlanetParams(subdivisions = 4, equatorTempC = 20f)
        ).generate().fingerprint
        val drier = WorldGenerator.fromName(
            "Gaia", PlanetParams(subdivisions = 4, maxPrecipMm = 2_000f)
        ).generate().fingerprint
        assertTrue(base != colder, "equatorTempC édité, monde inchangé")
        assertTrue(base != drier, "maxPrecipMm édité, monde inchangé")
        assertTrue(colder != drier, "deux éditions différentes, même monde")
    }

    @Test
    fun `l inclinaison axiale ne change pas le monde statique`() {
        // Documente un fait qui surprendrait dans six mois : l'inclinaison
        // pilote les saisons et le ciel (WorldTime), pas le climat moyen
        // annuel de la génération. Si un futur lot (1.12, insolation) la
        // fait entrer dans la génération, ce test DOIT être inversé et le
        // spec « tilt » passer affectsGeneration = true.
        val a = WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4))
            .generate().fingerprint
        val b = WorldGenerator.fromName(
            "Gaia", PlanetParams(subdivisions = 4, axialTiltDeg = 5f)
        ).generate().fingerprint
        assertEquals(a, b)
    }
}

/**
 * Format de sauvegarde 2 : les deux paramètres oubliés du format 1 sont
 * désormais persistés, et les sauvegardes v1 se relisent avec leurs
 * valeurs d'usine — qui sont celles de leur génération, puisque ces
 * paramètres n'étaient pas éditables avant le lot 1.18.
 */
class WorldSaveFormat2Test {

    @Test
    fun `les parametres edites survivent a l aller-retour`() {
        val edited = PlanetParams(oceanThermalInertia = 0.75f, continentalityC = 20f)
        val bytes = WorldSave.encode(WorldSave.Snapshot("Kaleth", edited, 4200L))
        val back = WorldSave.decode(bytes)
        assertNotNull(back)
        assertEquals(0.75f, back.params.oceanThermalInertia)
        assertEquals(20f, back.params.continentalityC)
        assertEquals("Kaleth", back.worldName)
        assertEquals(4200L, back.tick)
    }

    @Test
    fun `une sauvegarde v1 se relit avec les valeurs d usine des nouveaux champs`() {
        // Flux v1 fabriqué à l'identique de l'ancien encodeur : mêmes
        // champs, même ordre, sans les deux ajouts du format 2.
        val p = PlanetParams(oceanFraction = 0.5f, subdivisions = 4)
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { s ->
                s.writeInt(0x54455252)              // MAGIC "TERR"
                s.writeInt(1)                        // FORMAT_VERSION historique
                s.writeInt(WorldSave.GENERATION_VERSION)
                s.writeUTF("Ancien")
                s.writeLong(7L)
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
            }
        }.toByteArray()

        val back = WorldSave.decode(bytes)
        assertNotNull(back, "une sauvegarde v1 valide doit se relire")
        assertEquals(1, back.formatVersion)
        assertEquals(0.5f, back.params.oceanFraction, "les champs v1 doivent être intacts")
        val defaults = PlanetParams()
        assertEquals(defaults.oceanThermalInertia, back.params.oceanThermalInertia)
        assertEquals(defaults.continentalityC, back.params.continentalityC)
    }
}
