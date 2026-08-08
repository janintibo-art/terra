package com.terra.sim

import com.terra.core.Seed
import kotlin.math.acos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Points chauds — lot 1.7.
 *
 * La propriété qui définit une chaîne de point chaud n'est pas son apparence
 * mais sa **cause** : le panache est fixe, la plaque défile, donc les
 * édifices s'alignent sur le mouvement de cette plaque et décroissent avec
 * l'âge. Ce sont ces deux relations que ces tests vérifient.
 */
class HotspotTest {

    companion object {
        private val sphere: Icosphere by lazy { Icosphere(4) }

        private fun fieldFor(name: String): Pair<PlateSet, HotspotField> {
            val plates = PlateSet.generate(Seed.fromText(name), sphere, 0.66f)
            return plates to HotspotField.generate(Seed.fromText(name), plates, sphere)
        }
    }

    @Test
    fun `chaque panache produit une chaine complete`() {
        for (name in listOf("Kaleth", "Ormun", "Vessiane")) {
            val (_, field) = fieldFor(name)
            assertTrue(
                field.plumeCount in HotspotField.MIN_PLUMES..HotspotField.MAX_PLUMES,
                "$name : ${field.plumeCount} panaches"
            )
            assertEquals(
                field.plumeCount * HotspotField.CHAIN_LENGTH, field.centers.size,
                "$name : chaînes incomplètes"
            )
            for (c in field.centers) {
                assertEquals(1f, c.length, 1e-3f, "$name : édifice hors de la sphère")
            }
        }
    }

    @Test
    fun `les edifices decroissent avec l age`() {
        // C'est ce qui distingue une chaîne de point chaud d'un simple
        // chapelet d'îles : le volcan actif domine, les anciens s'affaissent.
        val (_, field) = fieldFor("Kaleth")
        for (plume in 0 until field.plumeCount) {
            var previous = Float.MAX_VALUE
            for (i in field.plumeId.indices) {
                if (field.plumeId[i] != plume) continue
                assertTrue(
                    field.heights[i] <= previous,
                    "panache $plume : un édifice plus vieux dépasse le précédent"
                )
                previous = field.heights[i]
            }
        }
    }

    @Test
    fun `la chaine s aligne sur le mouvement de sa plaque`() {
        // Le test central : la direction panache → édifices anciens doit
        // suivre la vitesse de la plaque en ce point. Sans cela, l'alignement
        // serait décoratif au lieu d'être causé.
        val (plates, field) = fieldFor("Ormun")
        var checked = 0
        for (plume in 0 until field.plumeCount) {
            val idx = field.plumeId.indices.filter { field.plumeId[it] == plume }
            val origin = field.centers[idx.first()]
            val far = field.centers[idx.last()]

            // Direction de la chaîne, tangente à la sphère au panache.
            val radial = origin.x * far.x + origin.y * far.y + origin.z * far.z
            val cx = far.x - origin.x * radial
            val cy = far.y - origin.y * radial
            val cz = far.z - origin.z * radial
            val cl = kotlin.math.sqrt(cx * cx + cy * cy + cz * cz)
            if (cl < 1e-6f) continue   // plaque quasi immobile : pas de chaîne

            // Vitesse de la plaque au panache.
            val plate = plates.plates.minByOrNull {
                acos((it.seedDir.x * origin.x + it.seedDir.y * origin.y +
                        it.seedDir.z * origin.z).coerceIn(-1f, 1f))
            }!!
            val v = plate.velocityAt(origin)
            val vl = v.length
            if (vl < 1e-9f) continue

            val cosAngle = (cx / cl * v.x + cy / cl * v.y + cz / cl * v.z) / vl
            assertTrue(
                kotlin.math.abs(cosAngle) > 0.9f,
                "panache $plume : chaîne à ${Math.toDegrees(acos(cosAngle.coerceIn(-1f, 1f)).toDouble())}° du mouvement"
            )
            checked++
        }
        assertTrue(checked >= 3, "trop peu de chaînes vérifiables : $checked")
    }

    @Test
    fun `le relief volcanique reste borne et local`() {
        val (_, field) = fieldFor("Vessiane")
        val rng = kotlin.random.Random(5)
        var maxSeen = 0f
        var touched = 0
        repeat(20_000) {
            val d = randomDir(rng)
            val e = field.elevationAt(d)
            assertTrue(e >= 0f, "élévation volcanique négative : $e")
            // Deux édifices voisins peuvent se sommer ; la borne tient compte
            // de la chaîne entière, mais l'ensemble reste sous le plafond que
            // softLimit garantit de toute façon.
            assertTrue(e < HotspotField.PEAK_HEIGHT_M * 3f, "édifice de $e m")
            if (e > maxSeen) maxSeen = e
            if (e > 50f) touched++
        }
        assertTrue(maxSeen > 200f, "aucun volcan rencontré : $maxSeen m")
        // Local : les volcans ne couvrent qu'une petite part de la planète.
        assertTrue(touched < 20_000 * 0.15, "les volcans couvrent trop de surface")
    }

    @Test
    fun `la generation est deterministe`() {
        val a = fieldFor("Kaleth").second
        val b = fieldFor("Kaleth").second
        assertTrue(a.heights.contentEquals(b.heights))
        assertTrue(a.radii.contentEquals(b.radii))
        for (i in a.centers.indices) {
            assertEquals(a.centers[i].x, b.centers[i].x, 0f)
        }
    }

    private fun randomDir(rng: kotlin.random.Random): com.terra.core.Vec3 {
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
