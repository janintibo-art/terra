package com.terra.sim

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Occlusion ambiante du terrain — lot 2.13.
 *
 * La première mouture mesurait la dispersion de luminance sur des tuiles
 * aux coordonnées arbitraires : sur un globe couvert à 66 % d'eau, elles
 * sont tombées en mer, où l'occlusion est neutre par construction et la
 * couleur plate. Elle ne mesurait donc rien.
 *
 * Cette version teste la propriété ELLE-MÊME : à biome égal, un sommet
 * concave doit être plus sombre qu'un sommet convexe. La concavité est
 * recalculée indépendamment depuis le terrain continu — le test ne
 * reproduit pas l'implémentation, il la contrôle. Populations stratifiées
 * par biome et cumulées sur plusieurs tuiles, comme l'exige la leçon des
 * courants (v0.15.3).
 */
class AmbientOcclusionTest {

    companion object {
        private val world by lazy {
            WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4)).generate()
        }

        /** Tuiles de niveau 11 dont le centre est en terrain émergé. */
        private val landTiles by lazy {
            val n = TileMesh.MESH_N
            val found = ArrayList<TileId>()
            val g = 1 shl 11
            var step = g / 16
            outer@ for (face in 0 until 6) {
                var ty = step
                while (ty < g - step) {
                    var tx = step
                    while (tx < g - step) {
                        val d = CubeSphere.gridDirection(
                            face, 11, tx * n + n / 2, ty * n + n / 2, n
                        ).toVec3()
                        if (world.terrain.renderedAltitudeAt(d) > 150f) {
                            found.add(TileId(face, 11, tx, ty))
                            if (found.size >= 6) break@outer
                        }
                        tx += step
                    }
                    ty += step
                }
            }
            found
        }
    }

    @Test
    fun `a biome egal, les creux sont plus sombres que les bosses`() {
        assertTrue(landTiles.size >= 3, "trop peu de tuiles terrestres trouvées")
        val sampler = CoarseSampler(world)
        val r = world.params.radiusM.toDouble()
        val n = TileMesh.MESH_N

        // Somme des luminances par (biome, creux/bosse).
        val darkSum = HashMap<Int, Double>()
        val darkN = HashMap<Int, Int>()
        val brightSum = HashMap<Int, Double>()
        val brightN = HashMap<Int, Int>()

        for (tile in landTiles) {
            val mesh = TileMesh(tile, world.terrain, sampler, r)
            // Pas de grille de la tuile, en radians : l'échelle à laquelle
            // le code mesure lui-même la concavité.
            val stepRad = ((tile.s1 - tile.s0) / n).toDouble()
            var v = 0
            val terrainFloats = n * n * 6 * TileMesh.FLOATS_PER_VERTEX
            while (v < terrainFloats) {
                // Direction du sommet, reconstruite depuis sa position.
                val x = mesh.vertexData[v] + mesh.centerXM
                val y = mesh.vertexData[v + 1] + mesh.centerYM
                val z = mesh.vertexData[v + 2] + mesh.centerZM
                val len = sqrt(x * x + y * y + z * z)
                v += TileMesh.FLOATS_PER_VERTEX
                if (len < 1.0) continue
                val d = com.terra.core.Vec3(
                    (x / len).toFloat(), (y / len).toFloat(), (z / len).toFloat()
                )
                val a = world.terrain.renderedAltitudeAt(d)
                if (a <= 0f) continue          // l'eau est neutre par construction

                // Concavité indépendante : quatre voisins à un pas de grille.
                val e = com.terra.core.Vec3(
                    (-d.z), 0f, d.x
                ).let { val l = sqrt(it.x * it.x + it.z * it.z); if (l < 1e-4f) null
                        else com.terra.core.Vec3(it.x / l, 0f, it.z / l) } ?: continue
                val nr = com.terra.core.Vec3(
                    d.y * e.z - d.z * e.y, d.z * e.x - d.x * e.z, d.x * e.y - d.y * e.x
                )
                val s = stepRad.toFloat()
                fun altAt(ox: Float, oy: Float, oz: Float) =
                    world.terrain.renderedAltitudeAt(
                        com.terra.core.Vec3(d.x + ox, d.y + oy, d.z + oz)
                    )
                val neighbours = 0.25f * (
                    altAt(e.x * s, e.y * s, e.z * s) +
                    altAt(-e.x * s, -e.y * s, -e.z * s) +
                    altAt(nr.x * s, nr.y * s, nr.z * s) +
                    altAt(-nr.x * s, -nr.y * s, -nr.z * s)
                )
                val concavity = neighbours - a
                // Zone morte : seuls les creux et bosses NETS comptent —
                // un demi-pas d'altitude, l'échelle où l'effet est fort.
                val scale = (stepRad * world.params.radiusM * 0.55).toFloat()
                if (kotlin.math.abs(concavity) < scale * 0.25f) continue

                val biome = sampler.biomeAt(d, sampler.nearestVertex(d, 0)).ordinal
                val lum = mesh.vertexData[v - TileMesh.FLOATS_PER_VERTEX + TileMesh.OFFSET_COLOR] * 0.3f +
                    mesh.vertexData[v - TileMesh.FLOATS_PER_VERTEX + TileMesh.OFFSET_COLOR + 1] * 0.6f +
                    mesh.vertexData[v - TileMesh.FLOATS_PER_VERTEX + TileMesh.OFFSET_COLOR + 2] * 0.1f
                if (concavity > 0f) {
                    darkSum[biome] = (darkSum[biome] ?: 0.0) + lum
                    darkN[biome] = (darkN[biome] ?: 0) + 1
                } else {
                    brightSum[biome] = (brightSum[biome] ?: 0.0) + lum
                    brightN[biome] = (brightN[biome] ?: 0) + 1
                }
            }
        }

        // Comparaison stratifiée : uniquement les biomes assez peuplés des
        // deux côtés, puis cumul pondéré — jamais une moyenne brute.
        var weighted = 0.0
        var weight = 0
        for (b in darkN.keys) {
            val dn = darkN[b] ?: 0
            val bn = brightN[b] ?: 0
            if (dn < 20 || bn < 20) continue
            val k = minOf(dn, bn)
            weighted += ((brightSum[b]!! / bn) - (darkSum[b]!! / dn)) * k
            weight += k
        }
        assertTrue(weight > 0, "aucun biome assez peuplé des deux côtés")
        val gap = weighted / weight
        assertTrue(
            gap > 0.0,
            "les creux ne sont pas plus sombres que les bosses (écart $gap)"
        )
    }

    @Test
    fun `l occlusion ne sort jamais des bornes de couleur`() {
        val sampler = CoarseSampler(world)
        val r = world.params.radiusM.toDouble()
        val tiles = landTiles.take(2) + listOf(TileId(2, 9, 100, 100))
        for (tile in tiles) {
            val mesh = TileMesh(tile, world.terrain, sampler, r)
            var v = TileMesh.OFFSET_COLOR
            while (v < mesh.vertexData.size) {
                for (c in 0..2) {
                    val x = mesh.vertexData[v + c]
                    assertTrue(x in 0f..1f, "couleur hors bornes après occlusion : $x")
                }
                v += TileMesh.FLOATS_PER_VERTEX
            }
        }
    }
}
