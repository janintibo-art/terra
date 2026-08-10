package com.terra.sim

/**
 * Familles d'espèces végétales — lot 3.2.
 *
 * Chaque famille est un jeu de paramètres de la grammaire du lot 3.1,
 * étendue ici aux verticilles latéraux : sans eux, aucun paramétrage ne
 * produit un conifère, dont les branches naissent le long du fût et non
 * à sa seule cime.
 *
 * ## D'où viennent ces chiffres
 *
 * De `validation/especes.py`, qui rejoue la grammaire en Python et mesure
 * les silhouettes sur 400 tirages par famille. Deux paramétrages ont été
 * essayés : le premier confondait conifère et feuillu. Le script a aussi
 * écarté un critère qui semblait évident — la conicité (envergure haute
 * sur envergure basse) ne sépare PAS les deux familles, le fût nu plaçant
 * la coupure au milieu du feuillage. C'est l'ÉLANCEMENT (envergure sur
 * hauteur) qui sépare : 0,30…0,33 pour le conifère contre 0,46…0,59 pour
 * le feuillu. Les tests reprennent ces bornes mesurées.
 *
 * Ces valeurs décrivent des individus ADULTES. La croissance, la variation
 * par individu et la coloration saisonnière viendront au lot 3.4 ; la
 * répartition par biome au lot 3.6.
 */
enum class TreeSpecies(val label: String) {

    /** Sapin : fût dominant, verticilles étagés, silhouette serrée. */
    CONIFERE("conifère"),

    /** Feuillu générique : ramification à la cime, couronne large. */
    FEUILLU("feuillu"),

    /** Palmier : fût nu, couronne de palmes au sommet, aucun latéral. */
    PALMIER("palmier"),

    /** Cactus colonnaire : bras peu nombreux, redressés, presque pas de
     *  conicité (radiusRatio proche de 1 : une colonne, pas un cône). */
    CACTUS("cactus"),

    /** Arbuste : tronc court, ramification dense et ouverte. */
    ARBUSTE("arbuste"),

    /** Herbacée : une touffe de tiges depuis une base courte. */
    HERBACEE("herbacée"),

    /** Mousse : quelques centimètres, pour le décor au ras du sol. */
    MOUSSE("mousse");

    fun params(): TreeParams = when (this) {
        CONIFERE -> TreeParams(
            trunkLengthM = 8.0f, trunkRadiusM = 0.26f,
            lengthRatio = 0.34f, radiusRatio = 0.58f,
            branchAngleRad = 1.20f, angleJitterRad = 0.10f,
            children = 1, maxDepth = 3, straightness = 0.05f,
            lateralWhorls = 3, lateralPerWhorl = 3, attachStartFraction = 0.22f
        )
        FEUILLU -> TreeParams(
            trunkLengthM = 4.0f, trunkRadiusM = 0.28f,
            lengthRatio = 0.68f, radiusRatio = 0.60f,
            branchAngleRad = 0.78f, angleJitterRad = 0.16f,
            children = 3, maxDepth = 5, straightness = 0.14f
        )
        PALMIER -> TreeParams(
            trunkLengthM = 7.0f, trunkRadiusM = 0.16f,
            lengthRatio = 0.55f, radiusRatio = 0.80f,
            branchAngleRad = 1.25f, angleJitterRad = 0.22f,
            children = 8, maxDepth = 1, straightness = 0.0f
        )
        CACTUS -> TreeParams(
            trunkLengthM = 1.8f, trunkRadiusM = 0.20f,
            lengthRatio = 0.70f, radiusRatio = 0.88f,
            branchAngleRad = 1.05f, angleJitterRad = 0.10f,
            children = 2, maxDepth = 2, straightness = 0.75f
        )
        ARBUSTE -> TreeParams(
            trunkLengthM = 0.9f, trunkRadiusM = 0.06f,
            lengthRatio = 0.72f, radiusRatio = 0.66f,
            branchAngleRad = 0.85f, angleJitterRad = 0.25f,
            children = 3, maxDepth = 4, straightness = 0.10f
        )
        HERBACEE -> TreeParams(
            trunkLengthM = 0.35f, trunkRadiusM = 0.010f,
            lengthRatio = 0.80f, radiusRatio = 0.70f,
            branchAngleRad = 0.55f, angleJitterRad = 0.30f,
            children = 4, maxDepth = 1, straightness = 0.35f
        )
        MOUSSE -> TreeParams(
            trunkLengthM = 0.05f, trunkRadiusM = 0.003f,
            lengthRatio = 0.75f, radiusRatio = 0.75f,
            branchAngleRad = 0.80f, angleJitterRad = 0.35f,
            children = 3, maxDepth = 1, straightness = 0.05f
        )
    }

    companion object {
        /** Reconnaissance souple pour la console : accents et pluriels
         *  optionnels, préfixes acceptés dès qu'ils sont sans ambiguïté. */
        fun parse(text: String): TreeSpecies? {
            val t = text.lowercase()
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .trimEnd('s')
            if (t.isEmpty()) return null
            val all = values()
            val exact = all.firstOrNull {
                it.name.lowercase() == t || it.label
                    .replace('é', 'e').replace('è', 'e') == t
            }
            if (exact != null) return exact
            val prefixed = all.filter { it.name.lowercase().startsWith(t) }
            return if (prefixed.size == 1) prefixed[0] else null
        }
    }
}
