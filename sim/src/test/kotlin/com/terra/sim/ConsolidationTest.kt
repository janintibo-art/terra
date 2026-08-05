package com.terra.sim

import com.terra.core.Rng
import com.terra.core.Seed
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldNamerTest {

    @Test
    fun `les noms sont prononcables et de longueur raisonnable`() {
        val rng = Rng(1L)
        repeat(500) {
            val name = WorldNamer.randomName(rng)
            assertTrue(name.length in 3..20, "longueur anormale : $name")
            assertTrue(name[0].isUpperCase(), "majuscule manquante : $name")
            assertTrue(name.all { it.isLetter() }, "caractère invalide : $name")
        }
    }

    @Test
    fun `le generateur de noms est deterministe`() {
        assertEquals(WorldNamer.randomName(42L), WorldNamer.randomName(42L))
    }

    @Test
    fun `les noms sont suffisamment varies`() {
        val rng = Rng(7L)
        val names = (0 until 400).map { WorldNamer.randomName(rng) }.toSet()
        assertTrue(names.size > 340, "trop de doublons : ${names.size} noms distincts sur 400")
    }

    @Test
    fun `un nom reconstruit exactement le meme monde`() {
        val a = WorldGenerator.fromName("Kaleth", PlanetParams(subdivisions = 3)).generate()
        val b = WorldGenerator.fromName("  kaleth  ", PlanetParams(subdivisions = 3)).generate()
        assertEquals(a.fingerprint, b.fingerprint, "le nom n'est pas une identité fiable")
    }

    @Test
    fun `des noms proches donnent des mondes differents`() {
        val a = WorldGenerator.fromName("Kaleth", PlanetParams(subdivisions = 3)).generate()
        val b = WorldGenerator.fromName("Kaletha", PlanetParams(subdivisions = 3)).generate()
        assertTrue(a.fingerprint != b.fingerprint)
    }

    @Test
    fun `la normalisation borne la longueur`() {
        assertTrue(WorldNamer.sanitize("x".repeat(100)).length <= 24)
        assertEquals("", WorldNamer.sanitize("   "))
        assertEquals("Deux Mots", WorldNamer.sanitize("  Deux    Mots  "))
    }
}

class WorldTimeTest {

    private val time = WorldTime(minutesPerTick = 1f, minutesPerDay = 1440, daysPerYear = 360)

    @Test
    fun `un jour dure bien mille quatre cent quarante ticks`() {
        assertEquals(1, time.dayOfYear(0L))
        assertEquals(2, time.dayOfYear(1440L))
        assertEquals(1, time.year(0L))
        assertEquals(2, time.year(1440L * 360L))
    }

    @Test
    fun `l heure affichee progresse correctement`() {
        assertEquals(Pair(0, 0), time.clockTime(0L))
        assertEquals(Pair(12, 0), time.clockTime(720L))
        assertEquals(Pair(23, 59), time.clockTime(1439L))
        assertEquals(Pair(0, 0), time.clockTime(1440L))
    }

    @Test
    fun `la rotation propre fait un tour par jour`() {
        assertTrue(abs(time.spinDegrees(0L)) < 0.01f)
        assertTrue(abs(time.spinDegrees(720L) - 180f) < 0.5f)
        assertTrue(abs(time.spinDegrees(1440L)) < 0.01f)
    }

    @Test
    fun `la declinaison solaire suit les saisons`() {
        val quarterYear = 1440L * 90L
        // Équinoxe de printemps : soleil dans le plan équatorial.
        assertTrue(abs(time.sunDeclinationDeg(0L)) < 0.5f)
        // Solstice d'été boréal : déclinaison maximale positive.
        assertTrue(time.sunDeclinationDeg(quarterYear) > 23f)
        // Solstice d'hiver : maximale négative.
        assertTrue(time.sunDeclinationDeg(quarterYear * 3) < -23f)
    }

    @Test
    fun `les saisons se succedent dans l ordre`() {
        val q = 1440L * 90L
        assertEquals(WorldTime.Season.SPRING, time.seasonNorth(q / 2))
        assertEquals(WorldTime.Season.SUMMER, time.seasonNorth(q + q / 2))
        assertEquals(WorldTime.Season.AUTUMN, time.seasonNorth(2 * q + q / 2))
        assertEquals(WorldTime.Season.WINTER, time.seasonNorth(3 * q + q / 2))
    }

    @Test
    fun `la direction du soleil est unitaire`() {
        for (tick in listOf(0L, 5000L, 100_000L, 999_999L)) {
            val s = time.sunDirection(tick)
            val len = kotlin.math.sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2])
            assertTrue(abs(len - 1f) < 1e-4f, "vecteur non unitaire au tick $tick")
        }
    }

    @Test
    fun `sans inclinaison il n y a pas de saisons`() {
        val flat = WorldTime(axialTiltDeg = 0f)
        for (tick in listOf(0L, 100_000L, 500_000L)) {
            assertTrue(abs(flat.sunDeclinationDeg(tick)) < 0.01f)
        }
    }
}

class GeographyTest {

    private fun world(name: String) =
        WorldGenerator.fromName(name, PlanetParams(subdivisions = 4)).generate()

    @Test
    fun `la somme des masses continentales couvre toutes les terres`() {
        val w = world("Gaia")
        val g = w.geography
        val landCells = (0 until w.vertexCount).count { w.altitudeM[it] >= 0f }
        assertTrue(abs(g.landFraction - landCells.toFloat() / w.vertexCount) < 1e-5f)
        assertTrue(g.landmassCount > 0, "aucune terre détectée")
    }

    @Test
    fun `continents et iles se totalisent`() {
        val g = world("Vesta").geography
        assertEquals(g.landmassCount, g.continentCount + g.islandCount)
    }

    @Test
    fun `la plus grande masse ne peut exceder cent pour cent`() {
        for (name in listOf("Alpha", "Beta", "Orion", "Vesta", "Kaleth")) {
            val g = world(name).geography
            assertTrue(
                g.largestLandmassFraction in 0f..1.0001f,
                "$name : fraction aberrante ${g.largestLandmassFraction}"
            )
        }
    }

    @Test
    fun `le monde n est ni un bloc unique ni une poussiere d ilots`() {
        // Le contrôle qualité qui justifie l'existence de ce module : sur une
        // série de graines, aucune ne doit produire un super-continent couvrant
        // la quasi-totalité des terres, ni une fragmentation extrême.
        val names = listOf("Alpha", "Beta", "Gaia", "Orion", "Vesta", "Kaleth", "Nyx", "Thule")
        var monolithic = 0
        var dust = 0
        for (name in names) {
            val g = world(name).geography
            if (g.largestLandmassFraction > 0.97f) monolithic++
            if (g.continentCount == 0) dust++
        }
        assertTrue(monolithic <= 2, "$monolithic mondes monolithiques sur ${names.size}")
        assertTrue(dust == 0, "$dust mondes sans aucun continent")
    }

    @Test
    fun `le littoral est non nul et proportionne`() {
        val w = world("Thule")
        val g = w.geography
        assertTrue(g.coastlineEdges > 0, "aucun littoral détecté")
        assertTrue(g.coastlineKm > 1000f, "littoral irréaliste : ${g.coastlineKm} km")
        // Le littoral terrestre ne peut dépasser la circonférence multipliée par
        // le nombre de cellules côtières : borne large mais qui piège les bugs
        // d'unités.
        assertTrue(g.coastlineKm < 2_000_000f, "littoral aberrant : ${g.coastlineKm} km")
    }

    @Test
    fun `l altitude moyenne des terres est plausible`() {
        val g = world("Orion").geography
        assertTrue(g.meanLandAltitudeM > 0f, "altitude moyenne négative")
        assertTrue(g.meanLandAltitudeM < 4000f, "planète anormalement haute : ${g.meanLandAltitudeM} m")
    }

    @Test
    fun `l analyse est deterministe`() {
        val a = world("Nyx").geography
        val b = world("Nyx").geography
        assertEquals(a.landmassCount, b.landmassCount)
        assertEquals(a.coastlineEdges, b.coastlineEdges)
        assertEquals(a.largestLandmassFraction, b.largestLandmassFraction)
    }
}

class MapLayerTest {

    @Test
    fun `toutes les palettes produisent des couleurs valides`() {
        val w = WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4)).generate()
        val out = FloatArray(3)
        for (layer in MapLayer.values()) {
            for (i in 0 until w.vertexCount) {
                LayerPalette.color(layer, w, i, out)
                for (c in 0..2) {
                    assertTrue(
                        out[c] in 0f..1f && out[c].isFinite(),
                        "couleur invalide sur ${layer.label} au sommet $i : ${out[c]}"
                    )
                }
            }
        }
    }

    @Test
    fun `les calques different visiblement les uns des autres`() {
        val w = WorldGenerator.fromName("Vesta", PlanetParams(subdivisions = 3)).generate()
        val a = FloatArray(3); val b = FloatArray(3)
        var different = 0
        for (i in 0 until w.vertexCount) {
            LayerPalette.color(MapLayer.BIOME, w, i, a)
            LayerPalette.color(MapLayer.TEMPERATURE, w, i, b)
            if (abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2]) > 0.15f) different++
        }
        assertTrue(different > w.vertexCount / 2, "calques trop semblables")
    }

    @Test
    fun `le cycle des calques revient au point de depart`() {
        var layer = MapLayer.BIOME
        repeat(MapLayer.values().size) { layer = MapLayer.next(layer) }
        assertEquals(MapLayer.BIOME, layer)
    }
}

class WorldSaveTest {

    @Test
    fun `aller retour sans perte`() {
        val original = WorldSave.Snapshot(
            worldName = "Kaleth",
            params = PlanetParams(subdivisions = 5, oceanFraction = 0.71f, axialTiltDeg = 17.3f),
            tick = 123_456_789L
        )
        val decoded = WorldSave.decode(WorldSave.encode(original))
        assertNotNull(decoded)
        assertEquals(original.worldName, decoded.worldName)
        assertEquals(original.tick, decoded.tick)
        assertEquals(original.params, decoded.params)
    }

    @Test
    fun `la sauvegarde reste tres compacte`() {
        val bytes = WorldSave.encode(
            WorldSave.Snapshot("Gaia", PlanetParams(), 0L)
        )
        assertTrue(bytes.size < 256, "sauvegarde trop lourde : ${bytes.size} octets")
    }

    @Test
    fun `un fichier corrompu ne fait pas planter`() {
        assertNull(WorldSave.decode(ByteArray(0)))
        assertNull(WorldSave.decode(ByteArray(4)))
        assertNull(WorldSave.decode(ByteArray(64) { 0x7F }))
        assertNull(WorldSave.decode("n'importe quoi".toByteArray()))
    }

    @Test
    fun `une sauvegarde tronquee est rejetee proprement`() {
        val full = WorldSave.encode(WorldSave.Snapshot("Orion", PlanetParams(), 5L))
        assertNull(WorldSave.decode(full.copyOf(full.size / 2)))
    }

    @Test
    fun `une sauvegarde restitue le meme monde`() {
        val snapshot = WorldSave.Snapshot("Thule", PlanetParams(subdivisions = 3), 0L)
        val restored = WorldSave.decode(WorldSave.encode(snapshot))!!
        val a = WorldGenerator.fromName(snapshot.worldName, snapshot.params).generate()
        val b = WorldGenerator.fromName(restored.worldName, restored.params).generate()
        assertEquals(a.fingerprint, b.fingerprint, "le monde rechargé diffère de l'original")
    }

    @Test
    fun `les noms accentues survivent a l aller retour`() {
        val snapshot = WorldSave.Snapshot("Élyséa", PlanetParams(), 42L)
        assertEquals("Élyséa", WorldSave.decode(WorldSave.encode(snapshot))!!.worldName)
    }
}

/**
 * Test d'empreinte — protection contre les dérives involontaires.
 *
 * Les autres tests vérifient des propriétés (« il y a bien 66 % d'océans »).
 * Celui-ci vérifie l'identité : la graine « Gaia » doit produire *exactement* le
 * monde qu'elle produisait au lot précédent.
 *
 * Au premier passage, aucune référence n'existe : le test enregistre les
 * empreintes dans `build/reports/fingerprints.txt` et passe. Ces valeurs sont
 * ensuite figées dans `src/test/resources/fingerprints.txt`, et toute
 * modification involontaire de la génération fera échouer le test.
 *
 * Quand la génération change **volontairement** (ajout de la tectonique par
 * exemple), on met à jour le fichier de référence et on incrémente
 * [WorldSave.GENERATION_VERSION].
 */
class FingerprintTest {

    private val referenceWorlds = listOf("Alpha", "Beta", "Gaia", "Orion", "Vesta")

    @Test
    fun `les empreintes correspondent a la reference`() {
        val actual = referenceWorlds.map { name ->
            val w = WorldGenerator.fromName(name, PlanetParams(subdivisions = 4)).generate()
            "$name=${w.fingerprintHex()}"
        }

        val report = File("build/reports/fingerprints.txt")
        report.parentFile?.mkdirs()
        report.writeText(
            "# Empreintes des mondes de référence, subdivisions = 4\n" +
            "# Générées par FingerprintTest — à copier dans src/test/resources/\n" +
            actual.joinToString("\n") + "\n"
        )

        val reference = javaClass.getResourceAsStream("/fingerprints.txt")
        if (reference == null) {
            println("Aucune référence d'empreinte : valeurs enregistrées dans ${report.path}")
            actual.forEach { println("  $it") }
            return
        }

        val expected = reference.bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        assertEquals(
            expected.sorted(), actual.sorted(),
            "La génération a changé. Si c'est volontaire, mettre à jour " +
            "src/test/resources/fingerprints.txt et incrémenter GENERATION_VERSION."
        )
    }

    @Test
    fun `l empreinte est stable d une execution a l autre`() {
        val a = WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4)).generate()
        val b = WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4)).generate()
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals(16, a.fingerprintHex().length)
    }

    @Test
    fun `l empreinte detecte la moindre difference`() {
        val a = WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4)).generate()
        val b = WorldGenerator.fromName(
            "Gaia", PlanetParams(subdivisions = 4, oceanFraction = 0.67f)
        ).generate()
        assertTrue(a.fingerprint != b.fingerprint, "un changement de paramètre passe inaperçu")
    }
}

/**
 * Tests climatiques — verrouillent la correction de la « planète boule de neige ».
 *
 * La v0.3 employait un profil thermique en sin², qui donnait −3 °C à 45° de
 * latitude contre +12 °C dans la réalité. Résultat : toute mer au-delà de 44°
 * gelait, toute terre au-delà de 50° devenait glacier, et près de 30 % de la
 * surface finissait sous la glace contre 7 % sur Terre.
 *
 * Ces tests comparent le modèle aux moyennes annuelles observées et fixent des
 * bornes de plausibilité. Ils échoueront si un réglage futur refait dériver le
 * climat.
 */
class ClimateRealismTest {

    private fun world(name: String, sub: Int = 4) =
        WorldGenerator.fromName(name, PlanetParams(subdivisions = sub)).generate()

    private val referenceNames = listOf("Alpha", "Gaia", "Orion", "Vesta", "Thule", "Nyx")

    /** Température moyenne des cellules dont la latitude est proche de la cible. */
    private fun meanTempAtLatitude(w: PlanetData, targetDeg: Float, toleranceDeg: Float): Float {
        val target = kotlin.math.sin(targetDeg * com.terra.core.DEG_TO_RAD)
        val tol = kotlin.math.sin((targetDeg + toleranceDeg) * com.terra.core.DEG_TO_RAD) - target
        var sum = 0.0
        var count = 0
        for (i in 0 until w.vertexCount) {
            // Au niveau de la mer uniquement : l'altitude fausserait la mesure.
            if (w.altitudeM[i] > 400f) continue
            if (abs(abs(w.position(i).y) - target) <= abs(tol)) {
                sum += w.temperatureC[i]
                count++
            }
        }
        return if (count > 0) (sum / count).toFloat() else Float.NaN
    }

    @Test
    fun `le profil thermique suit les moyennes annuelles terrestres`() {
        // Cibles observées sur Terre, avec une marge généreuse : on vérifie
        // l'ordre de grandeur, pas une reproduction au degré près.
        val expectations = listOf(
            Triple(0f, 20f, 32f),      // équateur    : environ 26 °C
            Triple(30f, 13f, 26f),     // tropiques   : environ 20 °C
            Triple(45f, 4f, 18f),      // tempéré     : environ 12 °C  <-- le cas fautif
            Triple(60f, -8f, 8f),      // subpolaire  : environ  0 °C
            Triple(80f, -28f, -6f)     // polaire     : environ −18 °C
        )
        for (name in referenceNames.take(3)) {
            val w = world(name)
            for ((lat, low, high) in expectations) {
                val t = meanTempAtLatitude(w, lat, 6f)
                if (t.isNaN()) continue
                assertTrue(
                    t in low..high,
                    "$name à $lat° : $t °C, attendu entre $low et $high"
                )
            }
        }
    }

    @Test
    fun `la planete n est pas une boule de neige`() {
        for (name in referenceNames) {
            val w = world(name)
            val ice = listOf(Biome.SEA_ICE, Biome.GLACIER, Biome.SNOW)
                .sumOf { w.stats.biomeCounts[it] ?: 0 }
                .toFloat() / w.vertexCount
            assertTrue(
                ice < 0.16f,
                "$name : ${(ice * 100).toInt()} % de la surface sous la glace (Terre : ~7 %)"
            )
        }
    }

    @Test
    fun `la banquise reste cantonnee aux hautes latitudes`() {
        for (name in referenceNames) {
            val w = world(name)
            for (i in 0 until w.vertexCount) {
                if (w.biome(i) != Biome.SEA_ICE) continue
                val lat = abs(w.position(i).y)
                assertTrue(
                    lat > 0.72f,
                    "$name : banquise à sin(lat) = $lat, soit une latitude trop basse"
                )
            }
        }
    }

    @Test
    fun `les tropiques ne gelent jamais`() {
        for (name in referenceNames) {
            val w = world(name)
            for (i in 0 until w.vertexCount) {
                if (abs(w.position(i).y) > 0.34f) continue   // au-delà de 20°
                if (w.altitudeM[i] > 2500f) continue          // hors haute montagne
                assertTrue(
                    w.temperatureC[i] > 8f,
                    "$name : ${w.temperatureC[i]} °C sous les tropiques au sommet $i"
                )
            }
        }
    }

    @Test
    fun `la continentalite asseche et refroidit l interieur des terres`() {
        // On compare, à latitude tempérée comparable, les cellules côtières aux
        // cellules intérieures. Sans continentalité, les deux seraient
        // identiques et ce test échouerait.
        val w = world("Gaia", sub = 5)
        val adjacency = w.sphere.buildAdjacency()

        var coastalPrecip = 0.0; var coastalCount = 0
        var interiorPrecip = 0.0; var interiorCount = 0

        for (i in 0 until w.vertexCount) {
            if (!w.isLand(i)) continue
            val lat = abs(w.position(i).y)
            if (lat < 0.35f || lat > 0.85f) continue
            val touchesSea = adjacency[i].any { !w.isLand(it) }
            if (touchesSea) {
                coastalPrecip += w.precipMm[i]; coastalCount++
            }
        }
        // Cellules très éloignées de toute mer : au moins 5 pas de distance.
        val stepsToSea = IntArray(w.vertexCount) { Int.MAX_VALUE }
        val queue = IntArray(w.vertexCount)
        var head = 0; var tail = 0
        for (i in 0 until w.vertexCount) if (!w.isLand(i)) { stepsToSea[i] = 0; queue[tail++] = i }
        while (head < tail) {
            val v = queue[head++]
            for (x in adjacency[v]) if (stepsToSea[x] > stepsToSea[v] + 1) {
                stepsToSea[x] = stepsToSea[v] + 1; queue[tail++] = x
            }
        }
        for (i in 0 until w.vertexCount) {
            if (!w.isLand(i)) continue
            val lat = abs(w.position(i).y)
            if (lat < 0.35f || lat > 0.85f) continue
            if (stepsToSea[i] >= 5) { interiorPrecip += w.precipMm[i]; interiorCount++ }
        }

        if (interiorCount > 20 && coastalCount > 20) {
            val coastal = coastalPrecip / coastalCount
            val interior = interiorPrecip / interiorCount
            assertTrue(
                interior < coastal,
                "l'intérieur ($interior mm) n'est pas plus sec que la côte ($coastal mm)"
            )
        }
    }

    @Test
    fun `le relief varie d un monde a l autre`() {
        // La v0.3 produisait exactement +7000 m sur chaque planète, effet
        // mécanique du calibrage par percentile.
        val peaks = referenceNames.map { world(it).stats.highestAltitudeM }
        assertTrue(
            peaks.toSet().size >= referenceNames.size - 1,
            "les sommets sont identiques d'un monde à l'autre : $peaks"
        )
        val spread = (peaks.max() - peaks.min()) / peaks.max()
        assertTrue(spread > 0.15f, "amplitude de relief trop uniforme : $spread")
    }

    @Test
    fun `les lacs ne sont plus comptes comme des mers interieures`() {
        val g = world("Triovild", sub = 5).geography
        assertTrue(g.inlandSeaCount < 8, "trop de mers intérieures : ${g.inlandSeaCount}")
        assertTrue(g.inlandSeaCount + g.lakeCount > 0, "aucun plan d'eau isolé détecté")
    }
}
