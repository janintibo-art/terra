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
    val trenchScale: Float
) {

    private val detail = Noise(seed.derive("terrain/detail"))
    private val micro = Noise(seed.derive("terrain/micro"))

    /** Puissance à exposant réel, sans dépendre de java.lang.Math. */
    private fun pow(base: Float, exponent: Float): Float =
        if (base <= 0f) 0f else exp(exponent * ln(base))

    /** Altitude en mètres, négative sous le niveau de la mer. */
    fun altitudeAt(x: Float, y: Float, z: Float): Float =
        altitudeFromRaw(field.rawAt(x, y, z))

    fun altitudeAt(p: Vec3): Float = altitudeAt(p.x, p.y, p.z)

    /** Conversion du champ brut en altitude physique. */
    fun altitudeFromRaw(raw: Float): Float =
        if (raw >= seaLevel) {
            val t = (raw - seaLevel) / landSpan
            pow(t, peakiness) * params.maxAltitudeM * reliefScale
        } else {
            val t = (seaLevel - raw) / seaSpan
            -t * params.maxDepthM * trenchScale
        }

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
    fun detailAmplitudeForLevel(level: Int): Float {
        if (level < 10) return 0f
        val steps = (level - 10).coerceAtMost(13)
        return max(0f, 4f * steps)
    }

    /**
     * Altitude telle que la surface est **rendue** au niveau de tuile donné —
     * l'unique vérité que partagent le mailleur et la collision de caméra.
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
    fun renderedAltitudeAt(p: Vec3, level: Int): Float {
        val base = detailedAltitudeAt(p, detailAmplitudeForLevel(level))
        if (level < 12 || base <= 0f) return base

        val fade = clamp01((level - 12) / 6f)
        // Fondu de rivage plus court que celui du détail : les plages
        // doivent onduler un peu, pas devenir des falaises.
        val shoreFade = clamp01(base / 40f)

        // fbm est CENTRÉ SUR ZÉRO (~±0,7, borne sûre ±0,9) : il s'utilise
        // tel quel. Le « −0,5 » de la première version supposait une plage
        // [0, 1] et enfonçait tout le sol proche de ~3 m — bug attrapé par le
        // test de borne en CI. ridged, lui, est bien dans [0, 1].
        val hills = micro.fbm(p.x * 26_000f, p.y * 26_000f, p.z * 26_000f, 3)
        val breaks = micro.ridged(p.x * 300_000f + 51f, p.y * 300_000f, p.z * 300_000f - 17f, 2) - 0.5f

        return base + (hills * 4.4f + breaks * 1.2f) * fade * shoreFade
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
