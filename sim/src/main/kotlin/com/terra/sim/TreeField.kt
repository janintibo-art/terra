package com.terra.sim

import com.terra.core.Vec3
import com.terra.core.Vec3d
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Champ d'arbres instancié — lot 3.5.
 *
 * ## L'architecture, et celle qui a été rejetée
 *
 * GLES2 n'a pas d'instanciation matérielle. La parade classique — des lots
 * fusionnés où chaque arbre est copié transformé — a été REJETÉE par
 * l'instruction (validation/instanciation.py) : un feuillu plein pèse
 * 0,7 Mo de sommets, et 38 arbres pleins auraient dupliqué 27 Mo de VBO,
 * plus que le cache de tuiles entier. À la place : des maillages de
 * VARIANTES partagés (2 par niveau cher, 4 pour le niveau bas), et une
 * INSTANCE par arbre — position, repère, échelle — dessinée en un appel.
 * 264 appels minuscules au pire, contre ~330 pour les tuiles : banal.
 *
 * ## Les arbres poussent là où poussaient les losanges
 *
 * Même treillis canonique de niveau 15 (43,6 m entre cases), mêmes sels de
 * hachage pour la gigue (+1, +2), la densité (+3) et la taille (+4) que
 * `TileMesh.emitOnePlant` : le champ est déterministe par graine et par
 * position, identique sur Android et sur PC. Sels nouveaux : +7 (espèce
 * via [VegetationRules]), +8 (variante de maillage).
 *
 * ## Ce que le champ ne fait pas (encore)
 *
 * Il se construit À LA DEMANDE (console `foret`) : le suivi automatique de
 * la caméra, avec hystérésis spatiale, est un lot ultérieur. Les losanges
 * des tuiles restent dessinés : au-delà du champ ils sont la végétation
 * lointaine, en deçà ils disparaissent dans les couronnes — les retirer
 * exigerait un couplage tuiles↔forêt refusé ici. L'énumération se limite à
 * la FACE du point sous l'œil : près d'une arête de cube, une partie du
 * disque n'est pas peuplée — assumé pour un outil de diagnostic, la
 * traversée de face viendra avec le suivi caméra.
 */
class TreeField(
    private val profile: TerrainProfile,
    private val sampler: CoarseSampler,
    private val planetRadiusM: Double
) {

    /** Budget du champ, en triangles. L'instruction §3 : le budget global
     *  du 3.3-b (700 k) était taillé pour des forêts denses ; sur 264
     *  arbres il achèterait du détail que personne ne voit. */
    val budgetTriangles: Int = 250_000

    /** Une instance : où, comment orientée, quelle variante. Le repère
     *  (colonnes est, haut, nord) porte DÉJÀ l'échelle de l'individu :
     *  le shader n'a pas d'uniforme d'échelle à connaître. */
    class Instance(
        val posXM: Double, val posYM: Double, val posZM: Double,
        val frame: FloatArray,
        val variant: VariantKey,
        val apparentPx: Float
    )

    /** Clef d'un maillage partagé. */
    data class VariantKey(
        val species: TreeSpecies,
        val detail: TreeDetail,
        val index: Int
    )

    class Field(
        val instances: List<Instance>,
        val trianglesSpent: Int,
        val cellsVisited: Int,
        val cellsPlanted: Int
    )

    /** Variantes par niveau : 2 pour les maillages chers, 4 pour le bas
     *  (83 ko l'unité, et la répétition s'y verrait — ils sont nombreux). */
    fun variantCount(detail: TreeDetail): Int =
        if (detail == TreeDetail.LOW) 4 else 2

    /**
     * Maillage d'une variante, dans le repère local de l'arbre (Y haut,
     * mètres). La graine dérive de l'espèce et de l'indice : deux mondes
     * différents partagent les mêmes variantes, seule la RÉPARTITION
     * change — c'est le même choix que pour les tuiles, dont le micro-
     * relief est un hachage de position, pas un tirage par monde.
     */
    fun buildVariantMesh(key: VariantKey): FloatArray {
        val seed = key.species.ordinal * 1_000L + key.index * 37L + 11L
        val tree = TreeGenerator.generate(key.species.params(), seed)
        return TreeMesh.build(tree, key.species.params(), detail = key.detail)
    }

    /**
     * Construit le champ autour du point métrique [eyePosM].
     *
     * @param pxPerRadian densité angulaire de l'écran, pour les plafonds
     * @param rangeM rayon du champ, borné à [200 ; 1500] m
     */
    fun build(eyePosM: Vec3d, pxPerRadian: Float, rangeM: Double): Field {
        val range = rangeM.coerceIn(200.0, 1_500.0)
        val eyeDir = eyePosM.normalized()
        val ground = eyeDir.toVec3()

        // Case du treillis sous l'œil, comme les losanges la définissent.
        val lat = TileMesh.PLANT_LATTICE_LEVEL
        val n = TileMesh.PLANT_LATTICE_N
        val total = (n.toLong() shl lat).toDouble()
        val (face, s, t) = CubeSphere.fromSphere(ground)
        val centerX = (s + 1f) * 0.5 * total
        val centerY = (t + 1f) * 0.5 * total

        // Portée en cases : l'arc d'une case vaut (π/2·R)/total au centre
        // de face, davantage vers les bords — la marge d'une case suffit.
        val cellArcM = (Math.PI / 2.0 * planetRadiusM) / total
        val radiusCells = ceil(range / cellArcM).toLong() + 1

        val candidates = ArrayList<Candidate>(256)
        val hint = intArrayOf(0)
        var visited = 0

        var cy = (Math.floor(centerY) - radiusCells).toLong()
        val cyEnd = (Math.floor(centerY) + radiusCells).toLong()
        val cxStart = (Math.floor(centerX) - radiusCells).toLong()
        val cxEnd = (Math.floor(centerX) + radiusCells).toLong()
        while (cy <= cyEnd) {
            var cx = cxStart
            while (cx <= cxEnd) {
                // Hors de la face : voir la limitation en tête de classe.
                if (cx < 0 || cy < 0 || cx >= total.toLong() || cy >= total.toLong()) {
                    cx++
                    continue
                }
                visited++
                collectCell(face, cx, cy, total, eyePosM, range, pxPerRadian,
                    hint, candidates)
                cx++
            }
            cy++
        }

        // Tri par taille apparente décroissante : le plus gros à l'écran
        // est servi en premier — le prérequis de toute allocation.
        candidates.sortByDescending { it.apparentPx }

        val instances = ArrayList<Instance>(candidates.size)
        var spent = 0
        for (c in candidates) {
            val allowed = TreeLodBudget.detailForSize(c.apparentPx)
            // BILLBOARD et en deçà : le losange des tuiles joue déjà ce
            // rôle, le champ n'ajoute rien.
            if (allowed.ordinal >= TreeDetail.BILLBOARD.ordinal) continue
            var chosen: TreeDetail? = null
            for (candidate in DETAIL_ORDER) {
                if (candidate.ordinal < allowed.ordinal) continue
                val cost = triangleCost(c.species, candidate)
                if (spent + cost <= budgetTriangles) {
                    chosen = candidate
                    spent += cost
                    break
                }
            }
            if (chosen == null) continue
            val variants = variantCount(chosen)
            val key = VariantKey(c.species, chosen, (c.variantU * variants).toInt()
                .coerceIn(0, variants - 1))
            instances += Instance(c.posXM, c.posYM, c.posZM, c.frame, key, c.apparentPx)
        }

        return Field(instances, spent, visited, candidates.size)
    }

    // ------------------------------------------------------------------

    private class Candidate(
        val posXM: Double, val posYM: Double, val posZM: Double,
        val frame: FloatArray,
        val species: TreeSpecies,
        val variantU: Float,
        val apparentPx: Float
    )

    private fun collectCell(
        face: Int, cellX: Long, cellY: Long, total: Double,
        eyePosM: Vec3d, rangeM: Double, pxPerRadian: Float,
        hint: IntArray, out: ArrayList<Candidate>
    ) {
        // Sels IDENTIQUES à TileMesh.emitOnePlant : la même case porte la
        // même plante, gigue et densité comprises.
        val sx = (face.toLong() * 0x9E3779B1L + cellX * 0x85EBCA77L +
            cellY * 0xC2B2AE3DL).toInt()
        val ju = profile.micro01(sx * 31 + 1)
        val jv = profile.micro01(sx * 31 + 2)
        val px = cellX + 0.15 + 0.70 * ju
        val py = cellY + 0.15 + 0.70 * jv
        val d = CubeSphere.gridDirectionF(
            face, TileMesh.PLANT_LATTICE_LEVEL,
            px.toFloat(), py.toFloat(), TileMesh.PLANT_LATTICE_N
        )

        // Exclusions des losanges : eau, lac, densité, pente.
        val alt = profile.altitudeAt(d)
        if (alt <= 0f || profile.lakeDepthAt(d) > 0f) return
        val near = sampler.nearestVertex(d, hint[0]); hint[0] = near
        val biome = sampler.biomeAt(d, near)
        val density = VegetationRules.densityFor(biome)
        if (density <= 0f) return
        if (profile.micro01(sx * 31 + 3) > density) return

        val stepRad = (2.0 / planetRadiusM).toFloat()
        val east = eastOf(d)
        val north = (d cross east)
        val aFine = profile.renderedAltitudeAt(d)
        val aE = profile.renderedAltitudeAt(Vec3(
            d.x + east.x * stepRad, d.y + east.y * stepRad, d.z + east.z * stepRad))
        val aN = profile.renderedAltitudeAt(Vec3(
            d.x + north.x * stepRad, d.y + north.y * stepRad, d.z + north.z * stepRad))
        val gx2 = (aE - aFine) / 2f
        val gy2 = (aN - aFine) / 2f
        if (gx2 * gx2 + gy2 * gy2 > 0.27f * 0.27f) return

        // L'espèce à cette position (sel +7), nulle part ailleurs tirée.
        val species = VegetationRules.speciesAt(biome, profile.micro01(sx * 31 + 7))
            ?: return

        // Échelle ±15 % par le sel de taille des losanges (+4).
        val scale = 0.85f + 0.30f * profile.micro01(sx * 31 + 4)

        // Squelette de la variante pour la hauteur : le champ mesure la
        // taille APPARENTE sur la géométrie réelle, pas sur une moyenne.
        val variantU = profile.micro01(sx * 31 + 8)
        val heightM = speciesHeight(species) * scale

        // Position métrique : terrain exact moins l'enfouissement du pied
        // (leçon v0.26.1 — le sol dessiné est la surface de tuile).
        val sink = speciesSink(species) * scale
        val r = planetRadiusM + alt.toDouble() - sink
        val posX = d.x.toDouble() * r
        val posY = d.y.toDouble() * r
        val posZ = d.z.toDouble() * r

        val dx = posX - eyePosM.x
        val dy = posY - eyePosM.y
        val dz = posZ - eyePosM.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist > rangeM) return

        val apparent = TreeLodBudget.apparentPx(heightM, dist.toFloat(), pxPerRadian)

        // Colonnes (est, haut, nord) × échelle, plus l'azimut propre : la
        // variété visuelle vient d'abord de l'orientation.
        val azimuth = profile.micro01(sx * 31 + 6) * 6.2831853f
        val ca = kotlin.math.cos(azimuth)
        val sa = kotlin.math.sin(azimuth)
        val e = Vec3(
            east.x * ca + north.x * sa, east.y * ca + north.y * sa,
            east.z * ca + north.z * sa
        )
        val nn = (d cross e)
        val frame = floatArrayOf(
            e.x * scale, e.y * scale, e.z * scale,
            d.x * scale, d.y * scale, d.z * scale,
            nn.x * scale, nn.y * scale, nn.z * scale
        )

        out += Candidate(posX, posY, posZ, frame, species, variantU, apparent)
    }

    /** Hauteurs des espèces-types (graine 1), mesurées par les tests du
     *  3.2 : suffisant pour la taille apparente, la géométrie réelle de la
     *  variante peut s'en écarter de quelques pour cent. */
    private fun speciesHeight(species: TreeSpecies): Float = when (species) {
        TreeSpecies.CONIFERE -> 11.6f
        TreeSpecies.FEUILLU -> 11.0f
        TreeSpecies.PALMIER -> 10.6f
        TreeSpecies.CACTUS -> 3.9f
        TreeSpecies.ARBUSTE -> 2.5f
        TreeSpecies.HERBACEE -> 0.62f
        TreeSpecies.MOUSSE -> 0.085f
    }

    private fun speciesSink(species: TreeSpecies): Float = when (species) {
        TreeSpecies.CONIFERE -> 0.58f
        TreeSpecies.FEUILLU -> 0.56f
        TreeSpecies.PALMIER -> 0.53f
        TreeSpecies.CACTUS -> 0.40f
        TreeSpecies.ARBUSTE -> 0.125f
        TreeSpecies.HERBACEE -> 0.031f
        TreeSpecies.MOUSSE -> 0.006f
    }

    private fun triangleCost(species: TreeSpecies, detail: TreeDetail): Int {
        // Mesurés par TreeDetailTest ; les espèces légères sont bornées par
        // leur coût plein — l'écart est sans effet sur l'allocation.
        return when (species) {
            TreeSpecies.CONIFERE -> when (detail) {
                TreeDetail.FULL -> 17_776
                TreeDetail.MEDIUM -> 10_110
                else -> 638
            }
            TreeSpecies.FEUILLU -> when (detail) {
                TreeDetail.FULL -> 6_472
                TreeDetail.MEDIUM -> 3_721
                else -> 771
            }
            TreeSpecies.ARBUSTE -> when (detail) {
                TreeDetail.FULL -> 2_152
                TreeDetail.MEDIUM -> 1_237
                else -> 255
            }
            else -> when (detail) {
                TreeDetail.FULL -> 150
                TreeDetail.MEDIUM -> 90
                else -> 15
            }
        }
    }

    private fun eastOf(d: Vec3): Vec3 {
        val e = Vec3(-d.z, 0f, d.x)
        return if (e.lengthSq < 1e-12f) Vec3(1f, 0f, 0f) else e.normalized()
    }

    private companion object {
        val DETAIL_ORDER = arrayOf(TreeDetail.FULL, TreeDetail.MEDIUM, TreeDetail.LOW)
    }
}
