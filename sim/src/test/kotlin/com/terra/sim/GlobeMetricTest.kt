package com.terra.sim

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lot 2.7-b1 — globe métrique.
 *
 * La dés-exagération est le miroir Kotlin du shader : c'est ELLE qu'on
 * teste, en aller-retour contre la formule d'exagération de PlanetData.
 * La sélection du limbe est testée sur des centres synthétiques placés à
 * des angles connus — pas sur une icosphère réelle, dont la répartition
 * ferait du test une loterie de tolérances.
 */
class GlobeMetricTest {

    // ------------------------------------------------------ dés-exagération

    @Test
    fun allerRetourExactContreLaFormuleDExageration() {
        val radiusM = 6_371_000.0
        val maxAlt = 7_000f
        val exag = 0.055f
        val factor = GlobeMetric.deExaggerationFactor(maxAlt, exag)
        for (altitude in floatArrayOf(0f, 1f, 137f, 2_500f, 7_000f)) {
            // La formule d'exagération de PlanetData.renderRadius, recopiée.
            val rendered = 1f + (altitude / maxAlt) * exag
            val back = GlobeMetric.trueRadiusM(rendered, factor, radiusM)
            // Budget d'erreur : la quantification float32 de (rendered − 1)
            // vaut 2⁻²³ ≈ 1,2e-7, soit 1,2e-7 × 127 000 ≈ 1,5 cm d'altitude.
            // On teste à 10 cm : dix fois le budget, mille fois sous le pixel.
            assertTrue(
                abs(back - (radiusM + altitude)) < 0.1,
                "aller-retour faux à $altitude m : $back"
            )
        }
    }

    @Test
    fun lEauResteALaMer() {
        // Les sommets sous la mer ont renderRadius = 1 exactement : le rayon
        // vrai doit être EXACTEMENT le rayon planétaire, pas presque.
        val r = GlobeMetric.trueRadiusM(1f, 127_272.73f, 6_371_000.0)
        assertEquals(6_371_000.0, r)
    }

    @Test
    fun exagerationNulleNeProduitPasNaN() {
        // Le curseur de l'éditeur descend à 0 : maxAlt/0 = ∞, que le shader
        // multiplierait par (r − 1) = 0 → NaN (leçon v0.35.1). La garde doit
        // rendre un facteur nul et une sphère à la mer.
        val factor = GlobeMetric.deExaggerationFactor(7_000f, 0f)
        assertEquals(0f, factor)
        val r = GlobeMetric.trueRadiusM(1f, factor, 6_371_000.0)
        assertTrue(r.isFinite())
        assertEquals(6_371_000.0, r)
    }

    // ------------------------------------------------------------ biais

    @Test
    fun biaisDeCollerette() {
        val radius = 6_371_000.0
        val near = 5_000.0            // plafond de nearPlaneFor, atteint en orbite
        val px = 540.0 / kotlin.math.tan(0.733038 / 2.0)
        var alt = 200_000.0
        while (alt <= 6_000_000.0) {
            val slant = sqrt((radius + alt) * (radius + alt) - radius * radius)
            val bias = GlobeMetric.collarBiasM(alt, radius, near)
            // Assez pour trancher : au moins un quantum de profondeur.
            val quantum = slant * slant / (near * 16_777_216.0)
            assertTrue(bias >= quantum, "biais sous le quantum à $alt m")
            // Pas trop pour la silhouette : sous le demi-pixel.
            assertTrue(bias / slant * px < 0.5, "silhouette abaissée à $alt m")
            alt *= 1.5
        }
    }

    // -------------------------------------------------------- bande du limbe

    /** Centres synthétiques sur un méridien, un par angle donné au nadir. */
    private fun ringCenters(anglesRad: DoubleArray): FloatArray {
        val out = FloatArray(anglesRad.size * 3)
        for (i in anglesRad.indices) {
            // Nadir = +Y ; on s'écarte vers +X.
            out[3 * i] = sin(anglesRad[i]).toFloat()
            out[3 * i + 1] = cos(anglesRad[i]).toFloat()
            out[3 * i + 2] = 0f
        }
        return out
    }

    @Test
    fun laFenetreEncadreLHorizon() {
        val radius = 6_371_000.0
        val alt = 1_000_000.0
        val thetaMax = acos(radius / (radius + alt))
        val angles = doubleArrayOf(
            0.0,                                    // nadir : exclu
            thetaMax - LimbBand.INNER_RAD - 0.01,   // juste avant la bande : exclu
            thetaMax - LimbBand.INNER_RAD + 0.01,   // dans la bande : inclus
            thetaMax,                               // l'horizon même : inclus
            thetaMax + LimbBand.OUTER_RAD - 0.01,   // encore dedans : inclus
            thetaMax + LimbBand.OUTER_RAD + 0.01,   // derrière : exclu
            Math.PI                                  // antipode : exclu
        )
        val selected = LimbBand.selectFaces(
            ringCenters(angles), 0f, 1f, 0f, alt, radius
        )
        assertTrue(selected.contentEquals(intArrayOf(2, 3, 4)), selected.joinToString())
    }

    @Test
    fun auSolLaBandeSeRabatSansExploser() {
        // À 2 m d'altitude, θ_max ≈ 0,0008 rad : la borne intérieure serait
        // négative sans le rabattement à zéro. La sélection doit alors
        // inclure le nadir lui-même — géométriquement juste, l'horizon est
        // à ses pieds — et surtout ne pas planter ni tout sélectionner.
        val radius = 6_371_000.0
        val angles = doubleArrayOf(0.0, 0.03, 0.2, 1.0, Math.PI)
        val selected = LimbBand.selectFaces(
            ringCenters(angles), 0f, 1f, 0f, 2.0, radius
        )
        assertTrue(selected.contentEquals(intArrayOf(0, 1)), selected.joinToString())
    }

    @Test
    fun cadenceDeReselection() {
        // Immobile : pas de reconstruction.
        assertFalse(
            LimbBand.shouldRebuild(0f, 1f, 0f, 1e6, 0f, 1f, 0f, 1e6)
        )
        // Rotation au-delà du seuil (0,02 rad > 0,012).
        val c = cos(0.02).toFloat(); val s = sin(0.02).toFloat()
        assertTrue(LimbBand.shouldRebuild(0f, 1f, 0f, 1e6, s, c, 0f, 1e6))
        // Rotation sous le seuil (0,005 rad).
        val c2 = cos(0.005).toFloat(); val s2 = sin(0.005).toFloat()
        assertFalse(LimbBand.shouldRebuild(0f, 1f, 0f, 1e6, s2, c2, 0f, 1e6))
        // Altitude : ±8 % déclenche, ±3 % non.
        assertTrue(LimbBand.shouldRebuild(0f, 1f, 0f, 1e6, 0f, 1f, 0f, 1.08e6))
        assertTrue(LimbBand.shouldRebuild(0f, 1f, 0f, 1e6, 0f, 1f, 0f, 0.92e6))
        assertFalse(LimbBand.shouldRebuild(0f, 1f, 0f, 1e6, 0f, 1f, 0f, 1.03e6))
        // Premier appel (altitude sentinelle) : toujours reconstruire.
        assertTrue(LimbBand.shouldRebuild(0f, 0f, 0f, -1.0, 0f, 1f, 0f, 1e6))
    }

    @Test
    fun centresDeFacesDepuisUnTamponEntrelace() {
        // Deux faces de 3 sommets à 10 flottants, positions en tête. La
        // première autour de +X, la seconde autour de +Z, rayons ≠ 1 pour
        // vérifier la normalisation.
        val fpv = 10
        val data = FloatArray(2 * 3 * fpv)
        fun put(face: Int, vert: Int, x: Float, y: Float, z: Float) {
            val o = (face * 3 + vert) * fpv
            data[o] = x; data[o + 1] = y; data[o + 2] = z
        }
        put(0, 0, 1.1f, 0.1f, 0f); put(0, 1, 1.1f, -0.1f, 0f); put(0, 2, 1.1f, 0f, 0.1f)
        put(1, 0, 0f, 0.1f, 0.9f); put(1, 1, 0.1f, 0f, 0.9f); put(1, 2, -0.1f, -0.1f, 0.9f)
        val dirs = LimbBand.faceCenterDirs(data, fpv)
        assertEquals(6, dirs.size)
        // Unitaire, et pointant vers l'axe attendu.
        for (f in 0 until 2) {
            val len = sqrt(
                dirs[3 * f] * dirs[3 * f] + dirs[3 * f + 1] * dirs[3 * f + 1] +
                    dirs[3 * f + 2] * dirs[3 * f + 2]
            )
            assertTrue(abs(len - 1f) < 1e-5f)
        }
        assertTrue(dirs[0] > 0.99f)      // face 0 ≈ +X
        assertTrue(dirs[5] > 0.99f)      // face 1 ≈ +Z
    }
}
