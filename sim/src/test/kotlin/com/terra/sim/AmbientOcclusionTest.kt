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

        // Échantillons (concavité, luminance) par biome. Aucun seuil de
        // sélection : la v0.29.1 exigeait 42 m de concavité là où un pas de
        // grille de 305 m en produit 1 à 5, et vidait les populations. La
        // séparation se fera par QUARTILES, peuplés par construction.
        val samples = HashMap<Int, ArrayList<Pair<Float, Float>>>()

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
                val biome = sampler.biomeAt(d, sampler.nearestVertex(d, 0)).ordinal
                val lum = mesh.vertexData[v - TileMesh.FLOATS_PER_VERTEX + TileMesh.OFFSET_COLOR] * 0.3f +
                    mesh.vertexData[v - TileMesh.FLOATS_PER_VERTEX + TileMesh.OFFSET_COLOR + 1] * 0.6f +
                    mesh.vertexData[v - TileMesh.FLOATS_PER_VERTEX + TileMesh.OFFSET_COLOR + 2] * 0.1f
                samples.getOrPut(biome) { ArrayList() }.add(Pair(concavity, lum))
            }
        }

        // Comparaison stratifiée par biome, quart le plus CONCAVE contre
        // quart le plus CONVEXE : les deux populations valent 25 % de
        // l'échantillon quelle que soit l'amplitude du relief — aucune
        // constante à calibrer, donc aucune à se tromper. Cumul pondéré
        // ensuite, jamais une moyenne brute (leçon v0.15.3).
        var weighted = 0.0
        var weight = 0
        for ((_, list) in samples) {
            if (list.size < 80) continue
            val sorted = list.sortedBy { it.first }
            val q = sorted.size / 4
            var convexSum = 0.0
            var concaveSum = 0.0
            for (i in 0 until q) convexSum += sorted[i].second
            for (i in sorted.size - q until sorted.size) concaveSum += sorted[i].second
            weighted += (convexSum / q - concaveSum / q) * q
            weight += q
        }
        assertTrue(weight > 0, "aucun biome n'atteint 80 échantillons")
        val gap = weighted / weight
        assertTrue(
            gap > 0.0,
            "les creux ne sont pas plus sombres que les bosses (écart $gap)"
        )
    }

    @Test
    fun `deux tuiles voisines s ombrent pareil sur leur bord commun`() {
        // LE test qui manquait : la v0.29.3 normalisait par la rugosité
        // moyenne de chaque tuile, si bien que deux voisines assombrissaient
        // différemment le MÊME relief à leur frontière — coutures diagonales
        // visibles sur appareil, qu'aucun test ne voyait.
        //
        // On compare les sommets du bord partagé, appariés par leur position
        // dans l'espace : à moins d'un centimètre, ce sont les mêmes points
        // du terrain, donc leur couleur doit coïncider.
        val sampler = CoarseSampler(world)
        val r = world.params.radiusM.toDouble()
        val base = landTiles.firstOrNull() ?: return
        val left = TileMesh(base, world.terrain, sampler, r)
        val right = TileMesh(
            TileId(base.face, base.level, base.x + 1, base.y),
            world.terrain, sampler, r
        )

        fun points(m: TileMesh): List<Triple<Double, Double, DoubleArray>> {
            val out = ArrayList<Triple<Double, Double, DoubleArray>>()
            val terrainFloats = TileMesh.MESH_N * TileMesh.MESH_N * 6 *
                TileMesh.FLOATS_PER_VERTEX
            var v = 0
            while (v < terrainFloats) {
                out.add(
                    Triple(
                        m.vertexData[v] + m.centerXM,
                        m.vertexData[v + 1] + m.centerYM,
                        doubleArrayOf(
                            m.vertexData[v + 2] + m.centerZM,
                            m.vertexData[v + TileMesh.OFFSET_COLOR].toDouble(),
                            m.vertexData[v + TileMesh.OFFSET_COLOR + 1].toDouble(),
                            m.vertexData[v + TileMesh.OFFSET_COLOR + 2].toDouble()
                        )
                    )
                )
                v += TileMesh.FLOATS_PER_VERTEX
            }
            return out
        }

        val a = points(left)
        val b = points(right)
        var compared = 0
        var maxGap = 0.0
        for (pa in a) {
            for (pb in b) {
                if (kotlin.math.abs(pa.first - pb.first) > 0.01) continue
                if (kotlin.math.abs(pa.second - pb.second) > 0.01) continue
                if (kotlin.math.abs(pa.third[0] - pb.third[0]) > 0.01) continue
                compared++
                for (k in 1..3) {
                    val gap = kotlin.math.abs(pa.third[k] - pb.third[k])
                    if (gap > maxGap) maxGap = gap
                }
                break
            }
        }
        assertTrue(compared >= 5, "trop peu de sommets partagés trouvés ($compared)")
        // Tolérance : le bruit de teinte du sol est identique aux deux bords
        // (même position, même hachage), donc l'écart attendu est nul ; on
        // laisse 1 % pour l'arrondi du flottant.
        assertTrue(
            maxGap < 0.01,
            "couture entre tuiles voisines : écart de couleur de $maxGap sur le bord commun"
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
