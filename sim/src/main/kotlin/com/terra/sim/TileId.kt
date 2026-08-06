package com.terra.sim

import com.terra.core.PI_F
import com.terra.core.Vec3
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Une tuile du quadtree sphérique.
 *
 * Identifiée par sa face, son niveau de subdivision et sa position dans la
 * grille de ce niveau. Le niveau 0 correspond à une face entière ; chaque
 * niveau divise par deux la taille de l'arête.
 *
 * ## Ce que cette classe rend possible
 *
 * La question centrale du rendu planétaire est : *combien de géométrie faut-il
 * charger pour que l'image soit correcte ?* La réponse tient dans [splitFactor] :
 * on subdivise une tuile tant qu'elle occuperait trop de place à l'écran. Ce
 * simple critère produit une charge **bornée** quelle que soit l'altitude —
 * environ 630 tuiles au ras du sol contre 9 depuis l'espace lointain, mesuré par
 * simulation avant écriture de ce code.
 *
 * ## Échelles
 *
 * Pour une planète de rayon terrestre, avec des tuiles maillées en 17×17 :
 *
 * | Niveau | Arête de tuile | Pas du maillage |
 * |--------|----------------|-----------------|
 * | 0  | 10 000 km | 625 km |
 * | 8  | 39 km     | 2,4 km |
 * | 16 | 153 m     | 9,5 m  |
 * | 23 | 1,2 m     | 7,5 cm |
 */
data class TileId(
    val face: Int,
    val level: Int,
    val x: Int,
    val y: Int
) {

    init {
        require(face in 0 until CubeSphere.FACE_COUNT) { "face invalide : $face" }
        require(level in 0..MAX_LEVEL) { "niveau invalide : $level" }
    }

    /** Nombre de tuiles par côté de face à ce niveau. */
    val gridSize: Int get() = 1 shl level

    /** Bornes de la tuile en coordonnées de face, dans [-1, 1]. */
    val s0: Float get() = -1f + 2f * x / gridSize
    val s1: Float get() = -1f + 2f * (x + 1) / gridSize
    val t0: Float get() = -1f + 2f * y / gridSize
    val t1: Float get() = -1f + 2f * (y + 1) / gridSize

    /** Point de la sphère unité pour une position relative dans la tuile. */
    fun sample(su: Float, tv: Float): Vec3 =
        CubeSphere.toSphere(
            face,
            s0 + (s1 - s0) * su,
            t0 + (t1 - t0) * tv
        )

    val corners: Array<Vec3>
        get() = arrayOf(
            sample(0f, 0f), sample(1f, 0f), sample(1f, 1f), sample(0f, 1f)
        )

    /** Centre de la tuile, projeté sur la sphère unité. */
    val center: Vec3
        get() {
            val c = corners
            return ((c[0] + c[1] + c[2] + c[3]) * 0.25f).normalized()
        }

    /**
     * Rayon englobant, en unités de sphère unité. Multiplier par le rayon
     * planétaire pour obtenir des mètres.
     */
    val boundingRadius: Float
        get() {
            val ctr = center
            var r = 0f
            for (c in corners) {
                val d = (c - ctr).length
                if (d > r) r = d
            }
            return r
        }

    /** Longueur d'arête approximative en mètres. */
    fun edgeLengthM(planetRadiusM: Float): Float =
        (PI_F * 0.5f / gridSize) * planetRadiusM

    /**
     * Rapport entre la taille apparente de la tuile et sa distance à la caméra.
     *
     * Supérieur au seuil retenu, la tuile occupe trop d'écran et doit être
     * subdivisée. Le rayon planétaire n'apparaît pas dans la formule : il se
     * simplifie, puisque numérateur et dénominateur sont dans la même unité.
     *
     * @param cameraUnit position de la caméra en unités de sphère unité ; sa
     *   longueur vaut donc `(rayon + altitude) / rayon`.
     */
    fun splitFactor(cameraUnit: Vec3): Float {
        val r = boundingRadius
        val distance = max(1e-7f, (cameraUnit - center).length - r)
        return (r * 2f) / distance
    }

    fun shouldSplit(cameraUnit: Vec3, threshold: Float, maxLevel: Int): Boolean =
        level < maxLevel && splitFactor(cameraUnit) > threshold

    /**
     * Élimination par l'horizon.
     *
     * Une tuile située derrière la courbure de la planète est invisible quelle
     * que soit l'orientation de la caméra. À basse altitude, ce seul test écarte
     * l'écrasante majorité de la sphère et évite de descendre l'arbre inutilement.
     */
    fun isVisible(cameraUnit: Vec3): Boolean {
        val camLength = cameraUnit.length
        if (camLength <= 1f) return true                 // caméra sous la surface
        val horizonCos = -sqrt(max(0f, 1f - 1f / (camLength * camLength)))
        val facing = center dot (cameraUnit / camLength)
        return facing > horizonCos - boundingRadius
    }

    val parent: TileId?
        get() = if (level == 0) null else TileId(face, level - 1, x / 2, y / 2)

    /** Les quatre enfants, ou null au niveau maximal. */
    fun children(): Array<TileId>? {
        if (level >= MAX_LEVEL) return null
        val l = level + 1
        return arrayOf(
            TileId(face, l, x * 2, y * 2),
            TileId(face, l, x * 2 + 1, y * 2),
            TileId(face, l, x * 2, y * 2 + 1),
            TileId(face, l, x * 2 + 1, y * 2 + 1)
        )
    }

    /** Identifiant compact, utile comme clé de cache. */
    fun packed(): Long =
        (face.toLong() shl 58) or (level.toLong() shl 52) or
                (x.toLong() shl 26) or y.toLong()

    override fun toString(): String = "T$face/$level/$x,$y"

    companion object {
        /**
         * Niveau maximal.
         *
         * Au niveau 23, une tuile mesure environ 1,2 m et le pas de son maillage
         * quelques centimètres. Descendre plus bas n'aurait pas de sens : le
         * flottant 32 bits ne distingue plus rien à cette échelle, et la
         * simulation n'a de toute façon aucune donnée à cette résolution.
         */
        const val MAX_LEVEL = 23

        /**
         * Seuil de subdivision par défaut, validé par simulation : environ 630
         * tuiles visibles au ras du sol, 9 depuis l'espace lointain.
         */
        const val DEFAULT_SPLIT_THRESHOLD = 1.4f

        fun roots(): Array<TileId> =
            Array(CubeSphere.FACE_COUNT) { TileId(it, 0, 0, 0) }

        /**
         * Clé compactée du parent d'une clé compactée, ou −1 au niveau racine.
         *
         * Vit ici, en `:sim`, pour être testée en CI face à [TileId.parent] —
         * la version objet. Elle a d'abord vécu côté application, hors de
         * portée des tests : une divergence entre les deux écritures aurait
         * cassé le repli sur l'ancêtre sans qu'aucun test ne rougisse.
         */
        fun parentKey(key: Long): Long {
            val level = ((key ushr 52) and 0x3F).toInt()
            if (level == 0) return -1L
            val face = (key ushr 58) and 0x3F
            val x = ((key ushr 26) and 0x3FFFFFF) shr 1
            val y = (key and 0x3FFFFFF) shr 1
            return (face shl 58) or ((level - 1).toLong() shl 52) or (x shl 26) or y
        }

        fun unpack(key: Long): TileId = TileId(
            face = ((key ushr 58) and 0x3F).toInt(),
            level = ((key ushr 52) and 0x3F).toInt(),
            x = ((key ushr 26) and 0x3FFFFFF).toInt(),
            y = (key and 0x3FFFFFF).toInt()
        )

        /**
         * Sélectionne les tuiles à afficher pour une position de caméra donnée.
         *
         * Parcours descendant depuis les six faces, avec élimination par
         * l'horizon. Le résultat est l'ensemble des feuilles retenues.
         */
        fun select(
            cameraUnit: Vec3,
            threshold: Float = DEFAULT_SPLIT_THRESHOLD,
            maxLevel: Int = MAX_LEVEL,
            budget: Int = 4096
        ): List<TileId> {
            val result = ArrayList<TileId>(256)
            val stack = ArrayDeque<TileId>()
            for (root in roots()) stack.addLast(root)

            while (stack.isNotEmpty() && result.size < budget) {
                val tile = stack.removeLast()
                if (!tile.isVisible(cameraUnit)) continue
                if (tile.shouldSplit(cameraUnit, threshold, maxLevel)) {
                    tile.children()?.forEach { stack.addLast(it) } ?: result.add(tile)
                } else {
                    result.add(tile)
                }
            }
            return result
        }
    }
}
