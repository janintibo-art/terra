package com.terra.sim

import com.terra.core.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Maillage des arbres — lot 3.3-a.
 *
 * Chaque segment du squelette (lots 3.1 / 3.2) devient un TUBE à N côtés,
 * du rayon de base au rayon de pointe. Ces deux rayons sont déjà dans le
 * squelette, et leur continuité aux raccords est un invariant testé depuis
 * le 3.1 : c'est elle qui fait qu'un tube ne montre aucune marche à la
 * jonction avec son parent, sans qu'aucun code de couture soit nécessaire.
 *
 * ## Deux formes de segment
 *
 * - **courant** : deux anneaux de N sommets reliés par N quadrilatères,
 *   soit 2N triangles.
 * - **terminal** (sans enfant) : l'anneau de pointe se réduit à un POINT,
 *   la branche finit en cône. Ce n'est pas un raffinement esthétique : sans
 *   lui le maillage reste OUVERT, et une pointe de conifère laisse un trou
 *   de 5,9 cm — 41 px vus à deux mètres, à travers lequel l'élagage des
 *   faces arrière donne à voir l'intérieur de la branche
 *   (validation/maillage_arbres.py §2). Et le cône coûte MOINS cher qu'un
 *   tube : N triangles au lieu de 2N.
 *
 * La base du tronc reste ouverte : elle est enfouie dans le terrain.
 *
 * ## Ce que ce lot ne fait pas
 *
 * La dégradation par distance est le lot 3.3-b. L'instruction montre qu'elle
 * n'est pas un confort : un conifère pèse 9 776 triangles à 8 côtés, donc
 * une centaine d'arbres proches dépasserait le rendu entier du terrain.
 *
 * ## Torsion assumée
 *
 * Chaque anneau est bâti dans son propre repère perpendiculaire. Deux
 * segments voisins ne partagent donc pas l'origine des azimuts : les
 * anneaux qui se touchent coïncident en position et en rayon, mais leurs
 * sommets se décalent jusqu'à 22,5° à huit côtés. Sans effet sur la
 * silhouette ni sur un ombrage plat — cela ne se verrait qu'avec une
 * texture continue le long d'une branche. Le transport parallèle du repère
 * le corrigerait, et n'aura de sens que le jour où il y aura des textures.
 */
object TreeMesh {

    /** Côtés par tube. Écart au cercle : 3,8 % de la largeur du tronc. */
    const val DEFAULT_SIDES = 8

    /** Position (3) + normale (3) + couleur (3). */
    const val FLOATS_PER_VERTEX = 9

    /**
     * Construit le maillage, sommets dupliqués et non indexés — même parti
     * que le maillage du globe, et le seul possible ici puisque chaque face
     * porte sa propre normale.
     *
     * Repère local : Y vers le haut, base du tronc à l'origine, en mètres.
     */
    fun build(skeleton: TreeSkeleton, sides: Int = DEFAULT_SIDES): FloatArray {
        require(sides in 3..32) { "sides hors de [3 ; 32] : $sides" }
        val segments = skeleton.segments

        // Un segment est terminal s'il n'a aucun enfant. Le calcul se fait
        // en UNE passe sur les parents : chercher les enfants segment par
        // segment serait quadratique, et un conifère en compte 1 111.
        val hasChild = BooleanArray(segments.size)
        for (s in segments) {
            if (s.parent >= 0) hasChild[s.parent] = true
        }

        var running = 0
        for (i in segments.indices) if (hasChild[i]) running++
        val terminal = segments.size - running
        val vertexCount = running * 6 * sides + terminal * 3 * sides
        val out = FloatArray(vertexCount * FLOATS_PER_VERTEX)

        // Anneaux réutilisés d'un segment à l'autre : ces tableaux sont
        // écrits puis lus dans la foulée, jamais conservés.
        val baseRing = FloatArray(sides * 3)
        val tipRing = FloatArray(sides * 3)
        val ringNormal = FloatArray(sides * 3)

        var o = 0
        for (i in segments.indices) {
            val seg = segments[i]
            val axis = seg.direction()
            if (axis.lengthSq < 1e-12f) continue

            // Repère perpendiculaire : le « plus petit axe cardinal » évite
            // le cas dégénéré où l'auxiliaire serait colinéaire à l'axe.
            val helper = if (abs(axis.y) < 0.9f) Vec3(0f, 1f, 0f) else Vec3(1f, 0f, 0f)
            val u = (axis cross helper).normalized()
            val v = (u cross axis)

            for (k in 0 until sides) {
                val a = 2f * PI_F * k.toFloat() / sides.toFloat()
                val nx = u.x * cos(a) + v.x * sin(a)
                val ny = u.y * cos(a) + v.y * sin(a)
                val nz = u.z * cos(a) + v.z * sin(a)
                ringNormal[3 * k] = nx
                ringNormal[3 * k + 1] = ny
                ringNormal[3 * k + 2] = nz
                baseRing[3 * k] = seg.baseX + nx * seg.radiusBaseM
                baseRing[3 * k + 1] = seg.baseY + ny * seg.radiusBaseM
                baseRing[3 * k + 2] = seg.baseZ + nz * seg.radiusBaseM
                tipRing[3 * k] = seg.tipX + nx * seg.radiusTipM
                tipRing[3 * k + 1] = seg.tipY + ny * seg.radiusTipM
                tipRing[3 * k + 2] = seg.tipZ + nz * seg.radiusTipM
            }

            val cBase = barkColor(seg.depth, seg.radiusBaseM)
            val cTip = barkColor(seg.depth, seg.radiusTipM)

            if (hasChild[i]) {
                for (k in 0 until sides) {
                    val k2 = (k + 1) % sides
                    // Deux triangles par côté, orientés vers l'EXTÉRIEUR
                    // (sens direct vu du dehors) : l'élagage des faces
                    // arrière est actif dans le moteur.
                    o = put(out, o, baseRing, k, ringNormal, k, cBase)
                    o = put(out, o, tipRing, k, ringNormal, k, cTip)
                    o = put(out, o, tipRing, k2, ringNormal, k2, cTip)

                    o = put(out, o, baseRing, k, ringNormal, k, cBase)
                    o = put(out, o, tipRing, k2, ringNormal, k2, cTip)
                    o = put(out, o, baseRing, k2, ringNormal, k2, cBase)
                }
            } else {
                for (k in 0 until sides) {
                    val k2 = (k + 1) % sides
                    o = put(out, o, baseRing, k, ringNormal, k, cBase)
                    // Apex : la normale d'un sommet de cône ne peut pas être
                    // radiale, on reprend l'axe — approximation qui suffit à
                    // un ombrage plat et évite un point noir au bout.
                    o = putXyz(
                        out, o, seg.tipX, seg.tipY, seg.tipZ,
                        axis.x, axis.y, axis.z, cTip
                    )
                    o = put(out, o, baseRing, k2, ringNormal, k2, cBase)
                }
            }
        }

        return if (o == out.size) out else out.copyOf(o)
    }

    /** Sommets attendus, pour dimensionner sans construire. */
    fun vertexCount(skeleton: TreeSkeleton, sides: Int = DEFAULT_SIDES): Int {
        val hasChild = BooleanArray(skeleton.segments.size)
        for (s in skeleton.segments) if (s.parent >= 0) hasChild[s.parent] = true
        var running = 0
        for (b in hasChild) if (b) running++
        val terminal = skeleton.segments.size - running
        return running * 6 * sides + terminal * 3 * sides
    }

    /**
     * Couleur du bois : brun sombre sur les fûts épais, vert de feuillage
     * sur les rameaux fins. Le RAYON commande, pas seulement la profondeur —
     * un palmier n'a qu'un niveau de branches et doit quand même verdir.
     */
    private fun barkColor(depth: Int, radiusM: Float): FloatArray {
        val thin = (1f - (radiusM / 0.06f)).coerceIn(0f, 1f)
        val deep = (depth / 4f).coerceIn(0f, 1f)
        val leaf = (0.55f * thin + 0.45f * deep).coerceIn(0f, 1f)
        return floatArrayOf(
            0.34f * (1f - leaf) + 0.18f * leaf,
            0.24f * (1f - leaf) + 0.46f * leaf,
            0.15f * (1f - leaf) + 0.14f * leaf
        )
    }

    private fun put(
        out: FloatArray, offset: Int,
        ring: FloatArray, ringIndex: Int,
        normals: FloatArray, normalIndex: Int,
        color: FloatArray
    ): Int = putXyz(
        out, offset,
        ring[3 * ringIndex], ring[3 * ringIndex + 1], ring[3 * ringIndex + 2],
        normals[3 * normalIndex], normals[3 * normalIndex + 1], normals[3 * normalIndex + 2],
        color
    )

    private fun putXyz(
        out: FloatArray, offset: Int,
        x: Float, y: Float, z: Float,
        nx: Float, ny: Float, nz: Float,
        color: FloatArray
    ): Int {
        var o = offset
        out[o++] = x; out[o++] = y; out[o++] = z
        out[o++] = nx; out[o++] = ny; out[o++] = nz
        out[o++] = color[0]; out[o++] = color[1]; out[o++] = color[2]
        return o
    }

    private const val PI_F = 3.1415927f
}
