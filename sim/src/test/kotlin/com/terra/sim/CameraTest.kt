package com.terra.sim

import com.terra.core.Geodesy
import com.terra.core.Rng
import com.terra.core.Vec3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Vec3dTest {

    @Test
    fun `le double precision distingue le centimetre a l echelle planetaire`() {
        // La raison d'être de cette classe : en 32 bits, ces deux positions
        // seraient identiques.
        val a = Vec3d(6_371_000.0, 0.0, 0.0)
        val b = Vec3d(6_371_000.01, 0.0, 0.0)
        assertTrue((b - a).length > 0.009, "le centimètre a été perdu")
        assertEquals(a.x.toFloat(), b.x.toFloat(), "le flottant 32 bits devrait, lui, confondre")
    }

    @Test
    fun `la rotation de Rodrigues conserve la norme`() {
        val rng = Rng(53L)
        repeat(2000) {
            val v = Vec3d(
                rng.nextFloatSigned().toDouble(),
                rng.nextFloatSigned().toDouble(),
                rng.nextFloatSigned().toDouble()
            )
            if (v.lengthSq < 1e-6) return@repeat
            val axis = Vec3d(
                rng.nextFloatSigned().toDouble(),
                rng.nextFloatSigned().toDouble(),
                rng.nextFloatSigned().toDouble()
            )
            if (axis.lengthSq < 1e-6) return@repeat
            val angle = rng.nextFloat().toDouble() * 6.0
            val r = v.rotatedAround(axis, angle)
            assertTrue(abs(r.length - v.length) < 1e-9 * v.length, "norme altérée")
        }
    }

    @Test
    fun `rotation d un tour complet ramene au point de depart`() {
        val v = Vec3d(3.0, -1.0, 2.0)
        val r = v.rotatedAround(Vec3d.UNIT_Y, 2.0 * PI)
        assertTrue((r - v).length < 1e-9)
    }

    @Test
    fun `latitude et longitude font un aller retour fidele`() {
        for (latDeg in -85..85 step 5) {
            for (lonDeg in -175..175 step 15) {
                val lat = latDeg * PI / 180.0
                val lon = lonDeg * PI / 180.0
                val p = Geodesy.toUnit(lat, lon)
                assertTrue(abs(Geodesy.latitude(p) - lat) < 1e-12, "latitude $latDeg")
                assertTrue(abs(Geodesy.longitude(p) - lon) < 1e-12, "longitude $lonDeg")
            }
        }
    }

    @Test
    fun `le repere local est orthonorme partout`() {
        val rng = Rng(59L)
        repeat(3000) {
            val lat = (rng.nextFloat() - 0.5f).toDouble() * PI * 0.98
            val lon = (rng.nextFloat() - 0.5f).toDouble() * 2.0 * PI
            val p = Geodesy.toUnit(lat, lon)
            val n = Geodesy.northAt(p)
            val e = Geodesy.eastAt(p)
            assertTrue(abs(n.length - 1.0) < 1e-9, "nord non unitaire")
            assertTrue(abs(e.length - 1.0) < 1e-9, "est non unitaire")
            assertTrue(abs(n dot e) < 1e-9, "nord et est non orthogonaux")
            assertTrue(abs(n dot p) < 1e-9, "nord hors du plan tangent")
            assertTrue(abs(e dot p) < 1e-9, "est hors du plan tangent")
        }
    }

    @Test
    fun `le repere local coincide avec les derivees de la parametrisation`() {
        // Le test qui manquait, et qui aurait attrapé les deux erreurs de signe
        // de la v0.6.0 : l'est rendait le vecteur opposé, et le déplacement
        // tournait dans le mauvais sens.
        //
        // Plutôt que de raisonner sur des intuitions d'orientation, on compare
        // le repère aux dérivées de la paramétrisation elle-même : l'est doit
        // suivre la longitude croissante, le nord la latitude croissante. Toute
        // erreur de signe devient alors impossible à manquer.
        val h = 1e-6
        for (latDeg in -80..80 step 10) {
            for (lonDeg in -170..170 step 20) {
                val lat = latDeg * PI / 180.0
                val lon = lonDeg * PI / 180.0
                val p = Geodesy.toUnit(lat, lon)

                val expectedEast = (Geodesy.toUnit(lat, lon + h) - p).normalized()
                val expectedNorth = (Geodesy.toUnit(lat + h, lon) - p).normalized()

                assertTrue(
                    (Geodesy.eastAt(p) - expectedEast).length < 1e-4,
                    "est erroné à $latDeg°, $lonDeg°"
                )
                assertTrue(
                    (Geodesy.northAt(p) - expectedNorth).length < 1e-4,
                    "nord erroné à $latDeg°, $lonDeg°"
                )
            }
        }
    }

    @Test
    fun `se deplacer vers l est fait croitre la longitude`() {
        val p = Geodesy.toUnit(0.3, 0.2)
        val moved = Geodesy.move(p, Geodesy.eastAt(p), 0.05)
        assertTrue(
            Geodesy.longitude(moved) > Geodesy.longitude(p),
            "un déplacement vers l'est doit augmenter la longitude"
        )
    }

    @Test
    fun `le nord pointe bien vers le pole`() {
        val p = Geodesy.toUnit(0.0, 0.0)
        val moved = Geodesy.move(p, Geodesy.northAt(p), 0.1)
        assertTrue(Geodesy.latitude(moved) > 0.09, "le déplacement vers le nord n'élève pas la latitude")
    }

    @Test
    fun `un deplacement le long de la surface conserve la sphere unite`() {
        val rng = Rng(61L)
        var p = Geodesy.toUnit(0.3, 0.7)
        repeat(500) {
            val dir = if (rng.nextBoolean()) Geodesy.northAt(p) else Geodesy.eastAt(p)
            p = Geodesy.move(p, dir, rng.nextFloat().toDouble() * 0.5)
            assertTrue(abs(p.length - 1.0) < 1e-9, "sortie de la sphère unité")
        }
    }
}

class PlanetCameraTest {

    private val R = 6_371_000.0

    private fun camera(range: Double = 1_000_000.0) =
        PlanetCamera(R, focusLatRad = 0.2, focusLonRad = 0.5, rangeM = range)

    @Test
    fun `sans inclinaison l oeil est a la verticale du point vise`() {
        val c = camera()
        c.tiltRad = 0.0
        val focus = c.focusDirection()
        val eye = c.eyePositionM().normalized()
        assertTrue(
            Geodesy.angleBetween(focus, eye) < 1e-9,
            "l'œil n'est pas à l'aplomb du point visé"
        )
        assertTrue(abs(c.eyeAltitudeM() - c.rangeM) < 1.0)
    }

    @Test
    fun `la distance a l oeil vaut toujours la portee`() {
        val c = camera(50_000.0)
        for (tilt in listOf(0.0, 0.3, 0.7, 1.1)) {
            c.tiltRad = tilt
            val d = (c.eyePositionM() - c.focusPositionM()).length
            assertTrue(abs(d - c.rangeM) < 1e-6, "portée trahie à l'inclinaison $tilt")
        }
    }

    @Test
    fun `le repere camera est orthonorme`() {
        val c = camera(80_000.0)
        c.tiltRad = 0.9
        c.headingRad = 1.3
        val f = c.forward(); val r = c.right(); val u = c.up()
        assertTrue(abs(f.length - 1.0) < 1e-9)
        assertTrue(abs(r.length - 1.0) < 1e-9)
        assertTrue(abs(u.length - 1.0) < 1e-9)
        assertTrue(abs(f dot r) < 1e-9, "visée et droite non orthogonales")
        assertTrue(abs(f dot u) < 1e-9, "visée et haut non orthogonaux")
        assertTrue(abs(r dot u) < 1e-9, "droite et haut non orthogonaux")
    }

    @Test
    fun `la camera vise bien le point d interet`() {
        val c = camera(120_000.0)
        c.tiltRad = 0.8
        val toFocus = (c.focusPositionM() - c.eyePositionM()).normalized()
        assertTrue((toFocus - c.forward()).length < 1e-9, "la visée ne pointe pas le point d'intérêt")
    }

    @Test
    fun `l amplitude du glissement est proportionnelle a la distance`() {
        // La propriété qui rend la navigation utilisable sur sept ordres de
        // grandeur : le même geste couvre le globe depuis l'orbite et quelques
        // mètres au sol.
        val far = PlanetCamera(R, rangeM = 10_000_000.0)
        val near = PlanetCamera(R, rangeM = 100.0)

        val farStart = far.focusDirection()
        val nearStart = near.focusDirection()
        far.pan(300.0, 0.0, 1080.0)
        near.pan(300.0, 0.0, 1080.0)

        val farMoved = Geodesy.angleBetween(farStart, far.focusDirection()) * R
        val nearMoved = Geodesy.angleBetween(nearStart, near.focusDirection()) * R

        assertTrue(nearMoved > 0.5, "aucun déplacement au sol : $nearMoved m")
        assertTrue(farMoved > 100_000.0, "déplacement dérisoire en orbite : $farMoved m")
        val ratio = farMoved / nearMoved
        assertTrue(
            ratio > 50_000.0 && ratio < 200_000.0,
            "proportionnalité rompue : rapport de $ratio pour un rapport de portée de 100 000"
        )
    }

    // Les SIGNES du glissement (quel axe tire le terrain dans quel sens) sont
    // verrouillés dans GesturesTest, et uniquement là. Ils y sont fixés par la
    // mesure sur appareil, pas par raisonnement — le test « glisser vers le
    // bas fait remonter vers le nord » qui vivait ici verrouillait une
    // convention théorique que l'essai a démentie (v0.8.4), et son doublon
    // corrigé aurait recréé deux verrous à désynchroniser. Un seul gardien.

    @Test
    fun `la portee reste dans ses bornes`() {
        val c = camera()
        repeat(200) { c.zoom(0.5) }
        assertEquals(PlanetCamera.MIN_RANGE_M, c.rangeM)
        repeat(400) { c.zoom(2.0) }
        assertEquals(PlanetCamera.MAX_RANGE_M, c.rangeM)
    }

    @Test
    fun `le zoom vers un point s en rapproche sans le depasser`() {
        val c = PlanetCamera(R, focusLatRad = 0.0, focusLonRad = 0.0, rangeM = 1_000_000.0)
        val target = Geodesy.toUnit(0.05, 0.05)
        var previous = Geodesy.angleBetween(c.focusDirection(), target)

        repeat(30) {
            c.zoomTowards(target, 0.7)
            val now = Geodesy.angleBetween(c.focusDirection(), target)
            assertTrue(now <= previous + 1e-12, "le point visé s'est éloigné de la cible")
            previous = now
        }
        assertTrue(previous < 1e-3, "la cible n'a pas été rejointe : $previous rad")
    }

    @Test
    fun `un eloignement ne deplace pas le point vise`() {
        val c = PlanetCamera(R, rangeM = 10_000.0)
        val target = Geodesy.toUnit(0.4, 0.4)
        val before = c.focusDirection()
        c.zoomTowards(target, 1.6)
        assertTrue(
            Geodesy.angleBetween(before, c.focusDirection()) < 1e-12,
            "un éloignement ne doit pas recentrer la vue"
        )
    }

    @Test
    fun `l inclinaison s ouvre en descendant et se ferme en montant`() {
        val c = PlanetCamera(R, rangeM = 20_000_000.0)
        assertEquals(0.0, c.maxTiltRad(), "aucune inclinaison ne devrait être permise en orbite")

        c.rangeM = 20_000.0
        assertTrue(c.maxTiltRad() > 1.3, "inclinaison trop bridée près du sol")

        // Une inclinaison prise au sol doit se replier si l'on remonte.
        c.tiltRad = c.maxTiltRad()
        c.rangeM = 20_000_000.0
        c.tiltRad = c.tiltRad
        assertEquals(0.0, c.tiltRad, "l'inclinaison n'a pas été repliée en remontant")
    }

    @Test
    fun `l inclinaison ne depasse jamais sa limite`() {
        val c = camera(50_000.0)
        repeat(100) { c.tilt(0.2) }
        assertTrue(c.tiltRad <= c.maxTiltRad() + 1e-12)
        repeat(100) { c.tilt(-0.2) }
        assertTrue(c.tiltRad >= 0.0)
    }

    @Test
    fun `le cap reste borne apres de nombreuses rotations`() {
        val c = camera()
        repeat(500) { c.rotate(0.37) }
        assertTrue(c.headingRad >= -PI && c.headingRad <= PI, "cap non normalisé : ${c.headingRad}")
    }

    @Test
    fun `les rayons d ecran couvrent le champ de vision`() {
        val c = camera(200_000.0)
        val centre = c.rayDirection(0.0, 0.0, 16.0 / 9.0)
        assertTrue((centre - c.forward()).length < 1e-9, "le rayon central doit suivre la visée")

        val top = c.rayDirection(0.0, 1.0, 16.0 / 9.0)
        val angle = Geodesy.angleBetween(centre, top)
        val expected = PlanetCamera.DEFAULT_FOV_RAD / 2.0
        assertTrue(abs(angle - expected) < 0.02, "demi-champ vertical erroné : $angle")
    }

    @Test
    fun `la navigation reste stable apres un long parcours`() {
        val c = PlanetCamera(R, rangeM = 5_000_000.0)
        val rng = Rng(67L)
        repeat(3000) {
            c.pan(
                (rng.nextFloatSigned() * 300f).toDouble(),
                (rng.nextFloatSigned() * 300f).toDouble(),
                1080.0
            )
            c.zoom(if (rng.nextBoolean()) 0.9 else 1.1)
            c.rotate(rng.nextFloatSigned().toDouble() * 0.2)
            c.tilt(rng.nextFloatSigned().toDouble() * 0.1)

            assertTrue(c.focusLatRad.isFinite() && c.focusLonRad.isFinite(), "état devenu invalide")
            assertTrue(abs(c.focusLatRad) <= PI / 2 + 1e-9, "latitude hors bornes")
            assertTrue(abs(c.focusLonRad) <= PI + 1e-9, "longitude hors bornes")
            assertTrue(c.eyePositionM().isFinite(), "position d'œil invalide")
        }
    }

    @Test
    fun `passer au dessus du pole ne casse pas le repere`() {
        val c = PlanetCamera(R, focusLatRad = 1.5, focusLonRad = 0.0, rangeM = 500_000.0)
        repeat(60) {
            c.moveFocusMetres(200_000.0, 0.0)   // droit vers le nord, par-dessus le pôle
            assertTrue(c.eyePositionM().isFinite(), "repère rompu au passage du pôle")
            assertTrue(abs(c.focusLatRad) <= PI / 2 + 1e-9)
        }
    }
}

class TerrainRaycasterTest {

    private val world = WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4)).generate()
    private val caster = TerrainRaycaster(world.terrain)
    private val R = world.params.radiusM.toDouble()

    @Test
    fun `un rayon vertical touche le sol a l altitude attendue`() {
        val rng = Rng(71L)
        repeat(120) {
            val lat = (rng.nextFloat() - 0.5f).toDouble() * PI * 0.9
            val lon = (rng.nextFloat() - 0.5f).toDouble() * 2.0 * PI
            val dir = Geodesy.toUnit(lat, lon)
            val origin = dir * (R + 200_000.0)

            val hit = caster.cast(origin, -dir)
            assertNotNull(hit, "aucun impact sur un tir vertical")
            val expected = maxOf(0.0, caster.altitudeAlong(dir))
            assertTrue(
                abs(hit.altitudeM.coerceAtLeast(0.0) - expected) < 5.0,
                "altitude d'impact erronée : ${hit.altitudeM} au lieu de $expected"
            )
            assertTrue(
                Geodesy.angleBetween(hit.direction, dir) < 1e-6,
                "l'impact a dérivé latéralement sur un tir vertical"
            )
        }
    }

    @Test
    fun `le point d impact repose bien sur la surface`() {
        val rng = Rng(73L)
        var tested = 0
        repeat(200) {
            val lat = (rng.nextFloat() - 0.5f).toDouble() * PI * 0.9
            val lon = (rng.nextFloat() - 0.5f).toDouble() * 2.0 * PI
            val dir = Geodesy.toUnit(lat, lon)
            val hit = caster.cast(dir * (R + 50_000.0), -dir) ?: return@repeat
            tested++
            assertTrue(
                abs(caster.heightAboveTerrain(hit.positionM)) < 2.0,
                "l'impact flotte ou s'enfonce : ${caster.heightAboveTerrain(hit.positionM)} m"
            )
        }
        assertTrue(tested > 150, "trop peu d'impacts obtenus : $tested")
    }

    @Test
    fun `un rayon dirige vers l espace ne touche rien`() {
        val dir = Geodesy.toUnit(0.3, 0.3)
        assertNull(caster.cast(dir * (R + 1000.0), dir), "impact fantôme vers le ciel")
    }

    @Test
    fun `un rayon qui manque la planete ne touche rien`() {
        val origin = Vec3d(0.0, 0.0, R * 5.0)
        val direction = Vec3d(1.0, 0.0, 0.0)      // tangentiel, très au large
        assertNull(caster.cast(origin, direction), "impact fantôme hors de la planète")
    }

    @Test
    fun `un rayon oblique touche entre l origine et l antipode`() {
        val rng = Rng(79L)
        var hits = 0
        repeat(150) {
            val dir = Geodesy.toUnit(
                (rng.nextFloat() - 0.5f).toDouble() * 2.0,
                (rng.nextFloat() - 0.5f).toDouble() * 6.0
            )
            val origin = dir * (R + 300_000.0)
            val east = Geodesy.eastAt(dir)
            val aim = (-dir * 0.8 + east * 0.6).normalized()
            val hit = caster.cast(origin, aim) ?: return@repeat
            hits++
            assertTrue(hit.distanceM > 0.0, "distance d'impact nulle")
            assertTrue(hit.distanceM < R * 4.0, "impact anormalement lointain")
            assertTrue(
                abs(caster.heightAboveTerrain(hit.positionM)) < 6.0,
                "impact oblique mal posé sur la surface"
            )
        }
        assertTrue(hits > 100, "trop peu d'impacts obliques : $hits")
    }

    @Test
    fun `l intersection avec la sphere du niveau de la mer est exacte`() {
        val dir = Geodesy.toUnit(0.1, 2.0)
        val origin = dir * (R + 1_000_000.0)
        val p = caster.castSeaLevel(origin, -dir)
        assertNotNull(p)
        assertTrue(abs(p.length - R) < 1e-3, "le point n'est pas au niveau de la mer")
    }

    @Test
    fun `la hauteur au dessus du terrain est nulle au niveau du sol`() {
        val rng = Rng(83L)
        repeat(300) {
            val dir = Geodesy.toUnit(
                (rng.nextFloat() - 0.5f).toDouble() * 3.0,
                (rng.nextFloat() - 0.5f).toDouble() * 6.0
            )
            val ground = dir * (R + maxOf(0.0, caster.altitudeAlong(dir)))
            assertTrue(abs(caster.heightAboveTerrain(ground)) < 0.5)
            assertTrue(caster.heightAboveTerrain(ground * 1.0001) > 0.0)
        }
    }

    @Test
    fun `le lancer converge en un nombre raisonnable d iterations`() {
        val rng = Rng(89L)
        var worst = 0
        repeat(120) {
            val dir = Geodesy.toUnit(
                (rng.nextFloat() - 0.5f).toDouble() * 3.0,
                (rng.nextFloat() - 0.5f).toDouble() * 6.0
            )
            val hit = caster.cast(dir * (R + 400_000.0), -dir) ?: return@repeat
            if (hit.iterations > worst) worst = hit.iterations
        }
        assertTrue(worst in 1..300, "convergence trop lente : $worst itérations")
    }
}

class CameraTerrainIntegrationTest {

    private val world = WorldGenerator.fromName("Orion", PlanetParams(subdivisions = 4)).generate()
    private val caster = TerrainRaycaster(world.terrain)
    private val R = world.params.radiusM.toDouble()

    @Test
    fun `l ancrage pose le point vise sur le relief`() {
        val rng = Rng(97L)
        repeat(60) {
            val c = PlanetCamera(
                R,
                focusLatRad = (rng.nextFloat() - 0.5f).toDouble() * 2.6,
                focusLonRad = (rng.nextFloat() - 0.5f).toDouble() * 6.0,
                rangeM = 5_000.0
            )
            c.snapToTerrain(caster)
            val expected = maxOf(0.0, caster.altitudeAlong(c.focusDirection()))
            assertTrue(
                abs(c.focusGroundAltitudeM - expected) < 1.0,
                "le point visé ne repose pas sur le sol"
            )
        }
    }

    @Test
    fun `la camera ne traverse jamais le terrain`() {
        // Le cas qui compte : viser une vallée depuis un col, très incliné.
        val rng = Rng(101L)
        var checked = 0
        repeat(120) {
            val c = PlanetCamera(
                R,
                focusLatRad = (rng.nextFloat() - 0.5f).toDouble() * 2.6,
                focusLonRad = (rng.nextFloat() - 0.5f).toDouble() * 6.0,
                rangeM = 300.0,
                headingRad = rng.nextFloat().toDouble() * 6.0
            )
            c.snapToTerrain(caster)
            c.tiltRad = c.maxTiltRad()
            c.snapToTerrain(caster)

            val clearance = caster.heightAboveTerrain(c.eyePositionM())
            checked++
            assertTrue(
                clearance > -0.5,
                "l'œil est enfoncé de ${-clearance} m dans le terrain"
            )
        }
        assertTrue(checked > 100)
    }

    @Test
    fun `un rayon central depuis la camera retombe sur le point vise`() {
        val c = PlanetCamera(R, focusLatRad = 0.4, focusLonRad = 1.1, rangeM = 30_000.0)
        c.snapToTerrain(caster)
        c.tiltRad = 0.6
        c.snapToTerrain(caster)

        val hit = caster.cast(c.eyePositionM(), c.rayDirection(0.0, 0.0, 16.0 / 9.0))
        assertNotNull(hit, "le rayon central ne touche pas le sol")
        val drift = Geodesy.angleBetween(hit.direction, c.focusDirection()) * R
        assertTrue(drift < 500.0, "le centre de l'écran dérive de $drift m du point visé")
    }

    @Test
    fun `descendre de l orbite au sol conserve un etat valide`() {
        val c = PlanetCamera(R, focusLatRad = 0.35, focusLonRad = 0.9, rangeM = 30_000_000.0)
        var previous = c.rangeM
        repeat(120) {
            c.zoom(0.85)
            c.tilt(0.02)
            c.snapToTerrain(caster)
            assertTrue(c.rangeM <= previous + 1.0 || c.rangeM > previous, "portée incohérente")
            assertTrue(c.eyePositionM().isFinite(), "position d'œil invalide")
            assertTrue(caster.heightAboveTerrain(c.eyePositionM()) > -1.0, "traversée du sol")
            previous = c.rangeM
        }
        assertTrue(c.rangeM < 10_000.0, "la descente n'a pas abouti : ${c.rangeM} m")
    }
}
