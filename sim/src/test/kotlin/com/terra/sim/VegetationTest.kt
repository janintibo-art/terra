package com.terra.sim

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Végétation minimale — lot 3-avancé.
 *
 * La propriété centrale est la CANONICITÉ du treillis : une plante
 * appartient à une case fixe du monde, pas à une tuile. Le test
 * mère/filles le vérifie au centimètre — c'est lui qui garantit qu'aucun
 * arbre ne saute ni n'apparaît au changement de niveau de détail.
 */
class VegetationTest {

    companion object {
        private val world by lazy {
            WorldGenerator.fromName("Gaia", PlanetParams(subdivisions = 4)).generate()
        }
        private val sampler by lazy { CoarseSampler(world) }

        /** Première tuile de niveau 15 dont le centre est en forêt plane. */
        private val forestTile by lazy {
            val n = TileMesh.MESH_N
            for (face in 0 until 6) {
                for (gy in 0 until 32) {
                    for (gx in 0 until 32) {
                        val g = 1 shl 15
                        val tx = (gx * (g / 32)) + g / 64
                        val ty = (gy * (g / 32)) + g / 64
                        val d = CubeSphere.gridDirection(face, 15, tx * n + n / 2,
                            ty * n + n / 2, n).toVec3()
                        val a = world.terrain.renderedAltitudeAt(d)
                        if (a <= 50f) continue
                        val b = sampler.biomeAt(d, sampler.nearestVertex(d, 0))
                        if (b == Biome.RAINFOREST || b == Biome.TEMPERATE_FOREST ||
                            b == Biome.BOREAL_FOREST
                        ) {
                            return@lazy TileId(face, 15, tx, ty)
                        }
                    }
                }
            }
            error("aucune tuile forestière de niveau 15 sur Gaia — improbable")
        }
    }

    /** Positions-monde des pieds de plantes d'une tuile (x, y, z par plante). */
    private fun plantBases(mesh: TileMesh): List<DoubleArray> {
        val start = (TileMesh.MESH_N * TileMesh.MESH_N * 6 + 4 * TileMesh.MESH_N * 6) *
            TileMesh.FLOATS_PER_VERTEX
        val out = ArrayList<DoubleArray>()
        var p = start
        while (p < mesh.vertexData.size) {
            val x = mesh.vertexData[p]; val y = mesh.vertexData[p + 1]
            val z = mesh.vertexData[p + 2]
            if (x != 0f || y != 0f || z != 0f) {
                out.add(doubleArrayOf(
                    x + mesh.centerXM, y + mesh.centerYM, z + mesh.centerZM
                ))
            }
            p += TileMesh.VERTS_PER_PLANT * TileMesh.FLOATS_PER_VERTEX
        }
        return out
    }

    @Test
    fun `une tuile forestiere est peuplee et les niveaux grossiers sont nus`() {
        val r = world.params.radiusM.toDouble()
        val forest = TileMesh(forestTile, world.terrain, sampler, r)
        val plants = plantBases(forest)
        // 49 cases, densité ≥ 0,8 en forêt, pertes de pente et de rivage :
        // huit est une borne très basse, calculée pour ne rougir que sur une
        // vraie panne du tirage.
        assertTrue(plants.size >= 8, "forêt trop clairsemée : ${plants.size} plantes")
        // Chaque pied touche le terrain rendu : rayon ≥ rayon planétaire.
        for (b in plants) {
            val norm = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
            assertTrue(norm > r - 1.0, "pied sous la surface : $norm")
        }
        // Niveau grossier : aucun emplacement peuplé.
        val coarse = TileMesh(TileId(0, 12, 100, 200), world.terrain, sampler, r)
        assertEquals(0, plantBases(coarse).size, "des plantes au niveau 12")
    }

    @Test
    fun `les plantes de la mere se retrouvent au centimetre dans ses filles`() {
        val r = world.params.radiusM.toDouble()
        val parent = TileMesh(forestTile, world.terrain, sampler, r)
        val parentPlants = plantBases(parent)
        assertTrue(parentPlants.isNotEmpty())

        val childPlants = ArrayList<DoubleArray>()
        for (dy in 0..1) {
            for (dx in 0..1) {
                val child = TileId(
                    forestTile.face, 16,
                    forestTile.gx * 2 + dx, forestTile.gy * 2 + dy
                )
                childPlants.addAll(plantBases(TileMesh(child, world.terrain, sampler, r)))
            }
        }
        assertEquals(
            parentPlants.size, childPlants.size,
            "la descente crée ou perd des plantes"
        )
        for (p in parentPlants) {
            val found = childPlants.any { c ->
                abs(c[0] - p[0]) < 0.01 && abs(c[1] - p[1]) < 0.01 &&
                    abs(c[2] - p[2]) < 0.01
            }
            assertTrue(found, "une plante de la mère manque dans les filles")
        }
    }

    @Test
    fun `le tirage est deterministe et la densite bien cartographiee`() {
        val r = world.params.radiusM.toDouble()
        val a = TileMesh(forestTile, world.terrain, sampler, r)
        val b = TileMesh(forestTile, world.terrain, sampler, r)
        assertTrue(a.vertexData.contentEquals(b.vertexData))

        assertEquals(0f, TileMesh.plantDensity(Biome.GLACIER))
        assertEquals(0f, TileMesh.plantDensity(Biome.DESERT))
        assertEquals(0f, TileMesh.plantDensity(Biome.OCEAN))
        assertTrue(TileMesh.plantDensity(Biome.RAINFOREST) >
            TileMesh.plantDensity(Biome.SAVANNA))
        assertTrue(TileMesh.plantDensity(Biome.SAVANNA) >
            TileMesh.plantDensity(Biome.TUNDRA))
    }
}
