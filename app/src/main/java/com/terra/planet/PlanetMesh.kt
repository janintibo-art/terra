package com.terra.planet

import com.terra.sim.PlanetData
import kotlin.math.sqrt

/**
 * Convertit les données de simulation en tampon de sommets prêt pour le GPU.
 *
 * Ce code vit côté rendu, pas côté simulation : le module [:sim] ignore
 * volontairement tout ce qui touche à OpenGL. C'est ce qui lui permet d'être
 * testé sur une simple JVM en intégration continue.
 *
 * Format entrelacé, 10 flottants par sommet :
 *   position (3) · couleur (3) · normale (3) · matériau (1)
 *
 * Les sommets sont dupliqués par face plutôt que partagés : c'est indispensable
 * pour obtenir des facettes nettes (low-poly). Une normale unique par sommet
 * partagé produirait un lissage qui effacerait complètement le style visuel.
 */
class PlanetMesh(data: PlanetData) {

    val vertexData: FloatArray
    val vertexCount: Int

    companion object {
        const val FLOATS_PER_VERTEX = 10
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4
        const val OFFSET_POSITION = 0
        const val OFFSET_COLOR = 3
        const val OFFSET_NORMAL = 6
        const val OFFSET_MATERIAL = 9

        const val MATERIAL_LAND = 0f
        const val MATERIAL_WATER = 1f
    }

    init {
        val sphere = data.sphere
        val faces = sphere.faces
        vertexCount = sphere.faceCount * 3
        vertexData = FloatArray(vertexCount * FLOATS_PER_VERTEX)

        // Positions déplacées, calculées une seule fois par sommet partagé.
        val px = FloatArray(sphere.vertexCount)
        val py = FloatArray(sphere.vertexCount)
        val pz = FloatArray(sphere.vertexCount)
        for (i in 0 until sphere.vertexCount) {
            val v = sphere.vertices[i]
            val r = data.renderRadius(i)
            px[i] = v.x * r; py[i] = v.y * r; pz[i] = v.z * r
        }

        var o = 0
        var f = 0
        while (f < faces.size) {
            val i0 = faces[f]; val i1 = faces[f + 1]; val i2 = faces[f + 2]

            // Normale de face par produit vectoriel.
            val ux = px[i1] - px[i0]; val uy = py[i1] - py[i0]; val uz = pz[i1] - pz[i0]
            val wx = px[i2] - px[i0]; val wy = py[i2] - py[i0]; val wz = pz[i2] - pz[i0]
            var nx = uy * wz - uz * wy
            var ny = uz * wx - ux * wz
            var nz = ux * wy - uy * wx
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            if (nl > 1e-9f) { nx /= nl; ny /= nl; nz /= nl }

            // Une face est aquatique seulement si ses trois sommets le sont :
            // le trait de côte reste ainsi net au lieu de baver sur la mer.
            val waterCount = (if (data.biome(i0).isWater) 1 else 0) +
                             (if (data.biome(i1).isWater) 1 else 0) +
                             (if (data.biome(i2).isWater) 1 else 0)
            val material = if (waterCount == 3) MATERIAL_WATER else MATERIAL_LAND

            for (k in 0..2) {
                val vi = faces[f + k]
                val biome = data.biome(vi)
                vertexData[o++] = px[vi]; vertexData[o++] = py[vi]; vertexData[o++] = pz[vi]
                vertexData[o++] = biome.r; vertexData[o++] = biome.g; vertexData[o++] = biome.b
                vertexData[o++] = nx; vertexData[o++] = ny; vertexData[o++] = nz
                vertexData[o++] = material
            }
            f += 3
        }
    }

    val sizeBytes: Int get() = vertexData.size * 4
}
