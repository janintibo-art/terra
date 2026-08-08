package com.terra.sim

import kotlin.math.roundToInt

/**
 * Registre des paramètres éditables en jeu — lot 1.18.
 *
 * Tout le contenu de l'éditeur vit ici, en Kotlin pur : bornes, pas,
 * libellés, lecture et écriture dans [PlanetParams]. La couche Android ne
 * fait que dessiner des curseurs à partir de cette liste — ajouter un
 * paramètre à l'éditeur, c'est ajouter une ligne ici, et elle est aussitôt
 * couverte par les tests génériques de ParamEditorTest.
 *
 * Deux absents délibérés :
 *
 *  - `radiusM` : le budget du quadtree (seuil 1,4, niveau max 23) et les
 *    registres de la caméra ont été calibrés pour 6 371 km. Un rayon
 *    divisé par dix sans recalibrage donnerait des tuiles incohérentes ;
 *    à traiter comme un lot propre si les mini-planètes tentent un jour.
 *  - l'activité tectonique : il n'existe pas encore de paramètre — le
 *    nombre de plaques est tiré dans [Tectonics]. En créer un change la
 *    génération, donc GENERATION_VERSION et les empreintes ; ce sera le
 *    lot 1.18 b, séparé exprès pour que celui-ci laisse les mondes
 *    existants intacts au bit près.
 */
object ParamEditor {

    /**
     * Un paramètre éditable. La valeur affichée est toujours
     * `min + index·step` avec `index` entier : c'est ce qui garantit que
     * deux appareils qui affichent la même position de curseur écrivent
     * exactement le même Float, donc génèrent le même monde.
     */
    data class Spec(
        val id: String,
        val label: String,
        val unit: String,
        val min: Float,
        val max: Float,
        val step: Float,
        val decimals: Int,
        /**
         * Vrai si le paramètre entre dans la génération du monde ; faux
         * s'il n'affecte que le rendu ou le ciel (exagération du relief,
         * inclinaison axiale — les saisons, pas le climat moyen annuel).
         * Sert à l'interface pour dire si « Régénérer » est nécessaire,
         * et aux tests pour savoir quelles empreintes doivent bouger.
         */
        val affectsGeneration: Boolean,
        val read: (PlanetParams) -> Float,
        val write: (PlanetParams, Float) -> PlanetParams
    ) {
        /** Nombre de positions du curseur (index de 0 à steps inclus). */
        val steps: Int get() = ((max - min) / step).roundToInt()

        fun valueAt(index: Int): Float =
            min + index.coerceIn(0, steps) * step

        fun indexOf(value: Float): Int =
            ((value - min) / step).roundToInt().coerceIn(0, steps)

        /** Ramène une valeur quelconque sur la grille min + k·step. */
        fun clamp(value: Float): Float = valueAt(indexOf(value))

        /**
         * Locale forcée : sur un appareil anglophone, `%f` mettrait un
         * point et le remplacement le changerait en virgule ; sur un
         * appareil francophone, `%f` mettrait déjà la virgule et le
         * remplacement ne ferait rien — même résultat, mais par deux
         * chemins différents. ROOT rend le chemin unique et testable.
         */
        fun format(value: Float): String =
            "%.${decimals}f".format(java.util.Locale.ROOT, value)
                .replace('.', ',') +
                if (unit.isEmpty()) "" else " $unit"
    }

    /*
     * PIÈGE DU PAS FLOTTANT — pourquoi l'interface ne doit écrire que les
     * curseurs réellement déplacés : la grille `min + k·step` ne retombe
     * pas toujours bit à bit sur la valeur d'usine (23,4° devient
     * 23,400002 après aller-retour par l'index). Si l'éditeur réécrivait
     * TOUS les paramètres à la fermeture, ouvrir le panneau puis
     * régénérer sans rien toucher suffirait à changer le monde. Un test
     * vérifie seulement que l'écart d'affichage reste sous un demi-pas ;
     * l'exactitude au bit est garantie en ne réécrivant que ce qui a
     * bougé.
     */

    val specs: List<Spec> = listOf(
        Spec("ocean", "Fraction océanique", "", 0.30f, 0.90f, 0.01f, 2, true,
            { it.oceanFraction }, { p, v -> p.copy(oceanFraction = v) }),
        Spec("maxAlt", "Altitude maximale", "m", 2_000f, 12_000f, 250f, 0, true,
            { it.maxAltitudeM }, { p, v -> p.copy(maxAltitudeM = v) }),
        Spec("maxDepth", "Profondeur maximale", "m", 2_000f, 11_000f, 250f, 0, true,
            { it.maxDepthM }, { p, v -> p.copy(maxDepthM = v) }),
        Spec("equator", "Température équatoriale", "°C", 10f, 45f, 1f, 0, true,
            { it.equatorTempC }, { p, v -> p.copy(equatorTempC = v) }),
        Spec("poleDrop", "Chute équateur → pôle", "°C", 20f, 80f, 1f, 0, true,
            { it.poleTempDropC }, { p, v -> p.copy(poleTempDropC = v) }),
        Spec("inertia", "Inertie thermique océanique", "", 0.70f, 1.00f, 0.01f, 2, true,
            { it.oceanThermalInertia }, { p, v -> p.copy(oceanThermalInertia = v) }),
        Spec("contin", "Continentalité", "°C", 0f, 25f, 1f, 0, true,
            { it.continentalityC }, { p, v -> p.copy(continentalityC = v) }),
        Spec("lapse", "Gradient vertical", "°C/km", 3f, 10f, 0.25f, 2, true,
            { it.lapseRateCPerKm }, { p, v -> p.copy(lapseRateCPerKm = v) }),
        Spec("precip", "Précipitations maximales", "mm/an", 1_000f, 8_000f, 100f, 0, true,
            { it.maxPrecipMm }, { p, v -> p.copy(maxPrecipMm = v) }),
        Spec("tilt", "Inclinaison axiale", "°", 0f, 45f, 0.1f, 1, false,
            { it.axialTiltDeg }, { p, v -> p.copy(axialTiltDeg = v) }),
        Spec("relief", "Exagération du relief", "", 0f, 0.15f, 0.005f, 3, false,
            { it.reliefExaggeration }, { p, v -> p.copy(reliefExaggeration = v) }),
        Spec("subdiv", "Subdivisions de la grille", "", 4f, 6f, 1f, 0, true,
            { it.subdivisions.toFloat() },
            { p, v -> p.copy(subdivisions = v.roundToInt()) })
    )

    fun byId(id: String): Spec = specs.first { it.id == id }

    /** Vrai si un paramètre de génération diffère entre les deux jeux. */
    fun generationDiffers(a: PlanetParams, b: PlanetParams): Boolean =
        specs.any { it.affectsGeneration && it.read(a) != it.read(b) }
}
