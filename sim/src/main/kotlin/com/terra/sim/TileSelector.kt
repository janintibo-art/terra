package com.terra.sim

import com.terra.core.Vec3
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Cône englobant le champ de vision.
 *
 * ## Pourquoi ce test s'ajoute à l'horizon
 *
 * L'élimination par l'horizon écarte la face cachée de la planète. Elle ne suffit
 * pas : au ras du sol, tout le tour de l'horizon reste « visible » au sens
 * géométrique, alors que l'écran n'en montre qu'un cinquième. Sans ce second
 * filtre, on maillerait environ quatre fois trop de terrain.
 *
 * Un cône plutôt qu'un vrai tronc de pyramide : le test tient en un produit
 * scalaire, il est conservateur — il ne rejette jamais une tuile visible — et
 * son léger excès de tolérance coûte bien moins cher que six tests de plans.
 */
class ViewCone(
    /** Position de la caméra, en unités de sphère unité. */
    val apex: Vec3,
    /** Direction de visée, unitaire. */
    val axis: Vec3,
    /** Demi-angle au sommet, en radians. */
    val halfAngleRad: Float
) {

    /** Vrai si la sphère englobante peut intersecter le cône. */
    fun mayContain(center: Vec3, radius: Float): Boolean {
        val dx = center.x - apex.x
        val dy = center.y - apex.y
        val dz = center.z - apex.z
        val distSq = dx * dx + dy * dy + dz * dz
        // Trois rayons : même garde que la copie du sélecteur — les deux
        // écritures doivent rester jumelles, le test du nadir y veille.
        if (distSq <= radius * radius * 9f) return true     // caméra dans ou contre la tuile

        val dist = sqrt(distSq)
        val cosAngle = ((dx * axis.x + dy * axis.y + dz * axis.z) / dist)
            .coerceIn(-1f, 1f)
        val angle = acos(cosAngle)
        val angularRadius = asin(min(1f, radius / dist))
        return angle <= halfAngleRad + angularRadius
    }

    companion object {
        /**
         * Cône circonscrit à un champ de vision rectangulaire.
         *
         * On prend la diagonale, seule façon de garantir qu'aucun coin d'écran
         * ne soit rejeté. La marge compense les mouvements de caméra entre deux
         * sélections.
         */
        fun fromCamera(
            apex: Vec3,
            forward: Vec3,
            verticalFovRad: Float,
            aspect: Float,
            marginRad: Float = 0.12f
        ): ViewCone {
            val halfV = tan(verticalFovRad * 0.5f)
            val diagonal = atan(halfV * sqrt(1f + aspect * aspect))
            return ViewCone(apex, forward.normalized(), diagonal + marginRad)
        }
    }
}

/**
 * Sélection des tuiles à afficher, conçue pour être appelée à chaque image.
 *
 * ## Le problème que cette classe résout
 *
 * La version naïve exposée par [TileId] lit des propriétés calculées : chaque
 * lecture de `corners` alloue neuf objets, `center` et `boundingRadius` la
 * rappellent, et `splitFactor` comme `isVisible` les rappellent à leur tour —
 * environ **quatre-vingt-cinq allocations par tuile évaluée**.
 *
 * Une sélection visite quelques milliers de nœuds. À soixante images par
 * seconde, cela représenterait une quinzaine de millions d'objets alloués par
 * seconde : le ramasse-miettes d'Android s'effondrerait, et la saccade serait
 * d'autant plus difficile à diagnostiquer que le nombre de triangles, lui,
 * serait correct.
 *
 * Ici, la géométrie de chaque nœud est calculée **une seule fois**, dans des
 * champs primitifs, avec une pile d'identifiants compactés en entiers longs. Le
 * seul objet créé est la liste des tuiles retenues.
 *
 * L'instance est réutilisable d'une image à l'autre, mais n'est **pas** sûre
 * entre fils d'exécution : elle porte des tampons de travail. Un sélecteur par
 * fil.
 */
class TileSelector(
    var threshold: Float = TileId.DEFAULT_SPLIT_THRESHOLD,
    var maxLevel: Int = TileId.MAX_LEVEL,
    var budget: Int = 2048
) {

    private var stack = LongArray(8192)
    private val corner = FloatArray(12)

    /** Nombre de nœuds parcourus lors de la dernière sélection. */
    var visitedNodes: Int = 0
        private set

    /** Nombre de nœuds écartés par l'horizon ou le cône de vision. */
    var culledNodes: Int = 0
        private set

    // Géométrie du nœud courant, en champs primitifs pour éviter toute allocation.
    private var centerX = 0f
    private var centerY = 0f
    private var centerZ = 0f
    private var radius = 0f

    /**
     * Position de la caméra, en **double** — invariant n°5 du projet.
     *
     * ## L'annulation qui cassait la subdivision près du sol (v0.10.7)
     *
     * Sur la sphère unité, deux mètres valent 3·10⁻⁷ : deux ulp et demi de
     * flottant 32 bits. Soustraire deux positions voisines de 1,0 y détruit
     * toute la précision — et multiplier le centre par le rayon du sol
     * introduisait une erreur d'arrondi du même ordre que la distance
     * cherchée. La subdivision plafonnait donc au hasard près du sol, sauf
     * quand le terrain était exactement au niveau de la mer : le facteur
     * valait alors 1, la multiplication était exacte, et le défaut se
     * cachait.
     *
     * Les centres de tuiles restent en 32 bits — leur précision propre,
     * 40 cm, suffit largement à un critère de niveau de détail. C'est la
     * soustraction qui devait passer en double.
     */
    private var camXd = 0.0
    private var camYd = 0.0
    private var camZd = 0.0

    /**
     * Remplit [out] avec les tuiles à afficher.
     *
     * @param cameraUnit position de la caméra en unités de sphère unité
     * @param cone champ de vision ; null pour ne filtrer que par l'horizon
     */
    /**
     * Rayon de référence pour juger les distances, en unités de sphère unité.
     *
     * Le sélecteur mesurait la distance à la sphère au NIVEAU DE LA MER : à
     * dix mètres d'un plateau de 874 m, il croyait la caméra à 874 m du sol
     * et s'arrêtait à un niveau grossier (v0.10.5). En donnant ici le rayon
     * du terrain sous la caméra, les distances redeviennent celles que l'œil
     * perçoit, et la subdivision descend là où il faut.
     */
    var groundRadiusUnit: Float = 1f

    fun select(cameraUnit: Vec3, out: MutableList<TileId>, cone: ViewCone? = null) {
        select(
            cameraUnit.x.toDouble(), cameraUnit.y.toDouble(), cameraUnit.z.toDouble(),
            out, cone
        )
    }

    /**
     * Variante en double précision, seule exacte près du sol. Le renderer
     * l'appelle avec la position de l'œil divisée par le rayon planétaire,
     * calculée en double de bout en bout.
     */
    fun select(
        camX: Double, camY: Double, camZ: Double,
        out: MutableList<TileId>,
        cone: ViewCone? = null
    ) {
        camXd = camX; camYd = camY; camZd = camZ
        val cameraUnit = Vec3(camX.toFloat(), camY.toFloat(), camZ.toFloat())
        out.clear()
        visitedNodes = 0
        culledNodes = 0

        var top = 0
        for (face in 0 until CubeSphere.FACE_COUNT) {
            stack[top++] = pack(face, 0, 0, 0)
        }

        val camLength = cameraUnit.length
        val horizonCos = if (camLength <= 1f) -1f
                         else -sqrt(max(0f, 1f - 1f / (camLength * camLength)))
        val invCamLength = if (camLength > 1e-9f) 1f / camLength else 0f

        while (top > 0 && out.size < budget) {
            val key = stack[--top]
            visitedNodes++

            val face = unpackFace(key)
            val level = unpackLevel(key)
            val x = unpackX(key)
            val y = unpackY(key)

            computeGeometry(face, level, x, y)

            // Horizon : la tuile est-elle sur la face tournée vers la caméra ?
            val facing = (centerX * cameraUnit.x + centerY * cameraUnit.y +
                    centerZ * cameraUnit.z) * invCamLength
            if (camLength > 1f && facing <= horizonCos - radius) {
                culledNodes++
                continue
            }

            // Champ de vision.
            if (cone != null && !coneAccepts(cone)) {
                culledNodes++
                continue
            }

            // Subdivision.
            // Le centre de tuile est ramené au rayon du terrain local, et
            // toute la soustraction se fait en double : voir [camXd].
            val g = groundRadiusUnit.toDouble()
            val dxd = camXd - centerX.toDouble() * g
            val dyd = camYd - centerY.toDouble() * g
            val dzd = camZd - centerZ.toDouble() * g
            val distance = max(
                1e-12,
                kotlin.math.sqrt(dxd * dxd + dyd * dyd + dzd * dzd) - radius.toDouble() * g
            )
            val factor = (radius.toDouble() * 2.0) / distance

            if (level < maxLevel && factor > threshold) {
                ensureCapacity(top + 4)
                val l = level + 1
                stack[top++] = pack(face, l, x * 2, y * 2)
                stack[top++] = pack(face, l, x * 2 + 1, y * 2)
                stack[top++] = pack(face, l, x * 2, y * 2 + 1)
                stack[top++] = pack(face, l, x * 2 + 1, y * 2 + 1)
            } else {
                out.add(TileId(face, level, x, y))
            }
        }
    }

    private fun coneAccepts(cone: ViewCone): Boolean {
        // Deux précautions, chacune payée d'un défaut :
        //
        //  - la position de la caméra vient des champs double, pas de l'apex
        //    32 bits du cône (invariant n°5) ;
        //  - le centre de tuile est ramené au rayon du TERRAIN, comme pour la
        //    subdivision. Sans cela, sur un plateau de 387 m, le garde de
        //    proximité croyait les tuiles à 389 m au lieu de deux : il cessait
        //    de protéger dès le niveau 16 et le cône éliminait la branche
        //    contenant l'observateur — donc toutes ses descendantes fines.
        //    Le niveau plafonnait à 17 au lieu de 23, et le premier plan
        //    disparaissait. Au niveau de la mer le facteur valait 1, la
        //    distance était juste, et le défaut restait invisible (v0.10.7).
        val g = groundRadiusUnit.toDouble()
        val dx = (centerX.toDouble() * g - camXd).toFloat()
        val dy = (centerY.toDouble() * g - camYd).toFloat()
        val dz = (centerZ.toDouble() * g - camZd).toFloat()

        // Garde-fou des tuiles proches — v0.9.5. Cette copie sans allocation
        // de [ViewCone.mayContain] avait PERDU le cas « caméra dans la
        // tuile » : au ras du sol en vue rasante, les tuiles qui contiennent
        // l'observateur ont leur centre derrière l'œil, toute la pile était
        // rejetée, et le premier plan disparaissait (fond de brume visible
        // sous l'horizon). Le garde est élargi à trois rayons : une tuile
        // voisine immédiate remplit l'écran même le centre hors du cône.
        // Deux écritures du même test ont divergé une fois de plus — le test
        // de couverture du nadir les verrouille désormais ensemble.
        val distSqEarly = dx * dx + dy * dy + dz * dz
        val nearGuard = radius * 3f
        if (distSqEarly <= nearGuard * nearGuard) return true
        val distSq = distSqEarly
        if (distSq <= radius * radius) return true
        val dist = sqrt(distSq)
        val cosAngle = ((dx * cone.axis.x + dy * cone.axis.y + dz * cone.axis.z) / dist)
            .coerceIn(-1f, 1f)
        return acos(cosAngle) <= cone.halfAngleRad + asin(min(1f, radius / dist))
    }

    /** Centre et rayon englobant du nœud, sans allouer. */
    private fun computeGeometry(face: Int, level: Int, x: Int, y: Int) {
        val grid = 1 shl level
        val s0 = -1f + 2f * x / grid
        val s1 = -1f + 2f * (x + 1) / grid
        val t0 = -1f + 2f * y / grid
        val t1 = -1f + 2f * (y + 1) / grid

        CubeSphere.toSphereInto(face, s0, t0, corner, 0)
        CubeSphere.toSphereInto(face, s1, t0, corner, 3)
        CubeSphere.toSphereInto(face, s1, t1, corner, 6)
        CubeSphere.toSphereInto(face, s0, t1, corner, 9)

        var cx = 0f; var cy = 0f; var cz = 0f
        for (i in 0 until 4) {
            cx += corner[i * 3]; cy += corner[i * 3 + 1]; cz += corner[i * 3 + 2]
        }
        val inv = 1f / sqrt(cx * cx + cy * cy + cz * cz)
        centerX = cx * inv; centerY = cy * inv; centerZ = cz * inv

        var r2 = 0f
        for (i in 0 until 4) {
            val ax = corner[i * 3] - centerX
            val ay = corner[i * 3 + 1] - centerY
            val az = corner[i * 3 + 2] - centerZ
            val d2 = ax * ax + ay * ay + az * az
            if (d2 > r2) r2 = d2
        }
        radius = sqrt(r2)
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= stack.size) return
        stack = stack.copyOf(max(needed, stack.size * 2))
    }

    companion object {
        fun pack(face: Int, level: Int, x: Int, y: Int): Long =
            (face.toLong() shl 58) or (level.toLong() shl 52) or
                    (x.toLong() shl 26) or y.toLong()

        fun unpackFace(key: Long): Int = ((key ushr 58) and 0x3F).toInt()
        fun unpackLevel(key: Long): Int = ((key ushr 52) and 0x3F).toInt()
        fun unpackX(key: Long): Int = ((key ushr 26) and 0x3FFFFFF).toInt()
        fun unpackY(key: Long): Int = (key and 0x3FFFFFF).toInt()
    }
}
