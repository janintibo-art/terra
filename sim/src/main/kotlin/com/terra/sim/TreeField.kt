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

    /** Budget du champ, en triangles. 500 k depuis la v0.52.1 : les 250 k
     *  initiaux, taillés pour l'allocation gloutonne, bridaient la
     *  nouvelle allocation progressive à ~44 arbres réels sur 264. La
     *  marge GPU mesurée (Mali-G77, validation/lod_arbres.py §4) autorise
     *  ~700 k pour toute la végétation — 500 k pour le champ en laisse aux
     *  losanges lointains et au reste. */
    val budgetTriangles: Int = 500_000

    /** Une instance : où, comment orientée, quelle variante. Le repère
     *  (colonnes est, haut, nord) porte DÉJÀ l'échelle de l'individu :
     *  le shader n'a pas d'uniforme d'échelle à connaître.
     *
     *  Les six derniers champs (lot 3.4) sont les INTRANTS de la couleur,
     *  pas la couleur elle-même : celle-ci se recalcule à chaque image par
     *  [FoliageTint.of]. Figer la couleur ici la gèlerait jusqu'à la
     *  prochaine reconstruction du champ, qui n'a lieu qu'après 35 % de
     *  déplacement de la caméra — un observateur immobile ne verrait
     *  jamais l'automne arriver. */
    class Instance(
        val posXM: Double, val posYM: Double, val posZM: Double,
        val frame: FloatArray,
        val variant: VariantKey,
        val apparentPx: Float,
        val annualTempC: Float,
        val precipMm: Float,
        val sinLat: Float,
        val continentality01: Float,
        val saltPhase: Float,
        val saltHue: Float,
        val saltShade: Float
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
        val cellsPlanted: Int,
        /** Clefs [PlantExclusion.key] des cases portant un vrai arbre. */
        val occupiedCells: Set<Long>
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

        // Tuiles RÉELLEMENT dessinées pour cette position d'œil : le même
        // sélecteur, le même seuil — c'est lui qui dit sur quelle surface
        // interpolée chaque arbre doit se poser (v0.51.2 : les arbres
        // posés sur le terrain exact FLOTTAIENT, l'écart tuile/exact
        // atteignant ~3 m à 400 m où la tuile est de niveau 15).
        val drawn = ArrayList<TileId>(512)
        TileSelector().select(
            eyePosM.x / planetRadiusM, eyePosM.y / planetRadiusM,
            eyePosM.z / planetRadiusM, drawn
        )
        val drawnSet = HashSet<Long>(drawn.size * 2)
        for (tile in drawn) drawnSet.add(tile.packed())

        val total = (TileMesh.PLANT_LATTICE_N.toLong() shl
            TileMesh.PLANT_LATTICE_LEVEL).toDouble()

        val candidates = ArrayList<Candidate>(256)
        val hint = intArrayOf(0)
        var visited = 0

        // Énumération par ÉCHANTILLONNAGE DE DIRECTIONS (lot 3.5-c) : une
        // grille tangente autour du point sous l'œil, chaque direction
        // projetée vers (face, case) par fromSphere, dédoublonnée par clef
        // canonique. Les arêtes et coins du cube sont traités par
        // construction — l'ancienne double boucle s'arrêtait à la face
        // courante et laissait un pan du disque vide près des arêtes.
        //
        // Pas de 16,4 m : la moitié de la case la plus déformée (coin de
        // face, −25 %). Le §2 de validation/suivi_foret.py prouve la
        // couverture : toute case contient un disque inscrit plus grand
        // que la demi-diagonale de la grille d'échantillons.
        val cellArcM = (Math.PI / 2.0 * planetRadiusM) / total
        val stepM = cellArcM * 0.5 * 0.75
        val stepRad = stepM / planetRadiusM
        val k = ceil((range + cellArcM) / stepM).toInt()
        val east0 = eastOf(ground)
        val north0 = (ground cross east0)
        val seenCells = HashSet<Long>(k * k / 2)
        val limitSq = (range + cellArcM) * (range + cellArcM)

        for (j in -k..k) {
            for (i in -k..k) {
                if ((i.toDouble() * i + j.toDouble() * j) * stepM * stepM > limitSq) continue
                val dir = Vec3(
                    ground.x + (east0.x * i + north0.x * j) * stepRad.toFloat(),
                    ground.y + (east0.y * i + north0.y * j) * stepRad.toFloat(),
                    ground.z + (east0.z * i + north0.z * j) * stepRad.toFloat()
                ).normalized()
                val (f, sc, tc) = CubeSphere.fromSphere(dir)
                val cx = ((sc + 1f) * 0.5 * total).toLong()
                    .coerceIn(0L, total.toLong() - 1)
                val cy = ((tc + 1f) * 0.5 * total).toLong()
                    .coerceIn(0L, total.toLong() - 1)
                if (!seenCells.add(PlantExclusion.key(f, cx, cy))) continue
                visited++
                collectCell(f, cx, cy, total, eyePosM, range, pxPerRadian,
                    hint, drawnSet, candidates)
            }
        }

        // Tri par taille apparente décroissante, PUIS par clef de case :
        // le second critère rend le champ indépendant de l'ordre
        // d'échantillonnage — deux grilles différentes donneraient le même
        // résultat au bit près.
        candidates.sortWith(
            compareByDescending<Candidate> { it.apparentPx }.thenBy { it.cellKey }
        )

        // ALLOCATION EN DEUX TEMPS (v0.52.1). La version gloutonne donnait
        // TOUT le budget au niveau plein : 38 arbres superbes puis
        // brutalement plus que des losanges — la « falaise » constatée sur
        // photo, et pourtant écrite noir sur blanc dans l'instruction
        // (« 38 pleins, 1 moyen, 0 bas ») sans que j'en voie l'effet.
        //
        // 1) chaque arbre reçoit le niveau que sa TAILLE APPARENTE dicte ;
        // 2) si le total déborde, on DÉGRADE DEPUIS LA QUEUE (les plus
        //    petits à l'écran d'abord), un cran à la fois, jusqu'à tenir.
        // La transition devient progressive : pleins, puis moyens, puis
        // bas, puis losanges — jamais de falaise.
        val levels = arrayOfNulls<TreeDetail>(candidates.size)
        var spent = 0L
        for (i in candidates.indices) {
            val allowed = TreeLodBudget.detailForSize(candidates[i].apparentPx)
            if (allowed.ordinal >= TreeDetail.BILLBOARD.ordinal) continue
            levels[i] = allowed
            spent += triangleCost(candidates[i].species, allowed)
        }
        var tail = candidates.size - 1
        while (spent > budgetTriangles && tail >= 0) {
            val level = levels[tail]
            if (level == null) {
                tail--
                continue
            }
            val degraded = when (level) {
                TreeDetail.FULL -> TreeDetail.MEDIUM
                TreeDetail.MEDIUM -> TreeDetail.LOW
                else -> null
            }
            spent -= triangleCost(candidates[tail].species, level)
            levels[tail] = degraded
            if (degraded != null) {
                spent += triangleCost(candidates[tail].species, degraded)
            }
            // On ne recule dans la liste que lorsque l'arbre est épuisé :
            // un même arbre peut descendre de deux crans avant son voisin.
            if (degraded == null) tail--
        }

        val instances = ArrayList<Instance>(candidates.size)
        val occupied = HashSet<Long>(candidates.size)
        for (i in candidates.indices) {
            val chosen = levels[i] ?: continue
            val c = candidates[i]
            val variants = variantCount(chosen)
            val key = VariantKey(c.species, chosen, (c.variantU * variants).toInt()
                .coerceIn(0, variants - 1))
            instances += Instance(
                posXM = c.posXM, posYM = c.posYM, posZM = c.posZM,
                frame = c.frame, variant = key, apparentPx = c.apparentPx,
                annualTempC = c.annualTempC, precipMm = c.precipMm,
                sinLat = c.sinLat, continentality01 = c.continentality01,
                saltPhase = c.saltPhase, saltHue = c.saltHue,
                saltShade = c.saltShade
            )
            occupied += c.cellKey
        }

        return Field(instances, spent.toInt(), visited, candidates.size, occupied)
    }

    // ------------------------------------------------------------------

    private class Candidate(
        val posXM: Double, val posYM: Double, val posZM: Double,
        val frame: FloatArray,
        val species: TreeSpecies,
        val variantU: Float,
        val apparentPx: Float,
        val cellKey: Long,
        val annualTempC: Float,
        val precipMm: Float,
        val sinLat: Float,
        val continentality01: Float,
        val saltPhase: Float,
        val saltHue: Float,
        val saltShade: Float
    )

    private fun collectCell(
        face: Int, cellX: Long, cellY: Long, total: Double,
        eyePosM: Vec3d, rangeM: Double, pxPerRadian: Float,
        hint: IntArray, drawnTiles: HashSet<Long>, out: ArrayList<Candidate>
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

        // Position métrique : altitude de la SURFACE DESSINÉE — la tuile
        // que le sélecteur a retenue pour cette position d'œil, interpolée
        // bilinéairement entre ses nœuds, exactement comme le vertex shader
        // la rend. L'enfouissement ne couvre plus que l'anneau de base
        // ouvert et le morphing entre niveaux, plus les mètres d'écart
        // tuile/exact qui faisaient flotter les arbres.
        val drawnAlt = drawnAltitudeAt(face, cellX, cellY, total, px, py, drawnTiles)
            ?: alt   // hors des tuiles dessinées (dos de la planète) : exact
        val sink = speciesSink(species) * scale
        val r = planetRadiusM + drawnAlt.toDouble() - sink
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

        // Intrants de la couleur (lot 3.4), lus EN DERNIER : tous les rejets
        // — eau, densité, pente, espèce nulle, hors de portée — sont déjà
        // passés, et trois échantillonnages lissés par case visitée auraient
        // été payés pour rien sur les milliers de cases écartées.
        //
        // Champs LISSÉS : la grille est à 115 km, et le plus proche voisin
        // peindrait la forêt par aplats de cellule — le défaut même que le
        // lissage avait corrigé sur les couleurs de biome.
        //
        // sin(latitude) est simplement d.y : l'axe polaire est Y, convention
        // figée du projet.
        val annualTempC = sampler.smoothTemperatureAt(d, near)
        val precipMm = sampler.smoothPrecipitationAt(d, near)
        val continentality = sampler.smoothContinentalityAt(d, near)

        out += Candidate(
            posX, posY, posZ, frame, species, variantU, apparent,
            PlantExclusion.key(face, cellX, cellY),
            annualTempC = annualTempC,
            precipMm = precipMm,
            sinLat = d.y,
            continentality01 = continentality,
            // Sels +9, +10, +11 : les huit premiers sont pris (gigue, densité,
            // taille, ombrage, azimut, espèce, variante). Même famille de
            // hachage, donc même déterminisme par graine et par position.
            saltPhase = profile.micro01(sx * 31 + 9),
            saltHue = profile.micro01(sx * 31 + 10),
            saltShade = profile.micro01(sx * 31 + 11)
        )
    }

    /**
     * Altitude de la surface dessinée au point (px, py) du treillis.
     *
     * Descend l'arbre quadtree jusqu'à la tuile PRÉSENTE dans la sélection,
     * puis interpole bilinéairement les quatre nœuds encadrants de sa
     * grille 16×16 — mêmes indices globaux, même [CubeSphere.gridDirection],
     * même [TerrainProfile.renderedAltitudeAt] que `TileMesh` : c'est
     * l'invariant n°3 étendu à la végétation. Nul si aucune tuile de la
     * sélection ne contient le point.
     */
    private fun drawnAltitudeAt(
        face: Int, cellX: Long, cellY: Long, total: Double,
        px: Double, py: Double, drawnTiles: HashSet<Long>
    ): Float? {
        // Fraction de face dans [0 ; 1).
        val u = (px / total).coerceIn(0.0, 0.9999999)
        val v = (py / total).coerceIn(0.0, 0.9999999)
        var tile: TileId? = null
        for (level in 0..TileId.MAX_LEVEL) {
            val cells = 1L shl level
            val tx = (u * cells).toInt()
            val ty = (v * cells).toInt()
            val candidate = TileId(face, level, tx, ty)
            if (drawnTiles.contains(candidate.packed())) {
                tile = candidate
                break
            }
        }
        val t = tile ?: return null

        val n = TileMesh.MESH_N
        val tileCells = (1L shl t.level).toDouble()
        val fx = (u * tileCells - t.x) * n     // [0 ; 16) dans la tuile
        val fy = (v * tileCells - t.y) * n
        val i0 = fx.toInt().coerceIn(0, n - 1)
        val j0 = fy.toInt().coerceIn(0, n - 1)
        val ax = (fx - i0).toFloat()
        val ay = (fy - j0).toFloat()
        val baseGx = t.x * n
        val baseGy = t.y * n

        val a00 = nodeAltitude(t, baseGx + i0, baseGy + j0, n)
        val a10 = nodeAltitude(t, baseGx + i0 + 1, baseGy + j0, n)
        val a01 = nodeAltitude(t, baseGx + i0, baseGy + j0 + 1, n)
        val a11 = nodeAltitude(t, baseGx + i0 + 1, baseGy + j0 + 1, n)
        return (a00 * (1f - ax) + a10 * ax) * (1f - ay) +
            (a01 * (1f - ax) + a11 * ax) * ay
    }

    /** L'altitude d'un nœud de tuile, ÉCHANTILLONNÉE COMME TileMesh. */
    private fun nodeAltitude(tile: TileId, gx: Int, gy: Int, n: Int): Float {
        val d = CubeSphere.gridDirection(tile.face, tile.level, gx, gy, n)
        return profile.renderedAltitudeAt(d.toVec3())
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

}
