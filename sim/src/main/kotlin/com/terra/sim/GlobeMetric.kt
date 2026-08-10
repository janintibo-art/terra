package com.terra.sim

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Globe métrique — lot 2.7-b1.
 *
 * ## Le problème
 *
 * Le limbe du quadtree est polygonal, et le fondu v0.24.0 le dissout dans un
 * disque sans relief. L'icosphère raffinée du mode contemplatif est ronde par
 * construction — mais elle vit dans SON repère : unités de planète (1,0 = la
 * mer), relief exagéré pour être visible de loin. Pour la dessiner dans le
 * repère métrique de la descente, il faut reconstruire le rayon vrai depuis
 * le rayon exagéré, et c'est un calcul d'écran formulable en Kotlin pur :
 * il vit donc ici, testé, et le shader n'en est que le miroir.
 *
 * ## Les chiffres (validation/bascule.py)
 *
 * Un VBO float32 en repère planétaire tremble de 0,76 m au pire — 0,0006 px
 * à la distance minimale du régime orbite. C'est mille fois sous le pixel,
 * et c'est POURQUOI ce chemin est réservé aux modes de diagnostic et, à
 * terme, au registre orbite : le même VBO au sol violerait l'invariant n°5.
 * La dés-exagération en shader ajoute 2 cm d'erreur d'altitude — invisible.
 */
object GlobeMetric {

    /**
     * Facteur de dés-exagération : altitude vraie = (r_exagéré − 1) × facteur.
     *
     * Inverse exact de [PlanetData.renderRadius] (r = 1 + a/maxAlt·exag).
     * La garde sur l'exagération nulle est indispensable : le curseur de
     * l'éditeur descend à 0, et maxAlt/0 vaudrait l'infini — que le shader
     * multiplierait par (r − 1) = 0 pour produire NaN, la leçon v0.35.1
     * transposée. À exagération nulle, le globe est déjà une sphère à la
     * mer : le facteur 0 rend la même géométrie, sans piège.
     */
    fun deExaggerationFactor(maxAltitudeM: Float, reliefExaggeration: Float): Float =
        if (reliefExaggeration < 1e-6f) 0f else maxAltitudeM / reliefExaggeration

    /** Rayon métrique vrai d'un sommet — le miroir Kotlin du shader. */
    fun trueRadiusM(renderRadiusUnit: Float, deExagFactor: Float, planetRadiusM: Double): Double =
        planetRadiusM + (renderRadiusUnit - 1f).toDouble() * deExagFactor

    /**
     * Dépression radiale de la collerette, en mètres.
     *
     * La collerette se dessine AVANT les tuiles ; là où les deux surfaces
     * coïncident (aux sommets des tuiles, posés sur le terrain vrai), le
     * tampon de profondeur doit trancher sans scintiller. On enfonce donc la
     * collerette de deux quanta de profondeur à la distance du limbe —
     * quantum ≈ d²/(near·2²⁴) pour un tampon 24 bits en projection
     * perspective. Le biais croît en d², la tolérance de silhouette en d :
     * chiffré de 200 à 6 000 km, la silhouette reste sous 0,35 px
     * (validation/bascule.py §7). Au-delà, le mode utile est « globe »
     * entier, pas la collerette.
     */
    fun collarBiasM(eyeAltitudeM: Double, planetRadiusM: Double, nearPlaneM: Double): Double {
        val alt = max(2.0, eyeAltitudeM)
        val r = planetRadiusM
        val slantSq = max(0.0, (r + alt) * (r + alt) - r * r)
        return 2.0 * slantSq / (max(1.0, nearPlaneM) * 16_777_216.0)
    }
}

/**
 * Sélection des faces du globe proches du limbe (mode « collerette »).
 *
 * ## L'idée
 *
 * Les tuiles grossières s'enfoncent sous l'arc vrai entre leurs sommets ; au
 * limbe, vues par la tranche, elles laissent la silhouette polygonale. On
 * glisse SOUS elles la fine bande de l'icosphère qui borde l'horizon : la
 * silhouette redevient celle du globe, et les tuiles recouvrent tout le
 * reste. ~1 000-1 500 faces sur 82 000, une frange de 3 à 8 px à l'écran.
 *
 * ## La fenêtre angulaire
 *
 * L'horizon est à θ_max = acos(R/(R+h)) du nadir. La bande retenue s'étend
 * de θ_max − [INNER_RAD] (côté visible : couvrir la zone où les cordes des
 * tuiles de niveau 1-3 s'enfoncent) à θ_max + [OUTER_RAD] (au-delà de
 * l'horizon : les reliefs qui dépassent la tangente — 7 000 m se voient
 * jusqu'à 0,047 rad derrière l'horizon — plus une marge de mouvement).
 * Les marges absorbent le déplacement de la caméra entre deux resélections,
 * dont [shouldRebuild] fixe la cadence.
 */
object LimbBand {

    /** Étendue de la bande côté visible, en radians d'angle au centre. */
    const val INNER_RAD = 0.075f

    /** Étendue au-delà de l'horizon (reliefs saillants + marge). */
    const val OUTER_RAD = 0.055f

    /** Resélection dès que le nadir a tourné de cet angle… */
    const val REBUILD_ANGLE_RAD = 0.012

    /** …ou que l'altitude a varié de ce ratio. */
    const val REBUILD_ALT_RATIO = 0.05

    /**
     * Indices des faces dont le centre tombe dans la bande du limbe.
     *
     * @param faceCenterDirs directions unitaires des centres de faces,
     *        3 flottants par face, dans l'ordre du tampon de sommets
     * @param eyeDirX/Y/Z direction unitaire du centre de la planète vers
     *        l'œil (le nadir inversé)
     * @return indices croissants, prêts pour la copie de faces
     */
    fun selectFaces(
        faceCenterDirs: FloatArray,
        eyeDirX: Float, eyeDirY: Float, eyeDirZ: Float,
        eyeAltitudeM: Double,
        planetRadiusM: Double
    ): IntArray {
        val alt = max(2.0, eyeAltitudeM)
        val thetaMax = acos((planetRadiusM / (planetRadiusM + alt)).coerceIn(0.0, 1.0))
        // cos est décroissant : la borne PROCHE du nadir donne le cos haut.
        // Bornes converties en Float UNE fois : le produit scalaire est en
        // Float, et Kotlin refuse (à raison) de mélanger les deux dans un
        // intervalle — la comparaison Float/Double serait une promotion
        // silencieuse par sommet.
        val inner = (thetaMax - INNER_RAD).coerceAtLeast(0.0)
        val outer = thetaMax + OUTER_RAD
        val cosHi = cos(inner).toFloat()
        val cosLo = cos(outer).toFloat()

        val count = faceCenterDirs.size / 3
        // Deux passes plutôt qu'une liste : zéro boxing, zéro croissance
        // amortie — ce code tourne sur le fil OpenGL.
        var n = 0
        for (i in 0 until count) {
            val d = faceCenterDirs[3 * i] * eyeDirX +
                faceCenterDirs[3 * i + 1] * eyeDirY +
                faceCenterDirs[3 * i + 2] * eyeDirZ
            if (d in cosLo..cosHi) n++
        }
        val out = IntArray(n)
        var k = 0
        for (i in 0 until count) {
            val d = faceCenterDirs[3 * i] * eyeDirX +
                faceCenterDirs[3 * i + 1] * eyeDirY +
                faceCenterDirs[3 * i + 2] * eyeDirZ
            if (d in cosLo..cosHi) out[k++] = i
        }
        return out
    }

    /**
     * Faut-il resélectionner ? Vrai si le nadir a tourné de plus de
     * [REBUILD_ANGLE_RAD] ou l'altitude varié de plus de [REBUILD_ALT_RATIO].
     * Les seuils valent 1/6 et 1/10 des marges de bande : la sélection reste
     * toujours plus large que ce que la caméra a pu parcourir entre deux
     * reconstructions.
     */
    fun shouldRebuild(
        lastDirX: Float, lastDirY: Float, lastDirZ: Float, lastAltitudeM: Double,
        dirX: Float, dirY: Float, dirZ: Float, altitudeM: Double
    ): Boolean {
        if (lastAltitudeM <= 0.0) return true
        val dot = (lastDirX * dirX + lastDirY * dirY + lastDirZ * dirZ)
            .coerceIn(-1f, 1f)
        if (acos(dot.toDouble()) > REBUILD_ANGLE_RAD) return true
        val ratio = altitudeM / lastAltitudeM
        return ratio > 1.0 + REBUILD_ALT_RATIO || ratio < 1.0 / (1.0 + REBUILD_ALT_RATIO)
    }

    /**
     * Directions unitaires des centres de faces, extraites d'un tampon de
     * sommets entrelacé (positions en tête de chaque sommet, trois sommets
     * consécutifs par face). Calcul fait UNE fois par monde ; ~82 000 faces,
     * quelques millisecondes.
     */
    fun faceCenterDirs(vertexData: FloatArray, floatsPerVertex: Int): FloatArray {
        val faces = vertexData.size / (floatsPerVertex * 3)
        val out = FloatArray(faces * 3)
        for (f in 0 until faces) {
            val base = f * floatsPerVertex * 3
            var x = 0f; var y = 0f; var z = 0f
            for (v in 0 until 3) {
                val o = base + v * floatsPerVertex
                x += vertexData[o]; y += vertexData[o + 1]; z += vertexData[o + 2]
            }
            val len = sqrt(x * x + y * y + z * z)
            if (len > 1e-9f) {
                out[3 * f] = x / len; out[3 * f + 1] = y / len; out[3 * f + 2] = z / len
            }
        }
        return out
    }
}
