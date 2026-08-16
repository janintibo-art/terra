package com.terra.sim

import kotlin.math.atan
import kotlin.math.tan

/**
 * Niveaux de détail des arbres — lot 3.3-b.
 *
 * Chaque niveau dit trois choses au maillage : combien de côtés par tube,
 * combien de niveaux de rameaux élaguer, et à quel rythme poser les
 * touffes. Les coûts indicatifs sont ceux d'un conifère, mesurés par les
 * tests.
 */
enum class TreeDetail(
    val label: String,
    /** Côtés par tube, ou 0 pour le panneau. */
    private val sides: Int,
    /** Niveaux de rameaux supprimés en partant des plus fins. */
    val prunedDepths: Int,
    /** Une touffe posée tous les N segments garnis. */
    val foliageStride: Int
) {

    /** Tout : 8 côtés, tous les rameaux, une touffe chacun. ~17 800 tris. */
    FULL("plein", 8, 0, 1),

    /** 5 côtés, une touffe sur deux. ~5 700 tris. */
    MEDIUM("moyen", 5, 0, 2),

    /**
     * 3 côtés et les rameaux les plus fins élagués : sur un conifère, cela
     * retire 1 000 des 1 111 segments — l'essentiel du coût pour des
     * branches qui, à cette distance, tiennent dans un pixel. ~200 tris.
     */
    LOW("bas", 3, 1, 3),

    /** Deux lames croisées. 4 triangles. */
    BILLBOARD("panneau", 0, 0, 0);

    fun sidesFor(requested: Int): Int =
        if (this == FULL) requested else minOf(requested, sides).coerceAtLeast(3)
}

/**
 * Allocateur de niveaux sous budget — le cœur du lot 3.3-b.
 *
 * ## Pourquoi un allocateur et pas des seuils
 *
 * La feuille de route suggérait des seuils de distance. L'instruction
 * (validation/lod_arbres.py §4) montre que cela NE TIENT PAS : même au
 * réglage le plus serré, une forêt de conifères réclame 2,4 fois le budget
 * mesuré. La raison est structurelle — le nombre d'arbres croît comme le
 * carré de la distance, et un seuil ne sait pas combien d'arbres il vient
 * d'admettre.
 *
 * Ici, les arbres sont triés par TAILLE APPARENTE décroissante, puis
 * servis dans cet ordre : le plus gros à l'écran reçoit le meilleur niveau
 * que le budget restant permet encore. Le budget est donc tenu par
 * construction, quelle que soit la densité de la forêt.
 *
 * ## Les plafonds
 *
 * Le budget seul donnerait un niveau plein à un arbre de dix pixels s'il
 * était seul à l'écran. Les plafonds en taille apparente l'interdisent, et
 * comme ils sont exprimés en PIXELS ils valent pour toutes les espèces —
 * de la mousse de huit centimètres au conifère de douze mètres, sans
 * aucune distance codée en dur.
 */
object TreeLodBudget {

    /**
     * Budget par défaut, en triangles. Mesuré sur Mali-G77 : le rendu au
     * sol tient 330 000 triangles en 6,6 ms, il reste 10 ms avant de
     * tomber sous 60 images par seconde, dont on garde 30 % de marge pour
     * les pics et les appareils plus lents (validation/lod_arbres.py §4).
     */
    const val DEFAULT_BUDGET_TRIANGLES = 700_000

    /** Hauteur apparente minimale, en pixels, pour prétendre à un niveau. */
    const val FULL_MIN_PX = 90f
    const val MEDIUM_MIN_PX = 35f
    const val LOW_MIN_PX = 12f

    /** Bande morte, reprise du principe des registres d'échelle (2.7-a). */
    const val HYSTERESIS = 1.15f

    /**
     * Hauteur apparente d'un objet, en pixels.
     *
     * @param heightM hauteur réelle
     * @param distanceM distance à l'œil
     * @param pxPerRadian densité angulaire de l'écran ; le HUD la connaît
     */
    fun apparentPx(heightM: Float, distanceM: Float, pxPerRadian: Float): Float {
        if (distanceM <= 0f || heightM <= 0f) return Float.MAX_VALUE
        return 2f * atan(heightM / (2f * distanceM)) * pxPerRadian
    }

    /** Distance à laquelle un objet occupe [px] pixels — l'inverse. */
    fun distanceForPx(heightM: Float, px: Float, pxPerRadian: Float): Float {
        if (px <= 0f) return Float.MAX_VALUE
        return heightM / (2f * tan(px / (2f * pxPerRadian)))
    }

    /**
     * Meilleur niveau qu'autorise la seule taille apparente, sans tenir
     * compte du budget. [previous] applique l'hystérésis : un arbre déjà
     * au niveau plein le garde jusqu'à un peu plus bas que le seuil de
     * montée, ce qui empêche le clignotement à la frontière.
     */
    fun detailForSize(apparentPx: Float, previous: TreeDetail? = null): TreeDetail {
        fun threshold(base: Float, target: TreeDetail): Float =
            if (previous != null && previous.ordinal <= target.ordinal) base / HYSTERESIS
            else base * HYSTERESIS
        return when {
            apparentPx >= threshold(FULL_MIN_PX, TreeDetail.FULL) -> TreeDetail.FULL
            apparentPx >= threshold(MEDIUM_MIN_PX, TreeDetail.MEDIUM) -> TreeDetail.MEDIUM
            apparentPx >= threshold(LOW_MIN_PX, TreeDetail.LOW) -> TreeDetail.LOW
            else -> TreeDetail.BILLBOARD
        }
    }

    /**
     * Distribue les niveaux sous budget.
     *
     * @param apparentPxSorted hauteurs apparentes, TRIÉES DÉCROISSANTES —
     *        c'est un prérequis vérifié, pas une politesse : trier ici
     *        coûterait une allocation par image, alors que l'appelant tient
     *        déjà ses arbres par distance.
     * @param triangleCost coût de chaque niveau pour l'espèce considérée
     * @return le niveau attribué à chaque arbre, dans le même ordre
     */
    fun allocate(
        apparentPxSorted: FloatArray,
        triangleCost: (TreeDetail) -> Int,
        budgetTriangles: Int = DEFAULT_BUDGET_TRIANGLES
    ): Array<TreeDetail> {
        require(budgetTriangles >= 0) { "Budget négatif : $budgetTriangles" }
        for (i in 1 until apparentPxSorted.size) {
            require(apparentPxSorted[i] <= apparentPxSorted[i - 1]) {
                "Tailles apparentes non triées à l'indice $i"
            }
        }

        val out = Array(apparentPxSorted.size) { TreeDetail.BILLBOARD }
        // Le panneau est le plancher : on paie d'abord son coût pour TOUS
        // les arbres, puis on dépense le reste à améliorer les plus gros.
        // Sans cela, un budget serré donnerait le niveau plein aux premiers
        // et plus rien du tout aux suivants — des arbres qui disparaissent.
        val floorCost = triangleCost(TreeDetail.BILLBOARD)
        var spent = floorCost.toLong() * apparentPxSorted.size
        if (spent > budgetTriangles) return out

        for (i in apparentPxSorted.indices) {
            val allowed = detailForSize(apparentPxSorted[i])
            if (allowed == TreeDetail.BILLBOARD) continue
            // On tente le meilleur niveau permis, puis on redescend.
            var chosen = TreeDetail.BILLBOARD
            for (candidate in arrayOf(TreeDetail.FULL, TreeDetail.MEDIUM, TreeDetail.LOW)) {
                if (candidate.ordinal < allowed.ordinal) continue
                val extra = triangleCost(candidate) - floorCost
                if (spent + extra <= budgetTriangles) {
                    chosen = candidate
                    spent += extra
                    break
                }
            }
            out[i] = chosen
        }
        return out
    }
}
