package com.terra.sim

import com.terra.core.Seed
import com.terra.core.Vec3
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Points chauds et chaînes volcaniques — lot 1.7.
 *
 * ## Le mécanisme, et pourquoi il donne un alignement gratuit
 *
 * Un point chaud est un panache **fixe** dans le manteau ; c'est la plaque
 * qui défile au-dessus. Chaque édifice qu'il perce est donc emporté par elle,
 * et le suivant naît en amont. La chaîne s'aligne ainsi d'elle-même sur le
 * mouvement de la plaque, et l'âge croît avec la distance au panache — comme
 * la chaîne hawaïenne, dont les îles les plus anciennes sont les plus
 * lointaines et les plus basses.
 *
 * Rien de tout cela n'est dessiné : la tectonique fournit déjà l'axe de
 * rotation et la vitesse de chaque plaque, et il suffit de **remonter** ce
 * mouvement pour placer les édifices successifs. Un point chaud sous une
 * plaque rapide produit une longue traîne d'îles espacées ; sous une plaque
 * lente, un massif compact.
 *
 * ## Calibrage, mesuré avant écriture
 *
 * 8 à 20 panaches par monde (la Terre en compte environ 45 pour 510 millions
 * de km², le tirage fait varier l'activité du manteau). Édifice actif à
 * 2 600 m sur un rayon de 45 km, décroissant d'un facteur e tous les quatre
 * rangs : la chaîne s'affaisse et s'érode en s'éloignant. Douze édifices
 * espacés de 20 à 100 km selon la vitesse de la plaque, soit des chaînes de
 * 240 à 1 200 km.
 *
 * ## Bornes
 *
 * Le relief volcanique s'ajoute au relief tectonique, et le pire empilement
 * atteindrait 9 100 m. Il n'y a pas de calibrage à ajuster pour l'éviter :
 * `TerrainProfile.softLimit` comprime l'ensemble sous le plafond planétaire,
 * par construction. Leçon des lots 0.9.x, où trois calibrages successifs
 * avaient échoué là où une borne aurait suffi.
 */
class HotspotField(
    /** Position de chaque édifice, sur la sphère unité. */
    val centers: Array<Vec3>,
    /** Hauteur de chaque édifice, en mètres. */
    val heights: FloatArray,
    /** Rayon de chaque édifice, en radians. */
    val radii: FloatArray,
    /** Panache d'origine de chaque édifice, pour le calque et les tests. */
    val plumeId: IntArray,
    /** Nombre de panaches. */
    val plumeCount: Int
) {

    /**
     * Élévation volcanique en un point, en mètres.
     *
     * Profil gaussien par édifice, sommés : deux volcans voisins forment une
     * selle plutôt qu'un mur, comme les îles jumelles d'un archipel.
     */
    fun elevationAt(p: Vec3): Float {
        var sum = 0f
        for (i in centers.indices) {
            val c = centers[i]
            val dot = (p.x * c.x + p.y * c.y + p.z * c.z).coerceIn(-1f, 1f)
            // Distance angulaire approchée par la corde : à ces échelles
            // l'écart est inférieur au pour cent, et l'on évite un arccos par
            // édifice et par sommet de tuile.
            val chord2 = 2f * (1f - dot)
            val r = radii[i]
            val t2 = chord2 / (r * r)
            if (t2 < 9f) sum += heights[i] * exp(-t2)
        }
        return sum
    }

    companion object {

        const val MIN_PLUMES = 8
        const val MAX_PLUMES = 20

        /** Édifices par chaîne. */
        const val CHAIN_LENGTH = 12

        /** Hauteur de l'édifice actif, en mètres. */
        const val PEAK_HEIGHT_M = 2_600f

        /** Rayon de l'édifice actif, en radians (~45 km). */
        const val PEAK_RADIUS_RAD = 0.00706f

        /** Intervalle entre deux édifices, en millions d'années. */
        const val CHAIN_INTERVAL_MA = 1.5f

        /**
         * Construit les chaînes à partir du mouvement des plaques.
         *
         * Le panache étant fixe, l'édifice d'âge `k` se trouve là où était le
         * panache il y a `k · intervalle` : on applique donc la rotation de
         * la plaque, dans le sens du mouvement, pendant cette durée.
         */
        fun generate(seed: Seed, plates: PlateSet, sphere: Icosphere): HotspotField {
            val rng = seed.derive("points-chauds").rng()
            val plumeCount = rng.nextIntRange(MIN_PLUMES, MAX_PLUMES)

            val centers = ArrayList<Vec3>(plumeCount * CHAIN_LENGTH)
            val heights = ArrayList<Float>(plumeCount * CHAIN_LENGTH)
            val radii = ArrayList<Float>(plumeCount * CHAIN_LENGTH)
            val plumeId = ArrayList<Int>(plumeCount * CHAIN_LENGTH)

            for (plume in 0 until plumeCount) {
                val origin = randomUnit(rng)

                // La plaque sous le panache : c'est elle qui emporte les
                // édifices. On la trouve par le plus proche sommet de grille.
                val plate = plates.plateOf(nearestVertex(sphere, origin))
                val axis = plate.eulerAxis
                val omega = plate.omegaRadPerMa

                for (k in 0 until CHAIN_LENGTH) {
                    // Rotation inverse : l'édifice d'âge k est là où le
                    // panache se trouvait, donc là où la plaque a emporté la
                    // croûte percée à l'époque.
                    val angle = omega * CHAIN_INTERVAL_MA * k
                    val pos = rotateAround(origin, axis, angle)

                    val fade = exp(-k / 4f)
                    centers.add(pos)
                    heights.add(PEAK_HEIGHT_M * fade)
                    radii.add(PEAK_RADIUS_RAD * (0.5f + 0.5f * fade))
                    plumeId.add(plume)
                }
            }

            return HotspotField(
                centers.toTypedArray(),
                heights.toFloatArray(),
                radii.toFloatArray(),
                plumeId.toIntArray(),
                plumeCount
            )
        }

        /** Rotation de Rodrigues : p tourné de `angle` autour de `axis`. */
        private fun rotateAround(p: Vec3, axis: Vec3, angle: Float): Vec3 {
            val c = cos(angle)
            val s = sin(angle)
            val dot = axis.x * p.x + axis.y * p.y + axis.z * p.z
            val cx = axis.y * p.z - axis.z * p.y
            val cy = axis.z * p.x - axis.x * p.z
            val cz = axis.x * p.y - axis.y * p.x
            val x = p.x * c + cx * s + axis.x * dot * (1f - c)
            val y = p.y * c + cy * s + axis.y * dot * (1f - c)
            val z = p.z * c + cz * s + axis.z * dot * (1f - c)
            val inv = 1f / sqrt(x * x + y * y + z * z)
            return Vec3(x * inv, y * inv, z * inv)
        }

        private fun nearestVertex(sphere: Icosphere, p: Vec3): Int {
            var best = 0
            var bestDot = -2f
            for (i in 0 until sphere.vertexCount) {
                val v = sphere.vertices[i]
                val d = v.x * p.x + v.y * p.y + v.z * p.z
                if (d > bestDot) { bestDot = d; best = i }
            }
            return best
        }

        private fun randomUnit(rng: com.terra.core.Rng): Vec3 {
            while (true) {
                val x = rng.nextFloatSigned()
                val y = rng.nextFloatSigned()
                val z = rng.nextFloatSigned()
                val l2 = x * x + y * y + z * z
                if (l2 in 1e-4f..1f) {
                    val inv = 1f / sqrt(l2)
                    return Vec3(x * inv, y * inv, z * inv)
                }
            }
        }
    }
}
