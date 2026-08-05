package com.terra.sim

import com.terra.core.Rng
import com.terra.core.Seed
import com.terra.core.Sphere
import com.terra.core.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CubeSphereTest {

    @Test
    fun `l aller retour restitue exactement la position`() {
        // Sur une arête ou un coin, plusieurs faces sont valides et les
        // coordonnées peuvent différer. Ce qui doit être invariant, et ce dont
        // dépend le rendu, c'est la position reconstruite.
        var worst = 0f
        for (face in 0 until CubeSphere.FACE_COUNT) {
            for (i in 0..24) {
                for (j in 0..24) {
                    val s = -1f + 2f * i / 24
                    val t = -1f + 2f * j / 24
                    val p = CubeSphere.toSphere(face, s, t)
                    val (f2, s2, t2) = CubeSphere.fromSphere(p)
                    val q = CubeSphere.toSphere(f2, s2, t2)
                    val d = (p - q).length
                    if (d > worst) worst = d
                }
            }
        }
        assertTrue(worst < 1e-4f, "écart maximal de reconstruction : $worst")
    }

    @Test
    fun `tous les points projetes sont sur la sphere unite`() {
        for (face in 0 until CubeSphere.FACE_COUNT) {
            for (i in 0..12) for (j in 0..12) {
                val p = CubeSphere.toSphere(face, -1f + 2f * i / 12, -1f + 2f * j / 12)
                assertTrue(abs(p.length - 1f) < 1e-5f, "hors sphère : ${p.length}")
            }
        }
    }

    @Test
    fun `la deformation tangente egalise les surfaces`() {
        // Sans déformation, les cellules du centre d'une face couvrent près de
        // cinq fois celles des coins. Avec, le rapport tombe sous 1,5 — c'est
        // ce qui rend le critère de subdivision homogène sur toute la planète.
        val m = 16
        val areas = ArrayList<Float>()
        for (i in 0 until m) for (j in 0 until m) {
            val s0 = -1f + 2f * i / m; val s1 = -1f + 2f * (i + 1) / m
            val t0 = -1f + 2f * j / m; val t1 = -1f + 2f * (j + 1) / m
            val a = CubeSphere.toSphere(4, s0, t0)
            val b = CubeSphere.toSphere(4, s1, t0)
            val c = CubeSphere.toSphere(4, s1, t1)
            val d = CubeSphere.toSphere(4, s0, t1)
            areas.add(sphericalQuadArea(a, b, c, d))
        }
        val ratio = areas.max() / areas.min()
        assertTrue(ratio < 1.8f, "distorsion de surface trop forte : $ratio")
    }

    private fun sphericalQuadArea(a: Vec3, b: Vec3, c: Vec3, d: Vec3): Float =
        sphericalTriangleArea(a, b, c) + sphericalTriangleArea(a, c, d)

    private fun sphericalTriangleArea(p: Vec3, q: Vec3, r: Vec3): Float {
        val a = Sphere.geodesic(q, r)
        val b = Sphere.geodesic(p, r)
        val c = Sphere.geodesic(p, q)
        val s = (a + b + c) / 2f
        val prod = kotlin.math.tan(s / 2f) * kotlin.math.tan((s - a) / 2f) *
                kotlin.math.tan((s - b) / 2f) * kotlin.math.tan((s - c) / 2f)
        if (prod <= 0f) return 0f
        return 4f * kotlin.math.atan(kotlin.math.sqrt(prod))
    }

    @Test
    fun `les six faces couvrent la sphere sans trou`() {
        val rng = Rng(31L)
        val seen = BooleanArray(6)
        repeat(4000) {
            val p = Sphere.randomPoint(rng)
            val (face, s, t) = CubeSphere.fromSphere(p)
            assertTrue(face in 0..5, "face invalide : $face")
            assertTrue(s in -1.0001f..1.0001f, "s hors bornes : $s")
            assertTrue(t in -1.0001f..1.0001f, "t hors bornes : $t")
            seen[face] = true
            val q = CubeSphere.toSphere(face, s, t)
            assertTrue((p - q).length < 1e-4f, "reconstruction fautive")
        }
        assertTrue(seen.all { it }, "certaines faces ne sont jamais atteintes")
    }
}

class TileIdTest {

    @Test
    fun `la hierarchie parent enfant est coherente`() {
        val root = TileId(2, 0, 0, 0)
        assertTrue(root.parent == null)
        val children = root.children()
        assertNotNull(children)
        assertEquals(4, children.size)
        for (c in children) {
            assertEquals(1, c.level)
            assertEquals(root, c.parent)
        }
    }

    @Test
    fun `les enfants pavent exactement le parent`() {
        val parent = TileId(0, 3, 5, 2)
        val children = parent.children()!!
        val sMin = children.minOf { it.s0 }
        val sMax = children.maxOf { it.s1 }
        val tMin = children.minOf { it.t0 }
        val tMax = children.maxOf { it.t1 }
        assertTrue(abs(sMin - parent.s0) < 1e-6f)
        assertTrue(abs(sMax - parent.s1) < 1e-6f)
        assertTrue(abs(tMin - parent.t0) < 1e-6f)
        assertTrue(abs(tMax - parent.t1) < 1e-6f)
    }

    @Test
    fun `l identifiant compact fait un aller retour fidele`() {
        val tiles = listOf(
            TileId(0, 0, 0, 0),
            TileId(5, 23, (1 shl 23) - 1, 0),
            TileId(3, 12, 1234, 3210),
            TileId(2, 7, 65, 12)
        )
        for (t in tiles) assertEquals(t, TileId.unpack(t.packed()), "échec sur $t")
    }

    @Test
    fun `la taille d arete decroit de moitie par niveau`() {
        val r = 6_371_000f
        var previous = TileId(0, 0, 0, 0).edgeLengthM(r)
        for (level in 1..20) {
            val e = TileId(0, level, 0, 0).edgeLengthM(r)
            assertTrue(abs(e * 2f - previous) < previous * 1e-3f, "niveau $level")
            previous = e
        }
        // Au niveau 23, une tuile mesure environ un mètre.
        assertTrue(TileId(0, 23, 0, 0).edgeLengthM(r) < 2f)
    }

    @Test
    fun `la selection reste bornee a toutes les altitudes`() {
        // La propriété centrale du quadtree : descendre de l'orbite au ras du
        // sol multiplie la charge par quelques dizaines, pas par des milliards.
        val radius = 6_371_000f
        val direction = Sphere.toVec(0.3f, 0.8f)

        var previous = 0
        for (altitudeM in listOf(
            10_000_000f, 1_000_000f, 100_000f, 10_000f, 1_000f, 100f, 10f, 2f
        )) {
            val camera = direction * ((radius + altitudeM) / radius)
            val tiles = TileId.select(camera)
            assertTrue(tiles.isNotEmpty(), "aucune tuile à $altitudeM m")
            assertTrue(
                tiles.size < 2500,
                "explosion du nombre de tuiles à $altitudeM m : ${tiles.size}"
            )
            assertTrue(tiles.size >= previous - 40, "chute anormale à $altitudeM m")
            previous = tiles.size
        }
    }

    @Test
    fun `au ras du sol le detail atteint l echelle metrique`() {
        val radius = 6_371_000f
        val direction = Sphere.toVec(0.1f, 0.2f)
        val camera = direction * ((radius + 2f) / radius)
        val tiles = TileId.select(camera)
        val deepest = tiles.maxOf { it.level }
        assertTrue(deepest >= 20, "profondeur insuffisante : niveau $deepest")
        assertTrue(
            TileId(0, deepest, 0, 0).edgeLengthM(radius) < 15f,
            "tuiles trop grosses au sol"
        )
    }

    @Test
    fun `depuis l espace lointain la planete tient en quelques tuiles`() {
        val direction = Sphere.toVec(0f, 0f)
        val camera = direction * 40f          // quarante rayons planétaires
        val tiles = TileId.select(camera)
        assertTrue(tiles.size <= 24, "trop de tuiles depuis l'espace : ${tiles.size}")
    }

    @Test
    fun `la selection ne retient que des tuiles visibles`() {
        val radius = 6_371_000f
        val direction = Sphere.toVec(0.4f, 1.1f)
        val camera = direction * ((radius + 1000f) / radius)
        for (tile in TileId.select(camera)) {
            assertTrue(tile.isVisible(camera), "$tile retenue alors qu'invisible")
        }
    }

    @Test
    fun `l elimination par l horizon ecarte la face opposee`() {
        val camera = Vec3(0f, 0f, 1.0002f)     // très basse altitude
        val far = TileId(CubeSphere.FACE_NEG_Z, 3, 4, 4)
        assertTrue(!far.isVisible(camera), "l'antipode devrait être masqué")
        val near = TileId(CubeSphere.FACE_POS_Z, 3, 4, 4)
        assertTrue(near.isVisible(camera), "la tuile sous la caméra devrait être visible")
    }
}

/**
 * Le test le plus important du lot.
 *
 * Il vérifie que le terrain évaluable et la grille simulée sont bien la même
 * fonction, et non deux approximations voisines. C'est cette identité qui
 * élimine par construction les coutures et les sauts visuels entre niveaux de
 * détail, au lieu d'avoir à les rattraper après coup.
 */
class TerrainProfileTest {

    private fun world(name: String, sub: Int = 4) =
        WorldGenerator.fromName(name, PlanetParams(subdivisions = sub)).generate()

    @Test
    fun `le terrain evaluable rend exactement la grille simulee`() {
        for (name in listOf("Gaia", "Orion", "Vesta")) {
            val w = world(name)
            for (i in 0 until w.vertexCount) {
                assertEquals(
                    w.altitudeM[i],
                    w.terrain.altitudeAt(w.position(i)),
                    "divergence au sommet $i du monde $name"
                )
            }
        }
    }

    @Test
    fun `l altitude est continue entre deux points voisins`() {
        // Deux points distants d'un mètre ne peuvent pas différer de plus de
        // quelques mètres d'altitude : au-delà, le terrain serait une falaise
        // verticale partout, signe d'un bruit mal échelonné.
        val w = world("Thule")
        val rng = Rng(19L)
        repeat(3000) {
            val p = Sphere.randomPoint(rng)
            val tangent = (Vec3.UNIT_Y cross p).normalized()
            val q = (p + tangent * (1f / 6_371_000f)).normalized()
            val delta = abs(w.terrain.altitudeAt(p) - w.terrain.altitudeAt(q))
            assertTrue(delta < 25f, "dénivelé de $delta m sur un mètre")
        }
    }

    @Test
    fun `le detail ne bosselle jamais la mer`() {
        val w = world("Nyx")
        val rng = Rng(23L)
        repeat(4000) {
            val p = Sphere.randomPoint(rng)
            val base = w.terrain.altitudeAt(p)
            if (base > 0f) return@repeat
            assertEquals(
                base,
                w.terrain.detailedAltitudeAt(p, 200f),
                "le détail a modifié une cellule marine"
            )
        }
    }

    @Test
    fun `le detail reste borne et s efface pres du rivage`() {
        val w = world("Alpha")
        val rng = Rng(29L)
        var maxShift = 0f
        repeat(4000) {
            val p = Sphere.randomPoint(rng)
            val base = w.terrain.altitudeAt(p)
            if (base <= 0f) return@repeat
            val shift = abs(w.terrain.detailedAltitudeAt(p, 150f) - base)
            if (base < 20f) {
                assertTrue(shift < 40f, "détail trop marqué sur une plage : $shift m")
            }
            if (shift > maxShift) maxShift = shift
        }
        assertTrue(maxShift < 200f, "amplitude de détail hors contrôle : $maxShift m")
        assertTrue(maxShift > 1f, "le détail ne produit aucun effet")
    }

    @Test
    fun `l amplitude de detail croit avec le niveau sans jamais sauter`() {
        val w = world("Beta", sub = 3)
        var previous = 0f
        for (level in 0..TileId.MAX_LEVEL) {
            val a = w.terrain.detailAmplitudeForLevel(level)
            assertTrue(a >= previous, "l'amplitude recule au niveau $level")
            assertTrue(a - previous <= 8f, "saut d'amplitude au niveau $level")
            previous = a
        }
        assertEquals(0f, w.terrain.detailAmplitudeForLevel(0))
    }

    @Test
    fun `le profil est deterministe`() {
        val a = world("Kaleth", sub = 3)
        val b = world("Kaleth", sub = 3)
        val rng = Rng(37L)
        repeat(2000) {
            val p = Sphere.randomPoint(rng)
            assertEquals(a.terrain.altitudeAt(p), b.terrain.altitudeAt(p))
        }
    }
}

class CoarseSamplerTest {

    private val world = WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4)).generate()

    @Test
    fun `la recherche par descente trouve le vrai sommet le plus proche`() {
        val sampler = CoarseSampler(world)
        val rng = Rng(41L)
        repeat(1500) {
            val p = Sphere.randomPoint(rng)

            var bestDot = p dot world.position(0)
            for (i in 1 until world.vertexCount) {
                val d = p dot world.position(i)
                if (d > bestDot) bestDot = d
            }

            val walked = sampler.nearestVertex(p)
            // On compare les distances plutôt que les indices : deux sommets
            // exactement équidistants sont tous deux des réponses correctes.
            val dw = p dot world.position(walked)
            assertTrue(
                abs(dw - bestDot) < 1e-6f,
                "descente arrivée sur un sommet plus lointain que l'optimum"
            )
        }
    }

    @Test
    fun `un sommet de la grille se retrouve lui meme`() {
        val sampler = CoarseSampler(world)
        for (i in 0 until world.vertexCount step 7) {
            assertEquals(i, sampler.nearestVertex(world.position(i)))
        }
    }

    @Test
    fun `l indication de depart ne change pas le resultat`() {
        val sampler = CoarseSampler(world)
        val rng = Rng(43L)
        repeat(600) {
            val p = Sphere.randomPoint(rng)
            val withoutHint = sampler.nearestVertex(p)
            val withHint = sampler.nearestVertex(p, hint = rng.nextInt(world.vertexCount))
            assertEquals(
                p dot world.position(withoutHint),
                p dot world.position(withHint),
                "l'indication de départ a faussé la recherche"
            )
        }
    }

    @Test
    fun `le champ lisse reste dans l enveloppe des valeurs voisines`() {
        val sampler = CoarseSampler(world)
        val rng = Rng(47L)
        repeat(1500) {
            val p = Sphere.randomPoint(rng)
            val t = sampler.smoothTemperatureAt(p)
            assertTrue(
                t >= world.stats.coldestC - 0.1f && t <= world.stats.hottestC + 0.1f,
                "température interpolée hors bornes : $t"
            )
        }
    }

    @Test
    fun `le biome renvoye est celui de la cellule la plus proche`() {
        val sampler = CoarseSampler(world)
        for (i in 0 until world.vertexCount step 11) {
            assertEquals(world.biome(i), sampler.biomeAt(world.position(i)))
        }
    }
}
