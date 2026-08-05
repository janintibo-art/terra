package com.terra.sim

import com.terra.core.Rng
import com.terra.core.Sphere
import com.terra.core.Vec3
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileSelectorTest {

    private val R = 6_371_000f

    private fun cameraAt(altitudeM: Float, lat: Float = 0.3f, lon: Float = 0.8f): Vec3 =
        Sphere.toVec(lat, lon) * ((R + altitudeM) / R)

    @Test
    fun `le selecteur rapide donne le meme resultat que la version naive`() {
        // Le sélecteur optimisé n'a le droit de changer que le coût, pas le
        // résultat. Sans cette équivalence, une régression de rendu pourrait
        // passer pour un problème de performance.
        val selector = TileSelector()
        val fast = ArrayList<TileId>()
        for (altitude in listOf(10_000_000f, 500_000f, 20_000f, 500f, 5f)) {
            val camera = cameraAt(altitude)
            selector.select(camera, fast)
            val naive = TileId.select(camera)
            assertEquals(
                naive.toSet(), fast.toSet(),
                "divergence à $altitude m d'altitude"
            )
        }
    }

    @Test
    fun `la selection reste bornee a toutes les altitudes`() {
        val selector = TileSelector()
        val out = ArrayList<TileId>()
        for (altitude in listOf(
            20_000_000f, 2_000_000f, 200_000f, 20_000f, 2_000f, 200f, 20f, 2f
        )) {
            selector.select(cameraAt(altitude), out)
            assertTrue(out.isNotEmpty(), "aucune tuile à $altitude m")
            assertTrue(out.size < 2500, "explosion à $altitude m : ${out.size} tuiles")
        }
    }

    @Test
    fun `le cone de vision reduit fortement la charge au sol`() {
        // La justification chiffrée du filtre : au ras du sol, l'horizon laisse
        // passer tout le tour de la planète alors que l'écran n'en montre
        // qu'une fraction.
        val camera = cameraAt(50f)
        val selector = TileSelector()

        val withoutCone = ArrayList<TileId>()
        selector.select(camera, withoutCone)

        val forward = (Sphere.toVec(0.32f, 0.82f) - camera).normalized()
        val cone = ViewCone.fromCamera(camera, forward, 0.733f, 16f / 9f)
        val withCone = ArrayList<TileId>()
        selector.select(camera, withCone, cone)

        assertTrue(withCone.size < withoutCone.size, "le cône n'élimine rien")
        assertTrue(
            withCone.size < withoutCone.size * 0.75f,
            "gain trop faible : ${withCone.size} contre ${withoutCone.size}"
        )
        assertTrue(withCone.isNotEmpty(), "le cône a tout éliminé")
    }

    @Test
    fun `le cone ne rejette jamais une tuile reellement visible`() {
        // Un filtre conservateur peut garder trop, jamais trop peu : une tuile
        // manquante ferait un trou dans le terrain.
        val camera = cameraAt(3_000f)
        val forward = (Sphere.toVec(0.31f, 0.81f) - camera).normalized()
        val cone = ViewCone.fromCamera(camera, forward, 0.733f, 16f / 9f)

        val selector = TileSelector()
        val kept = ArrayList<TileId>()
        selector.select(camera, kept, cone)
        val keptSet = kept.toSet()

        val all = ArrayList<TileId>()
        selector.select(camera, all)

        val halfDiagonal = cone.halfAngleRad
        for (tile in all) {
            if (tile in keptSet) continue
            // Toute tuile écartée doit être hors du champ, avec une marge.
            val toTile = (tile.center - camera).normalized()
            val angle = kotlin.math.acos((toTile dot cone.axis).coerceIn(-1f, 1f))
            assertTrue(
                angle > halfDiagonal - 0.05f,
                "tuile $tile écartée alors qu'elle est à $angle rad de l'axe"
            )
        }
    }

    @Test
    fun `le cone accepte la tuile droit devant`() {
        val camera = cameraAt(1_000f)
        val forward = -camera.normalized()          // regard vertical vers le bas
        val cone = ViewCone.fromCamera(camera, forward, 0.733f, 16f / 9f)
        assertTrue(
            cone.mayContain(camera.normalized(), 0.001f),
            "le point directement visé est rejeté"
        )
    }

    @Test
    fun `l instance est reutilisable sans etat residuel`() {
        val selector = TileSelector()
        val a = ArrayList<TileId>()
        val b = ArrayList<TileId>()
        selector.select(cameraAt(100f), a)
        val firstSize = a.size
        repeat(20) { selector.select(cameraAt(5_000_000f), b) }
        selector.select(cameraAt(100f), a)
        assertEquals(firstSize, a.size, "le sélecteur garde une trace des appels précédents")
    }

    @Test
    fun `le compactage des identifiants est fidele`() {
        val rng = Rng(103L)
        repeat(3000) {
            val face = rng.nextInt(6)
            val level = rng.nextInt(TileId.MAX_LEVEL + 1)
            val grid = 1 shl level
            val x = rng.nextInt(grid)
            val y = rng.nextInt(grid)
            val key = TileSelector.pack(face, level, x, y)
            assertEquals(face, TileSelector.unpackFace(key))
            assertEquals(level, TileSelector.unpackLevel(key))
            assertEquals(x, TileSelector.unpackX(key))
            assertEquals(y, TileSelector.unpackY(key))
            assertEquals(TileId(face, level, x, y).packed(), key, "incompatible avec TileId")
        }
    }

    @Test
    fun `le budget plafonne reellement la sortie`() {
        val selector = TileSelector(budget = 64)
        val out = ArrayList<TileId>()
        selector.select(cameraAt(10f), out)
        assertTrue(out.size <= 64, "budget dépassé : ${out.size}")
    }

    @Test
    fun `la projection sans allocation coincide avec la version objet`() {
        val buffer = FloatArray(3)
        for (face in 0 until 6) {
            for (i in 0..8) for (j in 0..8) {
                val s = -1f + 2f * i / 8
                val t = -1f + 2f * j / 8
                val expected = CubeSphere.toSphere(face, s, t)
                CubeSphere.toSphereInto(face, s, t, buffer, 0)
                assertTrue(
                    abs(buffer[0] - expected.x) < 1e-6f &&
                            abs(buffer[1] - expected.y) < 1e-6f &&
                            abs(buffer[2] - expected.z) < 1e-6f,
                    "divergence face $face en ($s, $t)"
                )
            }
        }
    }

    @Test
    fun `l elimination ecarte la majorite des noeuds au sol`() {
        val camera = cameraAt(20f)
        val forward = (Sphere.toVec(0.305f, 0.805f) - camera).normalized()
        val cone = ViewCone.fromCamera(camera, forward, 0.733f, 16f / 9f)
        val selector = TileSelector()
        selector.select(camera, ArrayList(), cone)
        assertTrue(selector.visitedNodes > 0)
        assertTrue(
            selector.culledNodes > selector.visitedNodes / 4,
            "élimination anormalement inefficace : ${selector.culledNodes} sur ${selector.visitedNodes}"
        )
    }
}

/**
 * Banc d'essai automatisé — lot 0.14, resté en dette depuis la Phase 0.
 *
 * Génère une série de mondes, vérifie qu'aucun ne dégénère, et publie un rapport
 * dans `build/reports/`. Sa raison d'être : la planète boule de neige de la v0.3
 * avait franchi la CI parce qu'aucun test ne regardait la **distribution** des
 * résultats sur plusieurs graines. Un contrôle par monde ne voit pas qu'un
 * défaut est systématique.
 */
class WorldBenchmarkTest {

    private val names = listOf(
        "Alpha", "Beta", "Gaia", "Orion", "Vesta", "Thule", "Nyx", "Kaleth",
        "Meridia", "Solara", "Ythros", "Calanth", "Dorne", "Ecliss", "Faron",
        "Halcyon", "Ivrel", "Jorund", "Kestral", "Lumen"
    )

    @Test
    fun `vingt mondes sont generes sans degenerescence`() {
        val report = StringBuilder()
        report.append("# Banc d'essai — ${names.size} mondes, subdivisions = 4\n\n")
        report.append(
            "monde".padEnd(10) + "océan%".padStart(8) + "glaces%".padStart(9) +
                    "cont".padStart(6) + "îles".padStart(6) + "max m".padStart(8) +
                    "T min".padStart(8) + "T max".padStart(8) + "biomes".padStart(8) +
                    "  empreinte\n"
        )

        var iceSum = 0f
        var continentSum = 0
        val peaks = ArrayList<Float>()

        for (name in names) {
            val w = WorldGenerator.fromName(name, PlanetParams(subdivisions = 4)).generate()
            val g = w.geography
            val ice = listOf(Biome.SEA_ICE, Biome.GLACIER, Biome.SNOW)
                .sumOf { w.stats.biomeCounts[it] ?: 0 }
                .toFloat() / w.vertexCount

            iceSum += ice
            continentSum += g.continentCount
            peaks.add(w.stats.highestAltitudeM)

            report.append(
                name.padEnd(10) +
                        "%.1f".format(w.stats.oceanFractionActual * 100).padStart(8) +
                        "%.1f".format(ice * 100).padStart(9) +
                        g.continentCount.toString().padStart(6) +
                        g.islandCount.toString().padStart(6) +
                        w.stats.highestAltitudeM.toInt().toString().padStart(8) +
                        "%.1f".format(w.stats.coldestC).padStart(8) +
                        "%.1f".format(w.stats.hottestC).padStart(8) +
                        w.stats.distinctBiomes.toString().padStart(8) +
                        "  " + w.fingerprintHex() + "\n"
            )

            // Contrôles individuels.
            assertTrue(ice < 0.20f, "$name : ${ice * 100} % de glaces")
            assertTrue(g.continentCount > 0, "$name : aucun continent")
            assertTrue(g.largestLandmassFraction < 0.99f, "$name : super-continent unique")
            assertTrue(w.stats.distinctBiomes >= 8, "$name : ${w.stats.distinctBiomes} biomes")
            assertTrue(w.stats.hottestC < 45f, "$name : ${w.stats.hottestC} °C")
            assertTrue(w.stats.coldestC > -85f, "$name : ${w.stats.coldestC} °C")
        }

        // Contrôles sur la distribution : ce que le test par monde ne voit pas.
        val meanIce = iceSum / names.size
        val meanContinents = continentSum.toFloat() / names.size
        val peakSpread = (peaks.max() - peaks.min()) / peaks.max()

        report.append("\nmoyennes : glaces %.1f %%, continents %.1f, dispersion des sommets %.0f %%\n"
            .format(meanIce * 100, meanContinents, peakSpread * 100))

        val out = File("build/reports/worlds.txt")
        out.parentFile?.mkdirs()
        out.writeText(report.toString())
        println(report)

        assertTrue(
            meanIce in 0.01f..0.14f,
            "couverture glaciaire moyenne hors du plausible : ${meanIce * 100} %"
        )
        assertTrue(
            meanContinents in 1.5f..12f,
            "nombre moyen de continents implausible : $meanContinents"
        )
        assertTrue(
            peakSpread > 0.2f,
            "les mondes ont tous le même relief : dispersion de ${peakSpread * 100} %"
        )
    }

    @Test
    fun `la generation d un monde complet tient dans le budget`() {
        // Budget annoncé : la génération reste acceptable derrière un écran de
        // chargement. Ce test signalera toute dérive quand la tectonique
        // s'ajoutera.
        val started = System.nanoTime()
        WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 5)).generate()
        val ms = (System.nanoTime() - started) / 1_000_000L
        println("Génération niveau 5 : $ms ms")
        assertTrue(ms < 15_000, "génération trop lente : $ms ms")
    }
}
