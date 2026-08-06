package com.terra.sim

import com.terra.core.Vec3
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Couverture du champ de vision — outil de diagnostic, v0.10.0.
 *
 * ## Pourquoi ce test existe
 *
 * Un défaut signalé à l'essai résiste : au ras du sol, en visée rasante, le
 * bas de l'écran ne montre pas de terrain. Un premier correctif visant le
 * cône de vision (v0.9.5) n'a pas suffi, et les compteurs du HUD indiquent
 * que tuiles sélectionnées et tuiles dessinées coïncident — le défaut est
 * donc ailleurs que là où j'avais regardé.
 *
 * Plutôt que de corriger à l'aveugle une seconde fois, ce test reproduit la
 * situation exacte et **échantillonne l'écran** : pour chaque direction du
 * champ de vision, on lance un rayon vers la sphère et l'on vérifie que le
 * point visé appartient à une tuile sélectionnée. Un échec localise la
 * région manquante de l'écran ; un succès innocente la sélection et désigne
 * le rendu.
 */
class ScreenCoverageTest {

    private val selector = TileSelector()

    /**
     * Intersection d'un rayon avec la sphère unité, ou null si le rayon la
     * manque (direction au-dessus de l'horizon).
     */
    private fun hitSphere(origin: Vec3, dir: Vec3): Vec3? {
        val b = origin.x * dir.x + origin.y * dir.y + origin.z * dir.z
        val c = origin.x * origin.x + origin.y * origin.y + origin.z * origin.z - 1f
        val disc = b * b - c
        if (disc < 0f) return null
        val t = -b - sqrt(disc)
        if (t < 0f) return null
        return Vec3(origin.x + dir.x * t, origin.y + dir.y * t, origin.z + dir.z * t)
            .normalized()
    }

    /** Vrai si le point est couvert par l'une des tuiles sélectionnées. */
    private fun covered(point: Vec3, tiles: List<TileId>): Boolean {
        val (face, s, t) = CubeSphere.fromSphere(point)
        for (tile in tiles) {
            if (tile.face != face) continue
            val grid = 1 shl tile.level
            val x = (((s + 1f) * 0.5f) * grid).toInt().coerceIn(0, grid - 1)
            val y = (((t + 1f) * 0.5f) * grid).toInt().coerceIn(0, grid - 1)
            if (tile.x == x && tile.y == y) return true
        }
        return false
    }

    /**
     * Balaie le champ de vision et rend la fraction de directions visant le
     * sol qui ne sont couvertes par aucune tuile, avec la pire élévation.
     */
    private fun scan(altitudeM: Double, tiltFromHorizontal: Double): Pair<Float, Double> {
        val r = 6_371_000.0
        val camLen = ((r + altitudeM) / r).toFloat()
        val up = Vec3(0f, 1f, 0f)
        val cam = Vec3(0f, camLen, 0f)

        // Visée : depuis l'horizontale locale, inclinée vers le bas.
        val east = Vec3(1f, 0f, 0f)
        val fwd = Vec3(
            (east.x * kotlin.math.cos(tiltFromHorizontal) - up.x * kotlin.math.sin(tiltFromHorizontal)).toFloat(),
            (east.y * kotlin.math.cos(tiltFromHorizontal) - up.y * kotlin.math.sin(tiltFromHorizontal)).toFloat(),
            (east.z * kotlin.math.cos(tiltFromHorizontal) - up.z * kotlin.math.sin(tiltFromHorizontal)).toFloat()
        ).normalized()

        val fovRad = 0.733f
        val aspect = 2.2f
        val cone = ViewCone.fromCamera(cam, fwd, fovRad, aspect)
        val tiles = ArrayList<TileId>()
        // Comme le renderer : les distances se jugent par rapport au sol, pas
        // au niveau de la mer. Ici le terrain d'essai EST la sphère unité, la
        // hauteur au-dessus du sol vaut donc l'altitude.
        selector.groundRadiusUnit = 1f
        selector.select(cam, tiles, cone)

        // Repère caméra pour balayer le rectangle de l'écran.
        val right = Vec3(
            fwd.y * up.z - fwd.z * up.y,
            fwd.z * up.x - fwd.x * up.z,
            fwd.x * up.y - fwd.y * up.x
        ).normalized()
        val camUp = Vec3(
            right.y * fwd.z - right.z * fwd.y,
            right.z * fwd.x - right.x * fwd.z,
            right.x * fwd.y - right.y * fwd.x
        ).normalized()

        val tanHalf = tan(fovRad * 0.5f)
        var ground = 0
        var missing = 0
        var worstNdcY = 0.0

        for (iy in 0..20) {
            val ndcY = -1f + 2f * iy / 20f
            for (ix in 0..20) {
                val ndcX = -1f + 2f * ix / 20f
                val dir = Vec3(
                    fwd.x + right.x * ndcX * tanHalf * aspect + camUp.x * ndcY * tanHalf,
                    fwd.y + right.y * ndcX * tanHalf * aspect + camUp.y * ndcY * tanHalf,
                    fwd.z + right.z * ndcX * tanHalf * aspect + camUp.z * ndcY * tanHalf
                ).normalized()
                val hit = hitSphere(cam, dir) ?: continue
                ground++
                if (!covered(hit, tiles)) {
                    missing++
                    if (ndcY < worstNdcY) worstNdcY = ndcY.toDouble()
                }
            }
        }
        return Pair(if (ground == 0) 0f else missing.toFloat() / ground, worstNdcY)
    }

    @Test
    fun `le champ de vision est entierement couvert en visee rasante`() {
        // Les cas d'essai signalés : 1,8 km et le ras du sol, visée presque
        // horizontale (le tilt s'ouvre en descendant).
        for (alt in doubleArrayOf(3.0, 500.0, 1_800.0, 27_000.0)) {
            for (tilt in doubleArrayOf(0.05, 0.25, 0.6)) {
                val (missingFraction, worstNdcY) = scan(alt, tilt)
                assertTrue(
                    missingFraction == 0f,
                    "alt $alt m, inclinaison $tilt rad : ${(missingFraction * 100).toInt()} % " +
                            "du sol visé sans tuile (pire à ndcY=$worstNdcY, −1 = bas de l'écran)"
                )
            }
        }
    }
}

/**
 * Plan de coupe proche — le vrai coupable du premier plan manquant (v0.10.1).
 *
 * Ces tests portent sur la **propriété** (voit-on le sol sous ses pieds ?)
 * plutôt que sur la formule : c'est elle qui compte, et c'est elle qui était
 * violée quand le plan se calculait sur l'altitude marine.
 */
class NearPlaneTest {

    /**
     * Distance œil-sol au bas de l'écran, en visée horizontale : le rayon du
     * bord inférieur descend de la moitié du champ vertical.
     */
    private fun groundDistanceAtScreenBottom(heightM: Double): Double =
        heightM / kotlin.math.sin(PlanetCamera.DEFAULT_FOV_RAD * 0.5)

    @Test
    fun `le sol au bas de l ecran est toujours devant le plan de coupe`() {
        // Le cas signalé à l'essai : caméra ancrée à deux mètres du sol sur un
        // plateau de 390 m. L'ancien calcul plaçait le plan à 7,8 m pour un
        // sol visible à 5,6 m — tout le premier plan disparaissait.
        for (height in doubleArrayOf(2.0, 5.0, 20.0, 100.0, 450.0, 27_000.0)) {
            val near = PlanetCamera.nearPlaneFor(height, height * 3.0)
            val groundDist = groundDistanceAtScreenBottom(height)
            assertTrue(
                near < groundDist,
                "hauteur $height m : plan de coupe à $near m devant un sol visible à $groundDist m"
            )
        }
    }

    @Test
    fun `le plan de coupe reste sous le dixieme de la hauteur`() {
        for (height in doubleArrayOf(2.0, 50.0, 1_000.0)) {
            assertTrue(PlanetCamera.nearPlaneFor(height, height * 3.0) <= height * 0.1)
        }
    }

    @Test
    fun `une hauteur aberrante ne peut plus agrandir le plan de coupe`() {
        // Le défaut de la v0.10.3 : la hauteur transmise valait l'altitude
        // marine (des centaines de mètres) au lieu de la hauteur réelle. La
        // portée, seconde borne, plafonne désormais le résultat.
        val aberrante = 900.0
        val portee = 4.0
        val near = PlanetCamera.nearPlaneFor(aberrante, portee)
        assertTrue(near <= portee * 0.1, "plan de coupe à $near m pour une portée de $portee m")
    }

    @Test
    fun `le plan de coupe ne descend jamais sous dix centimetres`() {
        // Plancher indispensable : la précision du tampon de profondeur
        // s'effondre avec le rapport lointain/proche.
        for (height in doubleArrayOf(0.0, 0.5, 1.0, 2.0)) {
            assertTrue(PlanetCamera.nearPlaneFor(height, 1e6) >= 0.1)
        }
    }
}
