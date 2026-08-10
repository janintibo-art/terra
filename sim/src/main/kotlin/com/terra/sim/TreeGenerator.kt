package com.terra.sim

import com.terra.core.Rng
import com.terra.core.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Générateur d'arbres procéduraux — lot 3.1.
 *
 * ## Ce que c'est, ce que ce n'est pas
 *
 * Une grammaire de branchement paramétrée qui produit un SQUELETTE
 * déterministe : des segments (base, pointe, rayons, profondeur, parent)
 * dans un repère local, Y vers le haut, base du tronc à l'origine, tout en
 * mètres. Le maillage viendra au lot 3.3, les familles d'espèces au 3.2,
 * la répartition sur la planète au 3.6. Poser l'ossature d'abord permet de
 * la tester en CI — comptages exacts, continuité, bornes d'angles — avant
 * qu'un seul triangle n'existe.
 *
 * ## La grammaire (instruite dans validation/arbres.py)
 *
 * Chaque branche porte [TreeParams.children] enfants À SA POINTE : un
 * enfant « continuation » à angle réduit (25 % du nominal, c'est lui qui
 * fait les fûts qui filent) et k−1 branches à l'angle nominal ± dispersion.
 * L'azimut tourne de l'angle d'or (137,51°) entre enfants successifs —
 * la phyllotaxie évite que les étages s'alignent. Un tropisme redresse
 * ensuite chaque direction vers le haut par nlerp, dont l'instruction a
 * vérifié la monotonie. Longueurs et rayons suivent une progression
 * géométrique ; la pointe d'un parent porte exactement le rayon de la base
 * de ses enfants, donc aucun ressaut au raccord.
 */
object TreeGenerator {

    /** Refus au-delà : un paramétrage fou doit échouer NET et déterministe,
     *  pas s'écrêter en silence (la forme dépendrait alors du budget). */
    const val MAX_SEGMENTS = 20_000

    /** Azimut entre enfants successifs : π(3 − √5) = 137,51°. */
    const val GOLDEN_ANGLE_RAD = 2.399963f

    /** L'enfant « continuation » ouvre à 25 % de l'angle nominal. */
    const val CONTINUATION_ANGLE_RATIO = 0.25f

    fun generate(params: TreeParams, seed: Long): TreeSkeleton {
        params.validate()
        val rng = Rng(seed)
        val segments = ArrayList<TreeSkeleton.Segment>(params.worstCaseSegments())

        // File explicite plutôt que récursion : la profondeur est bornée,
        // mais une file rend l'ORDRE de parcours (donc le flux aléatoire)
        // indépendant de la pile d'appels — le déterminisme est structurel.
        val queue = ArrayDeque<Int>()

        val trunkDir = tropism(Vec3(0f, 1f, 0f), params.straightness)
        segments += TreeSkeleton.Segment(
            baseX = 0f, baseY = 0f, baseZ = 0f,
            tipX = trunkDir.x * params.trunkLengthM,
            tipY = trunkDir.y * params.trunkLengthM,
            tipZ = trunkDir.z * params.trunkLengthM,
            radiusBaseM = params.trunkRadiusM,
            radiusTipM = params.trunkRadiusM * params.radiusRatio,
            depth = 0,
            parent = -1
        )
        queue.addLast(0)

        while (queue.isNotEmpty()) {
            val parentIndex = queue.removeFirst()
            val parent = segments[parentIndex]
            if (parent.depth >= params.maxDepth) continue

            val childDepth = parent.depth + 1
            val childLength = params.trunkLengthM * pow(params.lengthRatio, childDepth)
            val childRadiusBase = parent.radiusTipM
            val parentDir = parent.direction()

            // Base d'azimut PAR BRANCHE, tirée du flux : sans elle, toutes
            // les branches d'une même profondeur pointeraient leurs enfants
            // dans les mêmes directions absolues.
            var azimuth = rng.nextFloatRange(0f, 2f * PI_F)

            // Émission d'un enfant. L'azimut avance d'un angle d'or à
            // chaque appel — l'ordre des appels fait donc partie de la
            // définition de la forme : enfants de pointe d'abord, puis les
            // verticilles de bas en haut. Le changer changerait les mondes.
            fun emit(
                baseX: Float, baseY: Float, baseZ: Float,
                radiusBase: Float, nominalAngle: Float
            ) {
                val angle = (nominalAngle + rng.nextFloatRange(
                    -params.angleJitterRad, params.angleJitterRad
                )).coerceIn(0f, MAX_BRANCH_ANGLE_RAD)

                val dir = tropism(
                    rotateFrom(parentDir, angle, azimuth),
                    params.straightness
                )
                azimuth += GOLDEN_ANGLE_RAD

                segments += TreeSkeleton.Segment(
                    baseX = baseX, baseY = baseY, baseZ = baseZ,
                    tipX = baseX + dir.x * childLength,
                    tipY = baseY + dir.y * childLength,
                    tipZ = baseZ + dir.z * childLength,
                    radiusBaseM = radiusBase,
                    radiusTipM = radiusBase * params.radiusRatio,
                    depth = childDepth,
                    parent = parentIndex
                )
                queue.addLast(segments.size - 1)
            }

            // Enfants de POINTE : le premier est la continuation du fût.
            for (child in 0 until params.children) {
                val nominal = if (child == 0) {
                    params.branchAngleRad * CONTINUATION_ANGLE_RATIO
                } else {
                    params.branchAngleRad
                }
                emit(parent.tipX, parent.tipY, parent.tipZ, childRadiusBase, nominal)
            }

            // Enfants LATÉRAUX (lot 3.2) : des verticilles étagés le long du
            // parent. Sans eux, aucun jeu de paramètres ne fait un conifère —
            // un sapin porte ses branches le long de son fût, pas seulement à
            // sa cime. Les étages se répartissent strictement entre la
            // fraction d'attache et la pointe : jamais sur la base (le
            // segment y a déjà son parent), jamais sur la pointe (les enfants
            // de pointe y sont déjà).
            for (whorl in 0 until params.lateralWhorls) {
                val t = params.attachStartFraction +
                    (1f - params.attachStartFraction) *
                    (whorl + 1).toFloat() / (params.lateralWhorls + 1).toFloat()
                val bx = parent.baseX + (parent.tipX - parent.baseX) * t
                val by = parent.baseY + (parent.tipY - parent.baseY) * t
                val bz = parent.baseZ + (parent.tipZ - parent.baseZ) * t
                // Rayon du parent AU POINT d'attache : la continuité du lot
                // 3.1 se généralise, un latéral ne naît jamais plus gros que
                // le fût qui le porte.
                val rb = parent.radiusBaseM +
                    (parent.radiusTipM - parent.radiusBaseM) * t
                repeat(params.lateralPerWhorl) {
                    emit(bx, by, bz, rb, params.branchAngleRad)
                }
            }
        }

        return TreeSkeleton(segments)
    }

    // ------------------------------------------------------------ géométrie

    /**
     * Direction à [angle] du vecteur [axis], à l'[azimuth] donné autour de
     * lui. Le repère orthonormé local est construit par le truc habituel du
     * plus petit axe cardinal, sûr pour tout axis unitaire.
     */
    private fun rotateFrom(axis: Vec3, angle: Float, azimuth: Float): Vec3 {
        val helper = if (kotlin.math.abs(axis.y) < 0.9f) Vec3(0f, 1f, 0f) else Vec3(1f, 0f, 0f)
        val u = (axis cross helper).normalized()
        val v = (u cross axis)
        val s = sin(angle)
        return (axis * cos(angle) + u * (s * cos(azimuth)) + v * (s * sin(azimuth)))
            .normalized()
    }

    /** Redressement vers le haut : nlerp, monotone (validation §3). */
    private fun tropism(dir: Vec3, straightness: Float): Vec3 {
        if (straightness <= 0f) return dir
        return (dir * (1f - straightness) + Vec3(0f, straightness, 0f)).normalized()
    }

    private fun pow(base: Float, exp: Int): Float {
        var r = 1f
        repeat(exp) { r *= base }
        return r
    }

    private const val PI_F = 3.1415927f

    /** Une branche ne descend jamais plus bas que l'horizontale ouverte. */
    private const val MAX_BRANCH_ANGLE_RAD = 1.55f
}

/**
 * Paramètres de la grammaire — les quatre familles du plan (angle,
 * ramification, longueur, conicité) plus la dispersion et le tropisme qui
 * les rendent vivants. Les espèces du lot 3.2 seront des jeux de valeurs.
 */
data class TreeParams(
    /** Longueur du tronc, en mètres. */
    val trunkLengthM: Float,
    /** Rayon du tronc à sa base, en mètres. */
    val trunkRadiusM: Float,
    /** Longueur d'un enfant / longueur au niveau du parent. */
    val lengthRatio: Float,
    /** Rayon de pointe / rayon de base d'un même segment. */
    val radiusRatio: Float,
    /** Angle nominal d'ouverture des branches, en radians. */
    val branchAngleRad: Float,
    /** Dispersion uniforme ± autour de l'angle nominal. */
    val angleJitterRad: Float,
    /** Enfants par branche, continuation comprise. */
    val children: Int,
    /** Profondeur maximale ; le tronc est la profondeur 0. */
    val maxDepth: Int,
    /** Redressement vers le haut, 0 (aucun) à 1 (vertical). */
    val straightness: Float,
    /** Étages de branches le long du parent (lot 3.2). 0 = pointe seule. */
    val lateralWhorls: Int = 0,
    /** Branches par étage latéral. */
    val lateralPerWhorl: Int = 0,
    /** Fraction du parent sous laquelle aucun étage ne s'attache. */
    val attachStartFraction: Float = 0.35f
) {

    /** Facteur de branchement réel : pointe + latéraux. */
    fun branchingFactor(): Int = children + lateralWhorls * lateralPerWhorl

    fun worstCaseSegments(): Int {
        // Somme k^0..k^D en entier 64 bits : le débordement 32 bits d'un
        // paramétrage absurde donnerait un nombre NÉGATIF qui passerait la
        // garde.
        var total = 0L
        var level = 1L
        val k = branchingFactor().toLong()
        for (d in 0..maxDepth) {
            total += level
            if (total > TreeGenerator.MAX_SEGMENTS) return Int.MAX_VALUE
            level *= k
        }
        return total.toInt()
    }

    fun validate() {
        require(trunkLengthM > 0f && trunkRadiusM > 0f) { "Dimensions du tronc non positives" }
        require(lengthRatio in 0.1f..0.95f) { "lengthRatio hors de [0,1 ; 0,95] : $lengthRatio" }
        require(radiusRatio in 0.1f..0.95f) { "radiusRatio hors de [0,1 ; 0,95] : $radiusRatio" }
        require(branchAngleRad in 0f..1.55f) { "branchAngleRad hors de [0 ; 1,55] : $branchAngleRad" }
        require(angleJitterRad in 0f..0.6f) { "angleJitterRad hors de [0 ; 0,6] : $angleJitterRad" }
        require(children in 1..6) { "children hors de [1 ; 6] : $children" }
        require(maxDepth in 0..12) { "maxDepth hors de [0 ; 12] : $maxDepth" }
        require(straightness in 0f..1f) { "straightness hors de [0 ; 1] : $straightness" }
        require(lateralWhorls in 0..8) { "lateralWhorls hors de [0 ; 8] : $lateralWhorls" }
        require(lateralPerWhorl in 0..8) { "lateralPerWhorl hors de [0 ; 8] : $lateralPerWhorl" }
        require(attachStartFraction in 0f..0.9f) {
            "attachStartFraction hors de [0 ; 0,9] : $attachStartFraction"
        }
        require(branchingFactor() >= 1) { "Un arbre sans aucun enfant possible" }
        require(worstCaseSegments() <= TreeGenerator.MAX_SEGMENTS) {
            "Paramétrage à plus de ${TreeGenerator.MAX_SEGMENTS} segments : refusé"
        }
    }

    companion object {
        /** L'espèce par défaut : le feuillu (voir [TreeSpecies]). */
        fun defaultTree(): TreeParams = TreeSpecies.FEUILLU.params()
    }
}

/** Le squelette produit : immuable, prêt pour le maillage (3.3) ou le fil
 *  de fer de la console. */
class TreeSkeleton(val segments: List<Segment>) {

    data class Segment(
        val baseX: Float, val baseY: Float, val baseZ: Float,
        val tipX: Float, val tipY: Float, val tipZ: Float,
        val radiusBaseM: Float,
        val radiusTipM: Float,
        val depth: Int,
        val parent: Int
    ) {
        fun direction(): Vec3 =
            Vec3(tipX - baseX, tipY - baseY, tipZ - baseZ).normalized()

        fun lengthM(): Float =
            Vec3(tipX - baseX, tipY - baseY, tipZ - baseZ).length
    }

    /** Hauteur hors sol, pour cadrer une visualisation. */
    fun heightM(): Float = segments.maxOf { it.tipY }.coerceAtLeast(0f)

    /** Envergure : distance horizontale maximale à l'axe du tronc. */
    fun spreadM(): Float = segments.maxOf {
        kotlin.math.sqrt(it.tipX * it.tipX + it.tipZ * it.tipZ)
    }

    /**
     * Élancement = envergure / hauteur. C'est le critère qui sépare les
     * familles à l'œil comme au test : un conifère tient sous 0,39, un
     * feuillu passe au-dessus (bornes mesurées dans validation/especes.py,
     * qui a écarté la conicité — elle ne sépare pas).
     */
    fun slendernessRatio(): Float {
        val h = heightM()
        return if (h > 1e-6f) spreadM() / h else 0f
    }

    /** Segments nés le long d'un parent plutôt qu'à sa pointe (lot 3.2). */
    fun lateralCount(): Int = segments.count { s ->
        if (s.parent < 0) return@count false
        val p = segments[s.parent]
        val dx = s.baseX - p.tipX
        val dy = s.baseY - p.tipY
        val dz = s.baseZ - p.tipZ
        dx * dx + dy * dy + dz * dz > 1e-12f
    }

    /**
     * Sommets fil de fer : deux points par segment, position (3) + teinte
     * (3), la teinte fonçant du tronc vers la canopée pour lire la
     * profondeur d'un coup d'œil. Formulé ici pour être TESTÉ — :app ne
     * fait que téléverser ce tampon.
     */
    fun wireframeVertices(): FloatArray {
        val out = FloatArray(segments.size * 2 * 6)
        var o = 0
        for (s in segments) {
            val t = (s.depth / 6f).coerceIn(0f, 1f)
            val r = 0.45f - 0.25f * t
            val g = 0.30f + 0.45f * t
            val b = 0.15f + 0.10f * t
            out[o++] = s.baseX; out[o++] = s.baseY; out[o++] = s.baseZ
            out[o++] = r; out[o++] = g; out[o++] = b
            out[o++] = s.tipX; out[o++] = s.tipY; out[o++] = s.tipZ
            out[o++] = r; out[o++] = g; out[o++] = b
        }
        return out
    }
}
