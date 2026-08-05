package com.terra.sim

import com.terra.core.Vec3d
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Point d'impact d'un rayon sur le terrain. */
data class RayHit(
    /** Position de l'impact, en mètres depuis le centre de la planète. */
    val positionM: Vec3d,
    /** Direction unitaire du centre vers l'impact. */
    val direction: Vec3d,
    /** Altitude du terrain au point d'impact, en mètres. */
    val altitudeM: Double,
    /** Distance parcourue depuis l'origine du rayon, en mètres. */
    val distanceM: Double,
    /** Nombre d'itérations consommées, utile au réglage et au diagnostic. */
    val iterations: Int
)

/**
 * Lancer de rayon sur le terrain.
 *
 * ## À quoi cela sert
 *
 * Trois usages, tous indispensables :
 *
 *  - **Zoom vers le doigt** : pour que le point du sol sous les doigts y reste
 *    pendant un pincement, il faut savoir quel point c'est. C'est ce qui
 *    distingue une navigation agréable d'un zoom qui dérive vers le centre.
 *  - **Butée de caméra** : empêcher l'observateur de traverser une montagne.
 *  - **Sélection d'entités** en Phase 4 : désigner une créature du doigt.
 *
 * ## Méthode
 *
 * Le terrain n'est pas une surface analytique : impossible de résoudre
 * l'intersection en fermé. On emploie donc le *sphere tracing* : à chaque étape,
 * on mesure la hauteur du point courant au-dessus du terrain, et l'on avance
 * d'une fraction de cette hauteur. Tant qu'on est haut, on avance vite ; en
 * approchant, les pas se raccourcissent naturellement.
 *
 * La fraction est prise à 0,6 plutôt qu'à 1 : le champ d'élévation n'est pas
 * strictement 1-lipschitzien le long d'un rayon rasant, et un pas plein pourrait
 * traverser une crête sans la voir. Une bissection finale affine le contact.
 *
 * ## Précision
 *
 * L'évaluation du terrain se fait en 32 bits, ce qui situe le point
 * d'échantillonnage à quelques décimètres près sur la sphère unité. Le terrain
 * variant lentement à cette échelle, l'erreur sur l'altitude reste très
 * inférieure au mètre — assez pour la caméra comme pour la désignation.
 */
class TerrainRaycaster(
    private val terrain: TerrainProfile,
    private val planetRadiusM: Double = terrain.params.radiusM.toDouble()
) {

    /**
     * Amplitude du détail haute fréquence incluse dans la surface de collision.
     *
     * ## Le bug que ce champ corrige (v0.7.1)
     *
     * Le lancer de rayon évaluait le champ de base, mais les tuiles proches
     * rendent `detailedAltitudeAt` — jusqu'à ±26 m d'écart. L'ancrage de la
     * caméra garantissait donc deux mètres au-dessus d'une surface qui n'était
     * pas celle affichée : près du sol, l'œil passait sous le terrain rendu et
     * l'écran devenait noir, toutes les faces proches étant vues de dos.
     *
     * La collision se fait désormais sur la surface au **niveau de détail
     * maximal**. Quand les tuiles visibles sont plus grossières, la caméra
     * flotte au pire à quelques mètres au-dessus du sol affiché — un défaut
     * invisible, là où l'inverse enterrait l'observateur.
     */
    private val collisionDetailAmpM: Float =
        terrain.detailAmplitudeForLevel(TileId.MAX_LEVEL)

    /** Rayon de la sphère englobant tout relief émergé, détail compris. */
    private val outerRadius: Double =
        planetRadiusM + terrain.params.maxAltitudeM.toDouble() +
                collisionDetailAmpM.toDouble() + 1.0

    /** Rayon de la sphère sous laquelle aucun terrain ne peut se trouver. */
    private val innerRadius: Double =
        planetRadiusM - terrain.params.maxDepthM.toDouble() - 1.0

    /**
     * Altitude du terrain dans une direction donnée, en mètres.
     *
     * C'est la surface **rendue** qui fait foi, détail haute fréquence
     * compris : caméra et maillage doivent voir le même monde. Un test
     * vérifie l'égalité exacte avec ce que produit le mailleur au niveau
     * maximal.
     */
    fun altitudeAlong(direction: Vec3d): Double =
        terrain.detailedAltitudeAt(direction.normalized().toVec3(), collisionDetailAmpM)
            .toDouble()

    /**
     * Hauteur d'un point au-dessus du terrain. Négative sous la surface.
     *
     * Le niveau de la mer fait office de plancher : sur l'océan, la surface
     * pertinente est l'eau, pas le fond.
     */
    fun heightAboveTerrain(positionM: Vec3d): Double {
        val dir = positionM.normalized()
        val ground = planetRadiusM + max(0.0, altitudeAlong(dir))
        return positionM.length - ground
    }

    /**
     * Lance un rayon et rend le premier contact avec le terrain.
     *
     * @param originM origine du rayon, en mètres depuis le centre de la planète
     * @param direction direction du rayon, normalisée en interne
     * @param maxDistanceM distance maximale explorée
     * @return le point d'impact, ou null si le rayon manque la planète
     */
    fun cast(
        originM: Vec3d,
        direction: Vec3d,
        maxDistanceM: Double = planetRadiusM * 8.0,
        toleranceM: Double = 0.5
    ): RayHit? {
        val dir = direction.normalized()
        if (!dir.isFinite() || dir.lengthSq < 0.5) return null

        // Fenêtre utile : on saute le vide jusqu'à la sphère englobante.
        val window = sphereInterval(originM, dir, outerRadius) ?: return null
        var t = max(0.0, window.first)
        val tEnd = min(maxDistanceM, window.second)
        if (t > tEnd) return null

        // Si l'origine est déjà sous le terrain, la notion de premier contact
        // n'a pas de sens : on rend immédiatement la position courante.
        if (heightAboveTerrain(originM) < 0.0) {
            val d = originM.normalized()
            return RayHit(originM, d, altitudeAlong(d), 0.0, 0)
        }

        var previousT = t
        var previousH = heightAboveTerrain(originM + dir * t)
        var iterations = 0

        while (t < tEnd && iterations < MAX_ITERATIONS) {
            iterations++
            val p = originM + dir * t
            val h = heightAboveTerrain(p)

            if (h < toleranceM) {
                // Contact : on affine entre le dernier point au-dessus et
                // celui-ci, par bissection.
                val hit = refine(originM, dir, previousT, previousH, t, toleranceM, iterations)
                return hit
            }

            previousT = t
            previousH = h
            // Pas prudent : une fraction de la hauteur disponible, jamais nul.
            t += max(MIN_STEP_M, h * STEP_FRACTION)
        }
        return null
    }

    /** Bissection entre un point au-dessus du sol et un point au niveau ou en dessous. */
    private fun refine(
        originM: Vec3d,
        dir: Vec3d,
        tAbove: Double,
        hAbove: Double,
        tBelow: Double,
        toleranceM: Double,
        startIterations: Int
    ): RayHit {
        var lo = tAbove
        var hi = tBelow
        var iterations = startIterations
        var best = hi

        if (hAbove <= 0.0) {
            lo = tAbove
            hi = tAbove
        }

        repeat(REFINE_STEPS) {
            iterations++
            val mid = (lo + hi) * 0.5
            val h = heightAboveTerrain(originM + dir * mid)
            if (h > 0.0) lo = mid else { hi = mid; best = mid }
            if (abs(h) < toleranceM * 0.25) { best = mid; return@repeat }
        }

        val p = originM + dir * best
        val d = p.normalized()
        return RayHit(
            positionM = d * (planetRadiusM + max(0.0, altitudeAlong(d))),
            direction = d,
            altitudeM = altitudeAlong(d),
            distanceM = best,
            iterations = iterations
        )
    }

    /**
     * Intersection d'un rayon avec une sphère centrée à l'origine.
     * @return l'intervalle des paramètres, ou null si le rayon manque la sphère
     */
    fun sphereInterval(originM: Vec3d, dir: Vec3d, radius: Double): Pair<Double, Double>? {
        val b = 2.0 * (originM dot dir)
        val c = originM.lengthSq - radius * radius
        val discriminant = b * b - 4.0 * c
        if (discriminant < 0.0) return null
        val root = sqrt(discriminant)
        val t0 = (-b - root) * 0.5
        val t1 = (-b + root) * 0.5
        if (t1 < 0.0) return null
        return Pair(t0, t1)
    }

    /**
     * Intersection avec la sphère du niveau de la mer.
     * Repli utile lorsque le terrain n'a pas encore été évalué, ou pour un
     * calcul très rapide sur l'océan.
     */
    fun castSeaLevel(originM: Vec3d, direction: Vec3d): Vec3d? {
        val dir = direction.normalized()
        val hit = sphereInterval(originM, dir, planetRadiusM) ?: return null
        val t = if (hit.first >= 0.0) hit.first else hit.second
        if (t < 0.0) return null
        return originM + dir * t
    }

    companion object {
        private const val MAX_ITERATIONS = 400
        private const val REFINE_STEPS = 32
        private const val MIN_STEP_M = 0.05
        /**
         * Fraction de la hauteur disponible parcourue à chaque pas. Prise
         * franchement sous 1 : sur un rayon rasant, un pas plein pourrait
         * franchir une crête sans la détecter.
         */
        private const val STEP_FRACTION = 0.6
    }
}
