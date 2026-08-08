package com.terra.sim

import com.terra.core.Seed
import com.terra.core.Vec3

/**
 * Le monde généré, sous forme de données pures.
 *
 * Cette classe ne connaît ni OpenGL, ni Android, ni le moindre pixel. Le moteur
 * de rendu la lit pour construire ses tampons ; les systèmes de simulation la
 * liront pour placer des plantes, des créatures et des tribus. Cette séparation
 * est ce qui rend la génération testable automatiquement en intégration
 * continue.
 *
 * Toutes les grandeurs sont dans des unités physiques réelles.
 */
class PlanetData(
    /** Nom du monde : c'est lui qui fait office d'identité et de graine. */
    val name: String,
    val seed: Seed,
    val sphere: Icosphere,
    val params: PlanetParams,

    /** Altitude par sommet, en mètres. Négative sous le niveau de la mer. */
    val altitudeM: FloatArray,

    /** Température moyenne annuelle par sommet, en degrés Celsius. */
    val temperatureC: FloatArray,

    /** Précipitations annuelles par sommet, en millimètres. */
    val precipMm: FloatArray,

    /**
     * Continentalité [0, 1] : éloignement climatique de l'océan, déjà
     * calculée pour le climat et conservée depuis le lot 1.12 — les
     * saisons ([SeasonalClimate]) en ont besoin à l'évaluation. Zéro en
     * mer, par construction. N'entre PAS dans l'empreinte : le champ est
     * dérivé du relief, sans aléa propre.
     */
    val continentality: FloatArray,

    /** Biome par sommet, stocké par ordinal pour la compacité. */
    val biomeId: ByteArray,

    /** Statistiques de génération, affichées dans le HUD de debug. */
    val stats: GenerationStats,

    /**
     * Terrain sous forme de fonction, évaluable en tout point et à toute
     * résolution. C'est lui que consultera le rendu à tuiles ; il rend
     * exactement [altitudeM] sur les sommets de la grille.
     */
    val terrain: TerrainProfile,

    /**
     * Plaques tectoniques — lot 1.4. Purement descriptives pour l'instant :
     * le relief n'en dérivera qu'au lot 1.6. Flux de graine indépendant, donc
     * aucune influence sur les champs ci-dessus ni sur l'empreinte.
     */
    val plates: PlateSet,

    /** Frontières de plaques classées — lot 1.5. Même statut que [plates]. */
    val boundaries: BoundarySet,

    /** Distances aux frontières par type — lot 1.8, prérequis du relief 1.6. */
    val boundaryDistance: BoundaryDistanceField,

    /**
     * Couleur du biome de chaque sommet, séparée en trois canaux — lot 1.12.
     *
     * Sous cette forme, elle s'interpole comme n'importe quel champ : le
     * mailleur mélange les teintes des trois sommets du triangle au lieu de
     * copier celle du plus proche, et les frontières de biomes cessent
     * d'apparaître comme les polygones de la grille.
     */
    val biomeColorR: FloatArray,
    val biomeColorG: FloatArray,
    val biomeColorB: FloatArray,

    /** Points chauds et chaînes volcaniques — lot 1.7. */
    val hotspots: HotspotField,

    /** Érosion et réseau d'écoulement — lot 1.9. Le débit qu'il porte
     *  guidera les rivières (1.10), les lacs (1.11) et l'incision fine. */
    val hydrology: HydrologyField
) {

    /**
     * Analyse géographique, calculée à la demande car elle exige la
     * construction de la liste d'adjacence.
     */
    val geography: Geography by lazy { Geography.analyze(this) }

    /**
     * Empreinte du monde — lot de consolidation.
     *
     * Condense l'ensemble des champs générés en un seul entier 64 bits. Sert de
     * test de non-régression : si la graine « Gaia » ne produit plus la même
     * empreinte qu'hier, c'est que l'algorithme a dérivé, volontairement ou non.
     *
     * Les valeurs sont quantifiées avant hachage pour tolérer les écarts
     * d'arrondi entre processeurs sans masquer une vraie différence.
     */
    val fingerprint: Long by lazy {
        var h = -3750763034362895579L   // FNV-1a 64 bits
        fun feed(v: Long) {
            h = h xor v
            h *= 1099511628211L
        }
        for (i in 0 until vertexCount) {
            feed(Math.round(altitudeM[i]).toLong())
            feed(Math.round(temperatureC[i] * 100f).toLong())
            feed(Math.round(precipMm[i]).toLong())
            feed(biomeId[i].toLong())
        }
        h
    }

    /** Empreinte en hexadécimal, format compact pour le HUD et les rapports. */
    fun fingerprintHex(): String = java.lang.Long.toHexString(fingerprint).padStart(16, '0')

    val vertexCount: Int get() = sphere.vertexCount
    val faceCount: Int get() = sphere.faceCount

    fun position(index: Int): Vec3 = sphere.vertices[index]

    fun biome(index: Int): Biome = BIOMES[biomeId[index].toInt()]

    fun isLand(index: Int): Boolean = altitudeM[index] >= 0f

    /**
     * Rayon de rendu d'un sommet, en unités de planète (1.0 = niveau de la mer).
     *
     * Le relief est volontairement exagéré : à l'échelle réelle, l'Everest
     * représente 0,14 % du rayon terrestre et serait rigoureusement invisible.
     * Tous les globes en relief du monde trichent de la même façon.
     */
    fun renderRadius(index: Int): Float {
        val a = altitudeM[index]
        if (a <= 0f) return 1f
        return 1f + (a / params.maxAltitudeM) * params.reliefExaggeration
    }

    companion object {
        private val BIOMES = Biome.values()
    }
}

/**
 * Paramètres de génération. Exposés en un seul endroit pour que l'éditeur en
 * jeu du lot 1.18 puisse tous les manipuler sans toucher au code.
 */
data class PlanetParams(
    /** Rayon planétaire en mètres. Terre : 6 371 000. */
    val radiusM: Float = 6_371_000f,

    /** Proportion de la surface couverte par les océans. Terre : 0,71. */
    val oceanFraction: Float = 0.66f,

    /** Altitude du point le plus haut, en mètres. */
    val maxAltitudeM: Float = 7_000f,

    /** Profondeur du point le plus bas, en mètres. */
    val maxDepthM: Float = 6_500f,

    /** Facteur d'exagération du relief au rendu. */
    val reliefExaggeration: Float = 0.055f,

    /** Inclinaison de l'axe de rotation, en degrés. Terre : 23,44. */
    val axialTiltDeg: Float = 23.4f,

    /** Température moyenne à l'équateur au niveau de la mer, en °C. Terre : 26. */
    val equatorTempC: Float = 27f,

    /**
     * Écart de température entre l'équateur et les pôles, en °C.
     * Terre : environ 46 (de +26 à −20 en moyenne annuelle).
     */
    val poleTempDropC: Float = 47f,

    /**
     * Atténuation du gradient thermique au-dessus des océans, qui restituent en
     * hiver la chaleur accumulée en été. 1 = aucune inertie, 0,8 = forte.
     */
    val oceanThermalInertia: Float = 0.88f,

    /**
     * Refroidissement supplémentaire au cœur des continents, en °C, appliqué
     * proportionnellement à la latitude. Terre : environ 12 (Iakoutsk contre
     * une côte norvégienne à latitude égale).
     */
    val continentalityC: Float = 12f,

    /** Refroidissement avec l'altitude, en °C par kilomètre. Terre : 6,5. */
    val lapseRateCPerKm: Float = 6.5f,

    /** Précipitations maximales, en millimètres par an. */
    val maxPrecipMm: Float = 3_600f,

    /** Niveau de subdivision de l'icosphère. */
    val subdivisions: Int = 5,

    /**
     * Activité tectonique — lot 1.18 b. Multiplie l'amplitude de tout le
     * relief d'origine tectonique : chaînes, cordillères, fosses, arcs,
     * dorsales, rifts et édifices de points chauds. Le socle isostatique
     * n'en dépend pas (c'est de la flottaison, pas de l'orogenèse), le
     * bruit d'habillage non plus.
     *
     * À 1,0 — la valeur d'usine — la multiplication par 1,0f est EXACTE
     * en arithmétique IEEE : les mondes existants ne changent pas d'un
     * bit, ce qui autorise à ne pas incrémenter GENERATION_VERSION. Un
     * test le verrouille. Placé en dernier pour ne décaler aucun appel.
     */
    val tectonicActivity: Float = 1f
)

/** Mesures relevées pendant la génération, pour le HUD et les tests. */
data class GenerationStats(
    val generationMs: Long,
    val oceanFractionActual: Float,
    val highestAltitudeM: Float,
    val deepestDepthM: Float,
    val coldestC: Float,
    val hottestC: Float,
    val biomeCounts: Map<Biome, Int>
) {
    val distinctBiomes: Int get() = biomeCounts.count { it.value > 0 }
}
