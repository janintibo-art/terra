package com.terra.sim

import com.terra.core.Geodesy
import com.terra.core.Vec3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Caméra planétaire orbitant autour d'un point de la surface.
 *
 * ## Le modèle
 *
 * Plutôt qu'une caméra libre flottant dans l'espace, on adopte le modèle des
 * globes virtuels : la caméra vise toujours un **point d'intérêt posé sur le
 * sol**, à une certaine distance, sous un certain cap et une certaine
 * inclinaison. Glisser déplace ce point ; pincer change la distance.
 *
 * ## Pourquoi ce modèle donne la bonne sensation
 *
 * C'est ici que se joue la différence entre « manipuler un objet » et « se
 * déplacer dans un lieu ». Le pivot n'est pas au centre de la planète mais à la
 * surface, et l'amplitude d'un glissement est proportionnelle à la distance :
 *
 *  - à 20 000 km, un glissement d'un demi-écran fait rouler tout le globe ;
 *  - à 50 m, le même geste fait défiler cinquante mètres de sol.
 *
 * Aucun basculement de mode, aucun seuil : c'est la même formule, et la
 * transition d'une sensation à l'autre est continue. Cette proportionnalité est
 * la seule chose qui rende une navigation planétaire utilisable aux deux
 * extrémités de l'échelle.
 *
 * ## Précision
 *
 * L'état est purement géodésique — latitude, longitude, distance, cap,
 * inclinaison — et tout est calculé en double précision. La position métrique
 * n'est produite qu'à la demande. Le tremblement du flottant 32 bits, qui atteint
 * cinquante centimètres à la surface, ne peut donc pas s'introduire dans l'état
 * de la caméra ; il sera écarté du rendu par les coordonnées relatives.
 */
class PlanetCamera(
    val planetRadiusM: Double,

    /** Latitude du point visé, en radians. */
    var focusLatRad: Double = 0.0,

    /** Longitude du point visé, en radians. */
    var focusLonRad: Double = 0.0,

    /** Distance de l'œil au point visé, en mètres. */
    rangeM: Double = 24_000_000.0,

    /** Cap, en radians. 0 = le nord est en haut de l'écran. */
    var headingRad: Double = 0.0,

    /** Inclinaison, en radians. 0 = vue verticale, π/2 = horizon. */
    tiltRad: Double = 0.0
) {

    var rangeM: Double = rangeM
        set(value) { field = value.coerceIn(MIN_RANGE_M, MAX_RANGE_M) }

    var tiltRad: Double = tiltRad
        set(value) { field = value.coerceIn(0.0, maxTiltRad()) }

    init {
        this.rangeM = rangeM
        this.tiltRad = tiltRad
    }

    /** Altitude du sol sous le point visé, renseignée par [snapToTerrain]. */
    var focusGroundAltitudeM: Double = 0.0
        private set

    // ------------------------------------------------------------- géométrie

    /** Direction unitaire du centre de la planète vers le point visé. */
    fun focusDirection(): Vec3d = Geodesy.toUnit(focusLatRad, focusLonRad)

    /** Position du point visé, en mètres depuis le centre. */
    fun focusPositionM(): Vec3d =
        focusDirection() * (planetRadiusM + max(0.0, focusGroundAltitudeM))

    /**
     * Décalage unitaire de l'œil par rapport au point visé.
     *
     * À inclinaison nulle, l'œil est à la verticale du point visé. À mesure que
     * l'inclinaison croît, il bascule vers l'arrière du cap, jusqu'à raser
     * l'horizon.
     */
    fun eyeOffsetDirection(): Vec3d {
        val focus = focusDirection()
        val up = focus
        val north = Geodesy.northAt(focus)
        val east = Geodesy.eastAt(focus)
        val horizontal = north * cos(headingRad) + east * sin(headingRad)
        return (up * cos(tiltRad) - horizontal * sin(tiltRad)).normalized()
    }

    /** Position de l'œil, en mètres depuis le centre de la planète. */
    fun eyePositionM(): Vec3d = focusPositionM() + eyeOffsetDirection() * rangeM

    /** Direction de visée, unitaire. */
    fun forward(): Vec3d = -eyeOffsetDirection()

    /** Vecteur droite de la caméra, unitaire. */
    fun right(): Vec3d {
        val f = forward()
        val worldUp = focusDirection()
        val r = f cross worldUp
        return if (r.lengthSq < 1e-12) Geodesy.eastAt(focusDirection()) else r.normalized()
    }

    /** Vecteur haut de la caméra, unitaire et orthogonal à la visée. */
    fun up(): Vec3d = (right() cross forward()).normalized()

    /** Altitude de l'œil au-dessus du niveau de la mer, en mètres. */
    fun eyeAltitudeM(): Double = eyePositionM().length - planetRadiusM

    // -------------------------------------------------------------- limites

    /**
     * Inclinaison maximale autorisée à la distance courante.
     *
     * Depuis l'orbite, incliner n'a pas de sens et donnerait une vue rasante
     * illisible ; près du sol, c'est au contraire ce qui donne l'horizon et la
     * sensation d'être posé quelque part. La limite s'ouvre donc
     * progressivement à mesure qu'on descend, sur une échelle logarithmique —
     * la seule qui soit perceptivement régulière sur sept ordres de grandeur.
     */
    fun maxTiltRad(): Double {
        val t = ((ln(TILT_OPEN_RANGE_M / rangeM) / ln(10.0)) / 2.0).coerceIn(0.0, 1.0)
        return t * MAX_TILT_RAD
    }

    // ------------------------------------------------------------- commandes

    /**
     * Déplace le point visé selon un glissement à l'écran.
     *
     * L'amplitude est proportionnelle à la distance : c'est ce qui produit la
     * transition continue entre « faire rouler le globe » et « faire défiler le
     * sol ».
     *
     * @param dxPixels déplacement horizontal, positif vers la droite
     * @param dyPixels déplacement vertical, positif vers le bas
     * @param viewportHeightPx hauteur de la surface d'affichage
     * @param verticalFovRad champ de vision vertical
     */
    fun pan(
        dxPixels: Double,
        dyPixels: Double,
        viewportHeightPx: Double,
        verticalFovRad: Double = DEFAULT_FOV_RAD
    ) {
        if (viewportHeightPx <= 0.0) return

        // Étendue de terrain visible à la distance courante, puis conversion
        // en mètres par pixel.
        val visibleHeightM = 2.0 * rangeM * tan(verticalFovRad * 0.5)
        val metresPerPixel = visibleHeightM / viewportHeightPx

        // Un glissement vers la droite doit faire venir le terrain de droite :
        // le point visé se déplace donc vers la gauche, d'où les signes.
        var eastM = -dxPixels * metresPerPixel
        var northM = dyPixels * metresPerPixel

        // L'inclinaison écrase la composante verticale de l'écran : plus la vue
        // est rasante, plus un pixel vers le haut couvre de terrain.
        val tiltCompensation = 1.0 / max(0.25, cos(tiltRad))
        northM *= tiltCompensation

        // Le glissement s'exprime dans le repère écran, qu'il faut ramener dans
        // le repère local par le cap.
        val c = cos(headingRad)
        val s = sin(headingRad)
        val localNorth = northM * c - eastM * s
        val localEast = northM * s + eastM * c

        moveFocusMetres(localNorth, localEast)
    }

    /** Déplace le point visé de tant de mètres vers le nord et vers l'est. */
    fun moveFocusMetres(northM: Double, eastM: Double) {
        val distance = kotlin.math.sqrt(northM * northM + eastM * eastM)
        if (distance < 1e-9) return

        val focus = focusDirection()
        val north = Geodesy.northAt(focus)
        val east = Geodesy.eastAt(focus)
        val tangent = (north * (northM / distance) + east * (eastM / distance)).normalized()

        val moved = Geodesy.move(focus, tangent, distance / planetRadiusM)
        focusLatRad = Geodesy.latitude(moved)
        focusLonRad = Geodesy.longitude(moved)
    }

    /** Multiplie la distance ; un facteur supérieur à 1 éloigne. */
    fun zoom(factor: Double) {
        if (factor <= 0.0) return
        rangeM *= factor
        // L'inclinaison peut devenir illégale après un éloignement.
        tiltRad = tiltRad
    }

    /**
     * Zoom conservant un point du terrain sous le doigt.
     *
     * Sans cela, pincer ramène toujours vers le centre de l'écran, et viser un
     * détail devient un exercice de patience. Le point visé glisse vers la cible
     * dans la proportion exacte du rapprochement : au terme d'un zoom infini, il
     * l'atteint.
     *
     * @param targetDirection direction unitaire du point du sol à conserver
     * @param factor facteur appliqué à la distance ; inférieur à 1 rapproche
     */
    fun zoomTowards(targetDirection: Vec3d, factor: Double) {
        if (factor <= 0.0) return
        val before = rangeM
        rangeM *= factor
        val applied = rangeM / before          // facteur réellement appliqué

        if (applied >= 1.0) {
            tiltRad = tiltRad
            return
        }

        val focus = focusDirection()
        val separation = Geodesy.angleBetween(focus, targetDirection)
        if (separation < 1e-12) { tiltRad = tiltRad; return }

        // On parcourt la même fraction du chemin que celle dont on s'est
        // rapproché : le point conserve ainsi sa position à l'écran.
        val fraction = (1.0 - applied).coerceIn(0.0, 1.0)
        val axis = (focus cross targetDirection).normalized()
        if (!axis.isFinite() || axis.lengthSq < 1e-18) { tiltRad = tiltRad; return }

        val moved = focus.rotatedAround(axis, separation * fraction).normalized()
        focusLatRad = Geodesy.latitude(moved)
        focusLonRad = Geodesy.longitude(moved)
        tiltRad = tiltRad
    }

    /** Fait pivoter le cap, en radians. */
    fun rotate(deltaRad: Double) {
        headingRad = normalizeAngle(headingRad + deltaRad)
    }

    /** Modifie l'inclinaison, dans les limites autorisées à la distance courante. */
    fun tilt(deltaRad: Double) {
        tiltRad = tiltRad + deltaRad
    }

    // ------------------------------------------------------------- ancrage

    /**
     * Ancre la caméra sur le relief : le point visé se pose sur le sol, et l'œil
     * est repoussé s'il devait passer sous le terrain.
     *
     * À appeler après toute manipulation. Sans cela, viser une vallée depuis un
     * col ferait traverser la montagne.
     */
    fun snapToTerrain(raycaster: TerrainRaycaster, clearanceM: Double = 2.0) {
        focusGroundAltitudeM = max(0.0, raycaster.altitudeAlong(focusDirection()))

        // L'œil doit rester au-dessus du terrain, sous lui-même comme le long de
        // la ligne de visée. On corrige d'abord par la distance, qui préserve la
        // composition de l'image mieux qu'un redressement de l'inclinaison.
        var guard = 0
        while (guard < 32) {
            guard++
            val eye = eyePositionM()
            val clearance = raycaster.heightAboveTerrain(eye)
            if (clearance >= clearanceM) break

            val deficit = clearanceM - clearance
            rangeM += max(deficit * 1.2, rangeM * 0.05)

            // Reculer ne suffit pas toujours : à forte inclinaison, l'œil se
            // déplace surtout à l'horizontale et peut s'enfoncer dans le relief
            // voisin. Passé quelques tentatives, on redresse aussi la vue, ce
            // qui l'élève à coup sûr.
            if (guard > 8 && tiltRad > 0.0) tiltRad = tiltRad * 0.85

            if (rangeM >= MAX_RANGE_M) break
        }
    }

    // ------------------------------------------------------------- utilitaires

    /**
     * Direction d'un rayon partant de l'œil vers un point de l'écran.
     *
     * @param ndcX abscisse normalisée, −1 à gauche, +1 à droite
     * @param ndcY ordonnée normalisée, −1 en bas, +1 en haut
     * @param aspect largeur divisée par hauteur
     */
    fun rayDirection(
        ndcX: Double,
        ndcY: Double,
        aspect: Double,
        verticalFovRad: Double = DEFAULT_FOV_RAD
    ): Vec3d {
        val half = tan(verticalFovRad * 0.5)
        return (forward() + right() * (ndcX * half * aspect) + up() * (ndcY * half))
            .normalized()
    }

    /** Copie indépendante, utile pour anticiper un mouvement sans l'appliquer. */
    fun copy(): PlanetCamera = PlanetCamera(
        planetRadiusM, focusLatRad, focusLonRad, rangeM, headingRad, tiltRad
    ).also { it.focusGroundAltitudeM = focusGroundAltitudeM }

    override fun toString(): String =
        "PlanetCamera(lat=%.4f°, lon=%.4f°, portée=%.0f m, cap=%.0f°, inclinaison=%.0f°)"
            .format(
                focusLatRad * 180.0 / PI, focusLonRad * 180.0 / PI,
                rangeM, headingRad * 180.0 / PI, tiltRad * 180.0 / PI
            )

    companion object {
        /** Distance minimale : deux mètres du sol, hauteur d'un regard humain. */
        const val MIN_RANGE_M = 2.0

        /** Distance maximale : la planète tient largement dans le champ. */
        const val MAX_RANGE_M = 80_000_000.0

        /** Champ de vision vertical par défaut, 42 degrés. */
        const val DEFAULT_FOV_RAD = 0.733038

        /** Inclinaison maximale, 82 degrés : au-delà, la vue rase le sol. */
        const val MAX_TILT_RAD = 1.431170

        /** Distance à partir de laquelle l'inclinaison commence à s'ouvrir. */
        const val TILT_OPEN_RANGE_M = 2_000_000.0

        fun normalizeAngle(a: Double): Double {
            var x = a
            while (x > PI) x -= 2.0 * PI
            while (x < -PI) x += 2.0 * PI
            return x
        }
    }
}
