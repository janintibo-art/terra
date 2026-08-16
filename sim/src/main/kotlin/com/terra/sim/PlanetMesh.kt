package com.terra.sim

// Déplacé de :app vers :sim au lot 10.1 : ce maillage n'a jamais eu la
// moindre dépendance Android, et le module :desktop en a besoin. Le
// dupliquer aurait créé deux vérités qui auraient divergé.

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
class PlanetMesh(
    data: PlanetData,
    val layer: MapLayer = MapLayer.BIOME,
    /**
     * Raffinement haute définition (v0.19.0). Fourni : la géométrie vient
     * du terrain continu, un niveau plus fin que la grille — côtes tracées
     * là où le terrain croise le niveau de la mer. Absent : chemin
     * historique sur la grille, conservé pour la génération en cours et
     * comme filet de repli.
     */
    refinement: GlobeRefinement? = null
) {

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
        val sphere = refinement?.sphere ?: data.sphere
        val faces = sphere.faces
        vertexCount = sphere.faceCount * 3
        vertexData = FloatArray(vertexCount * FLOATS_PER_VERTEX)

        // Positions déplacées, calculées une seule fois par sommet partagé.
        val px = FloatArray(sphere.vertexCount)
        val py = FloatArray(sphere.vertexCount)
        val pz = FloatArray(sphere.vertexCount)
        for (i in 0 until sphere.vertexCount) {
            val v = sphere.vertices[i]
            val r = refinement?.renderRadius?.get(i) ?: data.renderRadius(i)
            px[i] = v.x * r; py[i] = v.y * r; pz[i] = v.z * r
        }

        // Couleurs pré-calculées par sommet selon le calque demandé.
        val colors = FloatArray(sphere.vertexCount * 3)
        val tmp = FloatArray(3)
        for (i in 0 until sphere.vertexCount) {
            // En mode raffiné, la couleur vient de la CELLULE la plus
            // proche : les données n'existent qu'à la résolution de la
            // grille, les interpoler peindrait une précision mensongère.
            val cell = refinement?.nearestCell?.get(i) ?: i
            LayerPalette.color(layer, data, cell, tmp)
            // Cas de couture du calque biomes : un sommet fin SOUS le
            // niveau de la mer dont la cellule la plus proche est
            // terrestre recevrait une couleur de terre sur une facette
            // marine — pixels bruns dans l'eau le long des côtes. On lui
            // donne la mer côtière, le biome littoral par définition.
            if (refinement != null && layer == MapLayer.BIOME &&
                refinement.water[i] && !data.biome(cell).isWater
            ) {
                val b = Biome.SHALLOW_SEA
                tmp[0] = b.r; tmp[1] = b.g; tmp[2] = b.b
            }
            colors[i * 3] = tmp[0]; colors[i * 3 + 1] = tmp[1]; colors[i * 3 + 2] = tmp[2]
        }
        // Nature aquatique par sommet : le terrain continu en mode raffiné
        // (le trait de côte suit le vrai niveau de la mer), le biome de la
        // grille sinon — strictement le comportement historique.
        val vertexWater = BooleanArray(sphere.vertexCount) { i ->
            refinement?.water?.get(i) ?: data.biome(i).isWater
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
            val waterCount = (if (vertexWater[i0]) 1 else 0) +
                             (if (vertexWater[i1]) 1 else 0) +
                             (if (vertexWater[i2]) 1 else 0)
            val material = if (waterCount == 3) MATERIAL_WATER else MATERIAL_LAND

            for (k in 0..2) {
                val vi = faces[f + k]
                vertexData[o++] = px[vi]; vertexData[o++] = py[vi]; vertexData[o++] = pz[vi]
                vertexData[o++] = colors[vi * 3]
                vertexData[o++] = colors[vi * 3 + 1]
                vertexData[o++] = colors[vi * 3 + 2]
                vertexData[o++] = nx; vertexData[o++] = ny; vertexData[o++] = nz
                vertexData[o++] = material
            }
            f += 3
        }
    }

    val sizeBytes: Int get() = vertexData.size * 4
}
