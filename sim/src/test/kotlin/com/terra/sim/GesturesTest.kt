package com.terra.sim

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Conventions de gestes, fixées par l'essai sur appareil.
 *
 * Ces tests ne prouvent pas que la convention est « la bonne » — aucun test
 * JVM ne le peut, seul un doigt sur l'écran tranche. Ils la **figent** : si
 * un refactor retourne un signe, ils rougissent, et la correction se refait
 * consciemment plutôt que de ressurgir en rapport de bug.
 */
class GesturesTest {

    private fun camera() = PlanetCamera(
        6_371_000.0,
        focusLatRad = 0.2,
        focusLonRad = 0.4,
        rangeM = 10_000.0
    )

    @Test
    fun `glisser vers le bas fait venir le terrain du haut de l ecran`() {
        // Convention carte, mesurée sur appareil (v0.8.4) : le terrain suit le
        // doigt. Doigt vers le bas (dy > 0 en coordonnées écran Android), cap
        // nul : le point visé recule vers le sud local — la latitude diminue.
        val cam = camera()
        val before = cam.focusLatRad
        cam.pan(0.0, 300.0, 2000.0)
        assertTrue(
            cam.focusLatRad < before,
            "dy > 0 doit réduire la latitude (cap nul) : ${cam.focusLatRad} vs $before"
        )
    }

    @Test
    fun `glisser vers la droite fait venir le terrain de droite`() {
        // L'axe horizontal était correct dès la première version : ce test
        // l'empêche de se dérégler pendant qu'on corrige l'autre.
        val cam = camera()
        val before = cam.focusLonRad
        cam.pan(300.0, 0.0, 2000.0)
        assertTrue(
            cam.focusLonRad < before,
            "dx > 0 doit réduire la longitude (cap nul) : ${cam.focusLonRad} vs $before"
        )
    }

    @Test
    fun `les deux axes se compensent par un aller-retour`() {
        // Quatre glissements en carré ramènent près du départ : détecte une
        // asymétrie d'échelle entre axes que les deux tests de signe ne
        // verraient pas.
        val cam = camera()
        val lat0 = cam.focusLatRad
        val lon0 = cam.focusLonRad
        cam.pan(250.0, 0.0, 2000.0)
        cam.pan(0.0, 250.0, 2000.0)
        cam.pan(-250.0, 0.0, 2000.0)
        cam.pan(0.0, -250.0, 2000.0)
        assertTrue(
            kotlin.math.abs(cam.focusLatRad - lat0) < 1e-5 &&
                    kotlin.math.abs(cam.focusLonRad - lon0) < 1e-5,
            "l'aller-retour ne revient pas au départ"
        )
    }
}

/**
 * Arithmétique de parenté sur clés compactées — le socle du repli sur
 * l'ancêtre. Testée face à la version objet : les deux écritures doivent
 * coïncider sur toute la hiérarchie, sinon les trous de couverture (v0.8.6)
 * reviendraient sans qu'aucun test ne rougisse.
 */
class TileKeyTest {

    @Test
    fun `parentKey coincide avec la version objet sur toute la hierarchie`() {
        val rng = kotlin.random.Random(13)
        repeat(2000) {
            val level = rng.nextInt(0, TileId.MAX_LEVEL + 1)
            val grid = 1 shl level
            val tile = TileId(rng.nextInt(6), level, rng.nextInt(grid), rng.nextInt(grid))
            val fromKey = TileId.parentKey(tile.packed())
            val fromObject = tile.parent?.packed() ?: -1L
            kotlin.test.assertEquals(fromObject, fromKey, "divergence pour $tile")
        }
    }

    @Test
    fun `la remontee atteint la racine puis s arrete`() {
        var key = TileId(3, TileId.MAX_LEVEL, 1234, 4321).packed()
        var steps = 0
        while (key != -1L) {
            key = TileId.parentKey(key)
            steps++
            assertTrue(steps <= TileId.MAX_LEVEL + 1, "remontée sans fin")
        }
        kotlin.test.assertEquals(TileId.MAX_LEVEL + 1, steps)
    }
}

/**
 * Couverture du premier plan — v0.9.5. Au ras du sol en vue rasante, les
 * tuiles qui contiennent l'observateur ont leur centre derrière l'œil : sans
 * garde-fou de proximité, le cône les rejetait et le bas de l'écran montrait
 * le fond de brume au lieu du sol (rapport d'essai, capture à l'appui).
 */
class NadirCoverageTest {

    @Test
    fun `le point sous la camera est toujours couvert en vue rasante`() {
        val selector = TileSelector()
        val out = ArrayList<TileId>()
        val rng = kotlin.random.Random(31)

        repeat(24) {
            // Position au ras du sol, direction de visée tangente (rasante).
            val d = randomUnit(rng)
            val t0 = randomUnit(rng)
            val fwd = com.terra.core.Vec3(
                t0.x - d.x * (t0.x * d.x + t0.y * d.y + t0.z * d.z),
                t0.y - d.y * (t0.x * d.x + t0.y * d.y + t0.z * d.z),
                t0.z - d.z * (t0.x * d.x + t0.y * d.y + t0.z * d.z)
            ).normalized()
            val eyeAltUnit = 2f / 6_371_000f
            val cam = com.terra.core.Vec3(
                d.x * (1f + eyeAltUnit), d.y * (1f + eyeAltUnit), d.z * (1f + eyeAltUnit)
            )
            val cone = ViewCone.fromCamera(cam, fwd, 0.733f, 2.2f)
            selector.select(cam, out, cone)

            val (face, sN, tN) = CubeSphere.fromSphere(d)
            val covered = out.any { tile ->
                if (tile.face != face) return@any false
                val grid = 1 shl tile.level
                val tx = (((sN + 1f) * 0.5f) * grid).toInt().coerceIn(0, grid - 1)
                val ty = (((tN + 1f) * 0.5f) * grid).toInt().coerceIn(0, grid - 1)
                tile.x == tx && tile.y == ty
            }
            assertTrue(covered, "nadir non couvert à l'essai $it (${out.size} tuiles)")
        }
    }

    private fun randomUnit(rng: kotlin.random.Random): com.terra.core.Vec3 {
        while (true) {
            val x = rng.nextFloat() * 2f - 1f
            val y = rng.nextFloat() * 2f - 1f
            val z = rng.nextFloat() * 2f - 1f
            val l = x * x + y * y + z * z
            if (l in 1e-4f..1f) {
                val inv = 1f / kotlin.math.sqrt(l)
                return com.terra.core.Vec3(x * inv, y * inv, z * inv)
            }
        }
    }
}


/**
 * Le niveau de subdivision doit suivre la hauteur au-dessus du SOL — et non
 * l'altitude au-dessus du niveau de la mer (v0.10.5). Sur un plateau élevé,
 * la seconde faisait plafonner le détail très au-dessus de ce que l'œil
 * réclame.
 */
class GroundRelativeDetailTest {

    @Test
    fun `a hauteur egale le niveau ne depend pas de l altitude du plateau`() {
        val selector = TileSelector()
        val out = ArrayList<TileId>()
        val r = 6_371_000.0

        fun deepestAt(plateauM: Double, heightM: Double): Int {
            val eyeLen = ((r + plateauM + heightM) / r).toFloat()
            val cam = com.terra.core.Vec3(0f, eyeLen, 0f)
            selector.groundRadiusUnit = ((r + plateauM) / r).toFloat()
            selector.select(cam, out, null)
            var deepest = 0
            for (t in out) if (t.level > deepest) deepest = t.level
            return deepest
        }

        // Dix mètres au-dessus du sol : même finesse, que le sol soit au
        // niveau de la mer ou à 2 000 m d'altitude.
        val auNiveauDeLaMer = deepestAt(0.0, 10.0)
        val surPlateau = deepestAt(2_000.0, 10.0)
        assertTrue(
            kotlin.math.abs(auNiveauDeLaMer - surPlateau) <= 1,
            "niveau $auNiveauDeLaMer au niveau de la mer contre $surPlateau sur plateau"
        )

        // Et la finesse doit bien croître quand on descend.
        assertTrue(
            deepestAt(2_000.0, 10.0) > deepestAt(2_000.0, 10_000.0),
            "le niveau ne s'affine pas en descendant"
        )
    }
}
