package com.terra.sim

import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lot 2.7-a — registres d'échelle.
 *
 * Trois familles : la classification (les frontières calibrées par
 * validation/registres.py), l'hystérésis (ni clignotement, ni registre
 * inatteignable), et le plan lointain (déplacement PUR depuis :app — le test
 * d'identité est le vrai livrable : si quelqu'un « améliore » la formule d'un
 * côté sans l'autre, c'est lui qui doit casser).
 */
class ScaleRegistryTest {

    // ------------------------------------------------------- classification

    @Test
    fun classificationParFrontieresCalibrees() {
        // Un point franc dans chaque registre — pas sur une frontière.
        assertEquals(ScaleRegister.GROUND, ScaleRegistry.classify(1.7))
        assertEquals(ScaleRegister.GROUND, ScaleRegistry.classify(300.0))
        assertEquals(ScaleRegister.LOCAL, ScaleRegistry.classify(5_000.0))
        assertEquals(ScaleRegister.REGIONAL, ScaleRegistry.classify(100_000.0))
        assertEquals(ScaleRegister.CONTINENTAL, ScaleRegistry.classify(1_000_000.0))
        assertEquals(ScaleRegister.ORBIT, ScaleRegistry.classify(24_000_000.0))
    }

    @Test
    fun classificationMonotone() {
        // L'ordinal ne peut que croître avec l'altitude : un balayage
        // logarithmique de 2 m à 80 000 km ne doit jamais redescendre.
        var alt = 2.0
        var previous = ScaleRegistry.classify(alt)
        while (alt < 80_000_000.0) {
            alt *= 1.05
            val r = ScaleRegistry.classify(alt)
            assertTrue(
                r.ordinal >= previous.ordinal,
                "régression de registre à $alt m : $previous → $r"
            )
            previous = r
        }
        assertEquals(ScaleRegister.ORBIT, previous)
    }

    @Test
    fun frontiereContinentalOrbiteEstLAncreExistante() {
        // La frontière haute du continental DOIT rester égale à
        // TILT_OPEN_RANGE_M : deux constantes séparées finiraient par mentir
        // à l'une des deux (leçon HUD v0.34.2, transposée).
        assertEquals(
            PlanetCamera.TILT_OPEN_RANGE_M,
            ScaleRegistry.UPPER_BOUND_M[ScaleRegister.CONTINENTAL.ordinal]
        )
    }

    @Test
    fun frontiereSolEstLePlancherDeDilatation() {
        // Même exigence pour l'ancre basse : 700 m doit rester à moins de 1 %
        // de la portée où la dilatation temporelle atteint son plancher.
        val anchor = PlanetCamera.TILT_OPEN_RANGE_M * PlanetCamera.MIN_TIME_DILATION
        val bound = ScaleRegistry.UPPER_BOUND_M[ScaleRegister.GROUND.ordinal]
        assertTrue(
            bound in anchor * 0.99..anchor * 1.02,
            "frontière sol $bound m décrochée de l'ancre $anchor m"
        )
    }

    // ----------------------------------------------------------- hystérésis

    @Test
    fun ballottementSurFrontiereNeChangeRien() {
        // Arrêt d'inertie pile sur la frontière local/régional (20 km), avec
        // un ballottement de ±8 % — sous la demi-bande de 12 %. Le registre
        // acquis ne doit pas bouger, dans un sens comme dans l'autre.
        val bound = ScaleRegistry.UPPER_BOUND_M[ScaleRegister.LOCAL.ordinal]

        val fromBelow = ScaleRegistry(bound * 0.5)      // arrive du local
        assertEquals(ScaleRegister.LOCAL, fromBelow.current)
        repeat(50) { i ->
            val wobble = if (i % 2 == 0) 1.08 else 0.92
            fromBelow.update(bound * wobble)
            assertEquals(ScaleRegister.LOCAL, fromBelow.current)
        }

        val fromAbove = ScaleRegistry(bound * 4.0)      // arrive du régional
        assertEquals(ScaleRegister.REGIONAL, fromAbove.current)
        repeat(50) { i ->
            val wobble = if (i % 2 == 0) 1.08 else 0.92
            fromAbove.update(bound * wobble)
            assertEquals(ScaleRegister.REGIONAL, fromAbove.current)
        }
    }

    @Test
    fun franchissementFrancChangeUneFois() {
        val bound = ScaleRegistry.UPPER_BOUND_M[ScaleRegister.LOCAL.ordinal]
        val reg = ScaleRegistry(bound * 0.5)
        assertFalse(reg.update(bound * 0.9))            // encore dans la bande
        assertTrue(reg.update(bound * 1.2))             // franchit ×1,12
        assertEquals(ScaleRegister.REGIONAL, reg.current)
        assertFalse(reg.update(bound * 1.2))            // stable ensuite
    }

    @Test
    fun sautDePlusieursRegistresHonoreDUnCoup() {
        // Téléportation console : orbite → sol sans étapes. L'hystérésis
        // protège du ballottement, pas du voyage.
        val reg = ScaleRegistry(24_000_000.0)
        assertEquals(ScaleRegister.ORBIT, reg.current)
        assertTrue(reg.update(300.0))
        assertEquals(ScaleRegister.GROUND, reg.current)
    }

    @Test
    fun sautPartielSArreteSurLaBonneMarche() {
        // Le bug que ce test attrape : une première version testait la seule
        // bande du registre CIBLE. Descendre d'orbite à 175 km — dans la
        // bande de la frontière 180 km — restait alors « orbite », alors que
        // la frontière 2 000 km est franchie sans ambiguïté : il faut
        // s'arrêter sur « continental », l'échelon intermédiaire.
        val down = ScaleRegistry(24_000_000.0)
        assertTrue(down.update(175_000.0))
        assertEquals(ScaleRegister.CONTINENTAL, down.current)
        // Et la marche suivante se franchit dès qu'on purge la bande.
        assertTrue(down.update(150_000.0))
        assertEquals(ScaleRegister.REGIONAL, down.current)

        // Symétrique en montée : du sol à 21 km — dans la bande de la
        // frontière 20 km — on s'arrête sur « local », pas « régional ».
        val up = ScaleRegistry(300.0)
        assertTrue(up.update(21_000.0))
        assertEquals(ScaleRegister.LOCAL, up.current)
        assertTrue(up.update(23_000.0))
        assertEquals(ScaleRegister.REGIONAL, up.current)
    }

    @Test
    fun tousLesRegistresRestentAtteignables() {
        // Si deux bandes d'hystérésis se chevauchaient, un registre
        // deviendrait impossible à STABILISER. On vérifie que la bande haute
        // d'une frontière reste sous la bande basse de la suivante.
        val b = ScaleRegistry.BAND
        val bounds = ScaleRegistry.UPPER_BOUND_M
        for (i in 0 until bounds.size - 2) {
            assertTrue(
                bounds[i] * b < bounds[i + 1] / b,
                "bandes en collision entre ${bounds[i]} et ${bounds[i + 1]}"
            )
        }
    }

    @Test
    fun determinisme() {
        // Même séquence d'altitudes → mêmes registres, y compris l'état
        // interne de l'hystérésis. Trivial ici (aucun aléatoire), mais le
        // test verrouille l'invariant si l'hystérésis se raffine un jour.
        val seq = doubleArrayOf(24e6, 1.9e6, 2.3e6, 150e3, 21e3, 18e3, 650.0, 800.0)
        val a = ScaleRegistry(30e6)
        val b = ScaleRegistry(30e6)
        for (alt in seq) {
            assertEquals(a.update(alt), b.update(alt))
            assertEquals(a.current, b.current)
        }
    }

    // -------------------------------------------------------- plan lointain

    @Test
    fun planLointainIdentiqueALAncienneFormuleDeApp() {
        // La formule vivait en ligne dans le rendu de la descente (:app,
        // v0.38.1) ; la voici recopiée TELLE QUELLE. Si les deux écritures
        // divergent un jour, ce test doit casser : le rendu doit rester le
        // miroir exact du calcul testé.
        fun oldApp(altitudeM: Double, radius: Double): Double {
            val altitude = max(2.0, altitudeM)
            val horizonM =
                sqrt(max(0.0, (radius + altitude) * (radius + altitude) - radius * radius))
            return horizonM * 1.8 + 80_000.0
        }

        // L'ancienne ligne de brume lisait aussi l'horizon nu ; il est
        // exposé séparément et doit porter la même valeur exacte.
        fun oldHorizon(altitudeM: Double, radius: Double): Double {
            val altitude = max(2.0, altitudeM)
            return sqrt(max(0.0, (radius + altitude) * (radius + altitude) - radius * radius))
        }

        val r = 6_371_000.0
        for (alt in doubleArrayOf(0.0, 2.0, 100.0, 700.0, 5e3, 2e4, 1.8e5, 2e6, 2e7, 8e7)) {
            // Égalité EXACTE, pas une tolérance : même expression, mêmes
            // doubles, mêmes bits. Toute divergence signifierait que l'une
            // des deux écritures a été retouchée seule.
            val expected: Double = oldApp(alt, r)
            val actual: Double = ScaleRegistry.farPlaneM(alt, r)
            assertEquals(expected, actual, "divergence du plan lointain à $alt m")

            val expectedHorizon: Double = oldHorizon(alt, r)
            val actualHorizon: Double = ScaleRegistry.slantHorizonM(alt, r)
            assertEquals(expectedHorizon, actualHorizon, "divergence de l'horizon à $alt m")

            // Et le plan lointain doit rester EXACTEMENT horizon × 1,8 + 80 km :
            // si quelqu'un retouche l'un sans l'autre, c'est ici que ça casse.
            val recomposed: Double = actualHorizon * 1.8 + 80_000.0
            assertEquals(recomposed, actual, "plan lointain décomposé ≠ recomposé à $alt m")
        }
    }

    @Test
    fun planLointainCouvreLHorizon() {
        // La propriété, indépendamment de la formule : le plan dépasse la
        // distance oblique au limbe avec au moins 50 % de marge (inclinaison,
        // jupes, relief au-delà de l'horizon).
        val r = 6_371_000.0
        var alt = 2.0
        while (alt < 80_000_000.0) {
            val slant = sqrt((r + alt) * (r + alt) - r * r)
            assertTrue(
                ScaleRegistry.farPlaneM(alt, r) > slant * 1.5,
                "plan lointain trop court à $alt m"
            )
            alt *= 2.0
        }
    }

    @Test
    fun planProcheSousPlanLointainPartout() {
        // near < far sur toute la plage, y compris le pire cas du near
        // (plancher de 10 cm au ras du sol) et son plafond (5 km en orbite).
        val r = 6_371_000.0
        var alt = 2.0
        while (alt < 80_000_000.0) {
            val near = PlanetCamera.nearPlaneFor(alt, alt)
            assertTrue(near < ScaleRegistry.farPlaneM(alt, r), "near ≥ far à $alt m")
            alt *= 2.0
        }
    }
}
