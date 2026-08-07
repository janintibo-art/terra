package com.terra.sim

import com.terra.core.Seed
import com.terra.core.Vec3
import com.terra.core.clamp01
import com.terra.core.lerp
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Champ d'élévation brut, évaluable en n'importe quel point de la sphère.
 *
 * Sans unité : seul l'ordre relatif de ses valeurs compte. C'est le calibrage
 * du niveau de la mer, opération globale, qui lui donnera une échelle physique.
 */
class ElevationField(seed: Seed) {

    private val terrain = Noise(seed.derive("terrain"))
    private val warp = Noise(seed.derive("terrain/warp"))

    /**
     * Trois ingrédients :
     *  - une **déformation du domaine** qui tord l'espace avant l'échantillonnage,
     *    ce qui produit des côtes sinueuses au lieu de contours ronds ;
     *  - un **bruit basse fréquence** qui dessine les masses continentales ;
     *  - un **bruit en crêtes**, masqué par les terres, qui pose les montagnes
     *    uniquement là où il y a de la terre à soulever.
     */
    fun rawAt(x: Float, y: Float, z: Float): Float {
        val d = 0.32f
        val wx = x + warp.fbm(x * 2.6f, y * 2.6f, z * 2.6f, 3) * d
        val wy = y + warp.fbm(x * 2.6f + 31f, y * 2.6f + 17f, z * 2.6f + 7f, 3) * d
        val wz = z + warp.fbm(x * 2.6f - 19f, y * 2.6f - 43f, z * 2.6f + 23f, 3) * d

        val continent = terrain.fbm(wx * 1.45f, wy * 1.45f, wz * 1.45f, 6)
        val mountains = terrain.ridged(wx * 3.2f + 100f, wy * 3.2f, wz * 3.2f - 100f, 5)

        val landMask = clamp01((continent + 0.03f) * 3.5f)
        val coastalSoftening = lerp(0.35f, 1f, landMask)

        return continent + mountains * landMask * coastalSoftening * 1.30f
    }

    fun rawAt(p: Vec3): Float = rawAt(p.x, p.y, p.z)
}

/**
 * Profil de terrain calibré — le cœur du rendu à niveaux de détail.
 *
 * ## Le problème que cette classe résout
 *
 * Un moteur planétaire à tuiles doit pouvoir demander l'altitude en n'importe
 * quel point, à n'importe quelle finesse. Or plusieurs traitements de la Phase 1
 * sont **globaux** : le niveau de la mer se calcule par percentile sur toute la
 * planète, la continentalité par parcours de graphe. Ils ne peuvent pas
 * s'évaluer point par point.
 *
 * La solution tient en une observation : ces traitements globaux ne produisent
 * qu'une **poignée de constantes** — niveau de la mer, amplitudes, exposant de
 * relief. Une fois ces constantes établies sur la grille grossière, l'altitude
 * redevient une fonction pure de la position.
 *
 * ## La garantie qui en découle
 *
 * `TerrainProfile.altitudeAt(v)` évalué sur un sommet de l'icosphère rend
 * **exactement** la valeur stockée dans `PlanetData.altitudeM`. Le terrain fin
 * n'est pas une approximation du terrain grossier : c'est la même fonction,
 * échantillonnée plus finement. Il n'existe donc ni couture ni saut visuel entre
 * niveaux de détail — le défaut le plus pénible des moteurs planétaires, éliminé
 * par construction plutôt que corrigé après coup.
 *
 * Un test le vérifie sommet par sommet à chaque intégration continue.
 */
class TerrainProfile(
    val seed: Seed,
    val params: PlanetParams,
    val field: ElevationField,
    /** Valeur du champ brut au niveau de la mer, issue du calibrage global. */
    val seaLevel: Float,
    /** Amplitude du champ brut au-dessus du niveau de la mer. */
    val landSpan: Float,
    /** Amplitude du champ brut en dessous. */
    val seaSpan: Float,
    /** Facteur d'amplitude du relief, propre à ce monde. */
    val reliefScale: Float,
    /** Exposant de la courbe d'altitude : plus il est haut, plus les pics sont rares. */
    val peakiness: Float,
    /** Facteur de profondeur des fosses. */
    val trenchScale: Float,
    /** Relief structural par sommet de grille — socle de croûte et profils
     *  tectoniques du lot 1.6, en mètres. */
    val structuralM: FloatArray,
    /** Échantillonneur qui fait de [structuralM] une fonction continue. */
    val structuralSampler: FieldSampler,
    /** Amortissement du bruit, devenu habillage ([TectonicRelief.NOISE_DAMP]). */
    val noiseDamp: Float,
    /** Décalage du niveau de la mer, second calibrage global : percentile de
     *  la somme bruit + structure, pour que la fraction océanique demandée
     *  survive à l'addition du relief tectonique. */
    val seaOffsetM: Float,

    /**
     * Débit cumulé par cellule de grille — lot 1.10b, source de l'incision.
     *
     * Nul tant que l'hydrologie n'est pas calculée : le profil doit exister
     * AVANT elle, puisqu'elle travaille sur les altitudes qu'il produit. Le
     * générateur le renseigne ensuite par [attachFlow]. Les mondes d'essai
     * qui construisent un profil à la main gardent simplement des vallées
     * inactives.
     */
    private var flowAccum: FloatArray? = null,

    /**
     * Niveau de l'eau des lacs par sommet de grille, en mètres — lot 1.11.
     * Nul hors cuvette. Branché après l'hydrologie, comme [flowAccum].
     */
    private var lakeLevelM: FloatArray? = null,

    /**
     * Masque des lacs : 1 dans une cuvette assez profonde, 0 ailleurs.
     * Interpolé, il donne une transition douce au bord du bassin ; c'est
     * ensuite le TERRAIN FIN qui dessine le contour exact, l'eau remplissant
     * tout ce qui passe sous son niveau. Les rives sont donc détaillées sans
     * que la grille ait à l'être.
     */
    private var lakeMask: FloatArray? = null
) {

    private val detail = Noise(seed.derive("terrain/detail"))
    private val micro = Noise(seed.derive("terrain/micro"))
    private val valleys = Noise(seed.derive("terrain/valleys"))

    /**
     * Indice de départ de la recherche du sampler, par fil d'exécution : les
     * requêtes d'un même fil sont spatialement cohérentes (sommets voisins
     * d'une même tuile), la descente devient quasi constante. Un état par fil,
     * jamais partagé : le maillage parallèle des tuiles reste sans verrou, et
     * l'indice n'influe jamais sur la valeur — seulement sur le temps.
     */
    private val structuralHint = ThreadLocal.withInitial { intArrayOf(0) }

    /** Même mécanique pour l'échantillonnage du débit : un état par fil. */
    private val valleyHint = ThreadLocal.withInitial { intArrayOf(0) }

    /** Idem pour les lacs. */
    private val lakeHint = ThreadLocal.withInitial { intArrayOf(0) }

    /**
     * Altitude en mètres, négative sous le niveau de la mer.
     *
     * Depuis le lot 1.6 : bruit d'habillage amorti, plus le relief structural
     * interpolé, moins le décalage de mer. Sur un sommet de la grille, le
     * sampler rend `structuralM[v]` au bit près : la grille et cette fonction
     * ne peuvent pas diverger — l'invariant n°3 tient par construction.
     */
    fun altitudeAt(p: Vec3): Float = softLimit(
        convertRaw(
            field.rawAt(p.x, p.y, p.z),
            seaLevel, landSpan, seaSpan,
            params.maxAltitudeM, params.maxDepthM,
            reliefScale, peakiness, trenchScale
        ) * noiseDamp +
                structuralSampler.sample(structuralM, p, structuralHint.get()) -
                seaOffsetM,
        params.maxAltitudeM, params.maxDepthM
    )

    fun altitudeAt(x: Float, y: Float, z: Float): Float = altitudeAt(Vec3(x, y, z))

    /**
     * Altitude enrichie de détail haute fréquence.
     *
     * Le champ de base est lisse à l'échelle du kilomètre : sous cette taille,
     * il n'a plus rien à raconter et le terrain deviendrait plat et artificiel
     * en s'approchant. Ce détail comble ce vide.
     *
     * Trois précautions :
     *  - amplitude nulle en mer, pour ne pas bosseler la surface de l'eau ;
     *  - fondu près du rivage, pour ne pas transformer les plages en falaises ;
     *  - amplitude passée en paramètre, ce qui permet de la faire croître
     *    progressivement avec le niveau de détail et d'éviter tout ressaut
     *    visible au moment où le détail apparaît.
     *
     * @param amplitudeM amplitude crête-à-crête du détail, en mètres
     */
    fun detailedAltitudeAt(p: Vec3, amplitudeM: Float): Float {
        val base = altitudeAt(p)
        if (amplitudeM <= 0f || base <= 0f) return base

        // Fondu sur les cent premiers mètres au-dessus du rivage.
        val shoreFade = clamp01(base / 100f)
        // Le détail suit le caractère du relief local : accidenté en montagne,
        // discret en plaine.
        val ruggedness = clamp01(base / (params.maxAltitudeM * 0.35f))
        val n = detail.ridged(p.x * 260f, p.y * 260f, p.z * 260f, 4) - 0.5f
        return base + n * amplitudeM * shoreFade * lerp(0.35f, 1f, ruggedness)
    }

    /**
     * Amplitude de détail recommandée pour un niveau de tuile donné.
     *
     * Croît avec le niveau, en restant toujours petite devant le pas du maillage
     * pour ne jamais créer de géométrie plus fine que ce qui est affiché.
     */
    @Deprecated(
        "L'amplitude ne dépend plus du niveau : une seule surface (v0.10.4).",
        ReplaceWith("TerrainProfile.DETAIL_AMPLITUDE_M")
    )
    fun detailAmplitudeForLevel(@Suppress("UNUSED_PARAMETER") level: Int): Float =
        DETAIL_AMPLITUDE_M

    /**
     * Altitude telle que la surface est **rendue** au niveau de tuile donné —
     * l'unique vérité que partagent le mailleur et la collision de caméra.
     *
     * ## Une surface, pas une famille de surfaces (v0.10.4)
     *
     * Cette fonction ne prend **plus** le niveau de tuile. Elle en dépendait,
     * pour éviter de créer du relief plus fin que le maillage qui le porte —
     * une optimisation classique, mais qui donnait autant de surfaces
     * différentes que de niveaux. Conséquences vécues : la caméra, ancrée sur
     * la surface du niveau maximal, se retrouvait **enfouie** sous les tuiles
     * affichées à un niveau plus grossier (écran vidé, faces vues de dos), et
     * le sol changeait d'altitude à chaque bascule de niveau de détail.
     *
     * Il n'y a désormais qu'une surface, celle-ci, partagée par le mailleur,
     * la collision et tout le reste. Le prix est un échantillonnage grossier
     * du relief fin sur les tuiles lointaines — un moiré possible, que le
     * morphing entre niveaux (lot 2.4) traitera. La cohérence vaut ce prix :
     * une caméra ne peut plus se retrouver sous le sol qu'elle regarde.
     *
     * ## Pourquoi le micro-relief existe (v0.8.1)
     *
     * Le champ de détail le plus fin variait sur ~19 km : à hauteur d'œil, le
     * sol était mathématiquement plat, et aucun éclairage ne peut sauver un
     * plan. Ces octaves métriques — calibrées par simulation : pente moyenne
     * ~7°, longueur d'onde la plus fine 67 m pour rester à 0,6 % du bruit de
     * quantification float32 — redonnent aux facettes des orientations
     * variées ; c'est l'éclairage qui fait ensuite l'essentiel du travail
     * visuel. La montée par niveau (12 → 18) évite tout ressaut : chaque
     * cran ajoute ±0,62 m, très en deçà de la marge de 4 m des jupes.
     */
    fun renderedAltitudeAt(p: Vec3): Float {
        val base = detailedAltitudeAt(p, DETAIL_AMPLITUDE_M)
        if (base <= 0f) return base

        val fade = 1f
        // Fondu de rivage plus court que celui du détail : les plages
        // doivent onduler un peu, pas devenir des falaises.
        val shoreFade = clamp01(base / 40f)

        // fbm est CENTRÉ SUR ZÉRO (~±0,7, borne sûre ±0,9) : il s'utilise
        // tel quel. Le « −0,5 » de la première version supposait une plage
        // [0, 1] et enfonçait tout le sol proche de ~3 m — bug attrapé par le
        // test de borne en CI. ridged, lui, est bien dans [0, 1].
        val hills = micro.fbm(p.x * 26_000f, p.y * 26_000f, p.z * 26_000f, 3)
        val breaks = micro.ridged(p.x * 300_000f + 51f, p.y * 300_000f, p.z * 300_000f - 17f, 2) - 0.5f

        val surface = base + (hills * 4.4f + breaks * 1.2f) * fade * shoreFade

        // Les vallées creusent la surface, sans jamais la faire passer sous
        // le niveau de la mer : une vallée qui se remplirait d'océan serait
        // un bras de mer, pas une vallée, et déplacerait le trait de côte
        // par rapport à la grille — donc par rapport au climat et aux biomes.
        val cut = valleyDepthAt(p, surface)
        val ground = if (cut <= 0f) surface else max(1f, surface - cut)

        // Les lacs appartiennent à LA surface, pas à une couche séparée.
        // Leçon de la v0.10.4 : dès qu'il existe deux surfaces, la caméra
        // s'ancre sur l'une pendant que l'écran affiche l'autre — ici elle se
        // poserait au fond du lac. L'incision passe avant : une vallée
        // creusée peut se remplir, l'inverse n'aurait pas de sens.
        val lake = lakeSurfaceAt(p)
        return if (lake > ground) lake else ground
    }

    /**
     * Profondeur d'eau douce en ce point, en mètres ; zéro hors lac. Sert au
     * mailleur à teinter et à rendre l'eau spéculaire.
     */
    fun lakeDepthAt(p: Vec3): Float {
        val lake = lakeSurfaceAt(p)
        if (lake == NO_LAKE) return 0f
        val base = detailedAltitudeAt(p, DETAIL_AMPLITUDE_M)
        if (base <= 0f) return 0f
        val fade = 1f
        val shoreFade = clamp01(base / 40f)
        val hills = micro.fbm(p.x * 26_000f, p.y * 26_000f, p.z * 26_000f, 3)
        val breaks = micro.ridged(p.x * 300_000f + 51f, p.y * 300_000f, p.z * 300_000f - 17f, 2) - 0.5f
        val surface = base + (hills * 4.4f + breaks * 1.2f) * fade * shoreFade
        val cut = valleyDepthAt(p, surface)
        val ground = if (cut <= 0f) surface else max(1f, surface - cut)
        return max(0f, lake - ground)
    }

    /**
     * Branche le débit de l'hydrologie, après coup.
     *
     * ## L'ordre, et pourquoi il est ainsi
     *
     * L'hydrologie a besoin des altitudes, qui viennent du profil ; le profil
     * a besoin du débit pour creuser ses vallées. Le nœud se dénoue en
     * remarquant que l'incision est un ENJOLIVEMENT du terrain rendu, pas une
     * entrée de la simulation : la grille, le climat et l'érosion travaillent
     * tous sur `altitudeAt`, que l'incision ne touche pas. On calcule donc
     * l'hydrologie sur le terrain nu, puis on branche son débit pour le seul
     * rendu — sans boucle, et sans que la grille et la fonction divergent.
     */
    fun attachFlow(accum: FloatArray) {
        flowAccum = accum
    }

    /**
     * Branche les lacs, calculés depuis les cuvettes comblées de
     * l'hydrologie — lot 1.11.
     *
     * @param levels niveau de l'eau par sommet (roche + comblement)
     * @param mask 1 là où la cuvette dépasse [LAKE_MIN_DEPTH_M], 0 ailleurs
     */
    fun attachLakes(levels: FloatArray, mask: FloatArray) {
        lakeLevelM = levels
        lakeMask = mask
    }

    /**
     * Niveau de la surface d'un lac en ce point, ou [NO_LAKE] s'il n'y en a
     * pas. Le contour du lac n'est PAS décidé ici : c'est
     * [renderedAltitudeAt] qui compare ce niveau au terrain, si bien qu'un
     * promontoire qui dépasse reste sec et qu'une anse se remplit.
     */
    fun lakeSurfaceAt(p: Vec3): Float {
        val mask = lakeMask ?: return NO_LAKE
        val levels = lakeLevelM ?: return NO_LAKE
        // Le masque d'abord : hors bassin, on s'arrête sans second
        // échantillonnage. La plupart des points du globe sortent ici.
        if (structuralSampler.sample(mask, p, lakeHint.get()) < 0.5f) return NO_LAKE
        return structuralSampler.sample(levels, p, lakeHint.get())
    }

    /**
     * Creusement des vallées en un point, en mètres (positif = à retrancher).
     *
     * ## Ce que ce champ combine
     *
     * Le débit de l'hydrologie dit **où** l'eau se concentre, mais à 115 km
     * de résolution — mille fois trop grossier pour une vallée. Un bruit en
     * crêtes fournit donc le **tracé** fin, naturellement ramifié, et le
     * débit en règle la **profondeur**. Une vallée existe donc là où le bruit
     * dessine une ligne d'écoulement plausible ET où la simulation dit que
     * l'eau passe.
     *
     * ## Calibrage, mesuré avant écriture
     *
     * Longueur d'onde 40 km et seuil 0,35 donnent des vallées de ~1,5 km de
     * large occupant 7 % du sol ; le creusement va de 15 m pour un affluent à
     * 68 m pour un fleuve, avec des versants à 4° au plus — lisibles en
     * descente, jamais des canyons.
     *
     * ## Borné par construction
     *
     * Les deux facteurs vivent dans [0, 1] : le creusement ne peut pas
     * dépasser [VALLEY_DEPTH_MAX_M], quoi qu'il arrive. Leçon des lots
     * précédents, où le calibrage seul avait échoué trois fois.
     */
    fun valleyDepthAt(p: Vec3, altitudeM: Float): Float {
        val accum = flowAccum ?: return 0f
        if (altitudeM <= VALLEY_MIN_ALTITUDE_M) return 0f

        // Tracé : le bruit en crêtes, seuillé, donne des lignes ramifiées.
        val ridge = valleys.ridged(
            p.x * VALLEY_FREQ, p.y * VALLEY_FREQ + 19f, p.z * VALLEY_FREQ - 7f, 4
        )
        val line = (ridge - VALLEY_THRESHOLD) / (1f - VALLEY_THRESHOLD)
        if (line <= 0f) return 0f

        // Débit : racine, normalisée sur un fleuve de référence.
        val flow = structuralSampler.sample(accum, p, valleyHint.get())
        val strength = clamp01(sqrtApprox(flow / VALLEY_FLOW_REFERENCE))

        // Fondus d'altitude : pas de vallée sur les plaines littorales déjà
        // plates, ni sur les hautes crêtes, qui sont glaciaires et non
        // fluviales.
        val lowFade = clamp01((altitudeM - VALLEY_MIN_ALTITUDE_M) / 35f)
        val highFade = 1f - clamp01((altitudeM - 2_500f) / 1_200f)

        return VALLEY_DEPTH_MAX_M * clamp01(line) * strength * lowFade * highFade
    }

    /**
     * Moucheture de teinte du sol : facteur autour de 1, par plages d'environ
     * 450 m. Casse l'aplat de couleur d'un biome uniforme sans introduire de
     * données nouvelles — c'est du même monde, vu de près.
     */
    fun colorJitterAt(p: Vec3): Float {
        val n = micro.fbm(p.x * 90_000f - 7f, p.y * 90_000f + 3f, p.z * 90_000f, 2)
        // Le clamp rend la borne vraie par construction, pas par supposition
        // sur la queue de distribution du bruit.
        return (1f + n * 0.13f).coerceIn(0.88f, 1.12f)
    }

    companion object {

        /**
         * Amplitude du détail haute fréquence, en mètres. Unique, désormais :
         * voir [renderedAltitudeAt]. Valeur de l'ancien niveau maximal, pour
         * que le relief fin reste aussi marqué qu'avant de près.
         */
        const val DETAIL_AMPLITUDE_M = 52f

        /**
         * Comblement minimal pour qu'une cuvette devienne un lac, en mètres.
         *
         * Calibré : à ce seuil, les lacs occupent 1 à 2 % des terres, l'ordre
         * de grandeur terrestre. Plus bas, chaque irrégularité du relief
         * devient une flaque ; plus haut, il ne reste que des mers intérieures.
         */
        const val LAKE_MIN_DEPTH_M = 30f

        /** Valeur rendue par [lakeSurfaceAt] en l'absence de lac. */
        const val NO_LAKE = -1e9f

        /** Creusement maximal d'une vallée, en mètres. Borne stricte. */
        const val VALLEY_DEPTH_MAX_M = 140f

        /** Fréquence du réseau : longueur d'onde ~40 km sur la sphère unité. */
        const val VALLEY_FREQ = 1_000f

        /** Seuil du bruit en crêtes : règle la largeur (~1,5 km) et la
         *  couverture (~7 % du sol). */
        const val VALLEY_THRESHOLD = 0.35f

        /** Débit d'un fleuve de référence, en cellules drainées. */
        const val VALLEY_FLOW_REFERENCE = 400f

        /** Pas de vallée sous cette altitude : les plaines littorales sont
         *  déjà plates, et creuser près du rivage ferait entrer la mer. */
        const val VALLEY_MIN_ALTITUDE_M = 25f

        /** Racine carrée sans dépendance à java.lang.Math. */
        private fun sqrtApprox(x: Float): Float =
            if (x <= 0f) 0f else kotlin.math.sqrt(x)

        /**
         * Conversion du champ de bruit brut en mètres — la formule unique que
         * partagent le générateur (grille) et [altitudeAt] (fonction). Vivre
         * dans le companion la rend appelable AVANT la construction du profil,
         * dont deux paramètres ([seaOffsetM]) dépendent d'elle : le second
         * calibrage a besoin de la somme bruit + structure sur toute la grille.
         */
        fun convertRaw(
            raw: Float,
            seaLevel: Float, landSpan: Float, seaSpan: Float,
            maxAltitudeM: Float, maxDepthM: Float,
            reliefScale: Float, peakiness: Float, trenchScale: Float
        ): Float =
            if (raw >= seaLevel) {
                val t = (raw - seaLevel) / landSpan
                (if (t <= 0f) 0f else exp(peakiness * ln(t))) * maxAltitudeM * reliefScale
            } else {
                val t = (seaLevel - raw) / seaSpan
                -t * maxDepthM * trenchScale
            }

        /**
         * Compression douce des extrêmes du relief — v0.9.2.
         *
         * Au-delà de 70 % de la borne, l'altitude est comprimée vers une
         * asymptote qui EST la borne : `maxAltitudeM` et `maxDepthM` ne
         * peuvent plus être dépassés, par construction — les tests exigent
         * ces bornes au mètre près, et les garantir par calibrage des
         * superpositions était un pari perdu d'avance (quatre tests rouges
         * l'ont prouvé). Le genou est C¹ : dérivée 1 au raccord, aucune
         * cassure dans le relief. Monotone, donc le percentile du niveau de
         * la mer traverse la compression sans se déplacer.
         */
        fun softLimit(a: Float, maxAltitudeM: Float, maxDepthM: Float): Float {
            if (a > 0.70f * maxAltitudeM) {
                val k0 = 0.70f * maxAltitudeM
                val span = maxAltitudeM - k0
                val t = (a - k0) / span
                return k0 + span * t / (1f + t)
            }
            if (a < -0.70f * maxDepthM) {
                val k0 = 0.70f * maxDepthM
                val span = maxDepthM - k0
                val t = (-a - k0) / span
                return -(k0 + span * t / (1f + t))
            }
            return a
        }

        /**
         * Amplitude crête-à-crête maximale du micro-relief, pour dimensionner
         * la sphère englobante du lancer de rayon et la borne du test.
         *
         * Calculée avec la borne **sûre** du Perlin (±0,9), pas sa plage
         * typique (±0,7) : 2 × (0,9 × 4,4 + 0,5 × 1,2) = 9,12, arrondi au-
         * dessus. Une borne de collision se dimensionne au pire cas garanti.
         */
        const val MICRO_TOTAL_AMPLITUDE_M = 9.2f
    }
}
