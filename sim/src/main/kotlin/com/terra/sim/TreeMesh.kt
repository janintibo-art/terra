package com.terra.sim

import com.terra.core.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

    /** Une touffe = un octaèdre : 8 faces, sommets dupliqués. */
    const val OCTAHEDRON_VERTICES = 24

    /**
     * Construit le maillage, sommets dupliqués et non indexés — même parti
     * que le maillage du globe, et le seul possible ici puisque chaque face
     * porte sa propre normale.
     *
     * Repère local : Y vers le haut, base du tronc à l'origine, en mètres.
     */
    fun build(
        skeleton: TreeSkeleton,
        params: TreeParams? = null,
        sides: Int = DEFAULT_SIDES
    ): FloatArray {
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

        // Feuillage (lot 3.3-c) : un octaèdre par segment des derniers
        // niveaux. Le compte se fait AVANT d'allouer, sinon le tampon
        // devrait croître au milieu de la construction.
        val span = params?.foliageDepthSpan ?: 0
        val maxDepth = if (segments.isEmpty()) 0 else segments.maxOf { it.depth }
        val foliageFrom = maxDepth - span + 1
        var clusters = 0
        if (span > 0) {
            for (seg in segments) if (seg.depth >= foliageFrom) clusters++
        }

        val vertexCount = running * 6 * sides + terminal * 3 * sides +
            clusters * OCTAHEDRON_VERTICES
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

        if (span > 0 && params != null) {
            for (seg in segments) {
                if (seg.depth < foliageFrom) continue
                o = emitFoliage(out, o, seg, params)
            }
        }

        return if (o == out.size) out else out.copyOf(o)
    }

    /**
     * Une touffe : un OCTAÈDRE aux trois demi-axes réglables, centré au
     * milieu du rameau qui la porte et orienté sur lui.
     *
     * Pourquoi un octaèdre. Terra n'embarque AUCUNE texture : les cartes de
     * feuilles à découpe alpha, solution habituelle, seraient ici deux
     * rectangles opaques croisés. Le feuillage doit donc être un volume, et
     * l'octaèdre est le meilleur rapport lecture/coût des candidats chiffrés
     * (validation/feuillage.py §1) — le tétraèdre est trop anguleux,
     * l'icosaèdre coûte 2,5× plus cher pour un gain invisible à la distance
     * où l'on regarde un arbre.
     *
     * Les demi-axes sont des multiples de la LONGUEUR du rameau, si bien que
     * les touffes voisines se recouvrent et que la couronne se lit comme une
     * masse plutôt qu'un chapelet de billes (§4). Le conifère les prend très
     * allongés et plats — une palme d'aiguilles, pas un pompon.
     */
    private fun emitFoliage(
        out: FloatArray, offset: Int, seg: TreeSkeleton.Segment, params: TreeParams
    ): Int {
        var o = offset
        val axis = seg.direction()
        val len = seg.lengthM()
        if (len < 1e-6f || axis.lengthSq < 1e-12f) return o

        val helper = if (abs(axis.y) < 0.9f) Vec3(0f, 1f, 0f) else Vec3(1f, 0f, 0f)
        val wide = (axis cross helper).normalized()
        val thick = (wide cross axis)

        val cx = (seg.baseX + seg.tipX) * 0.5f
        val cy = (seg.baseY + seg.tipY) * 0.5f
        val cz = (seg.baseZ + seg.tipZ) * 0.5f

        val a = axis * (len * params.foliageLengthRatio)
        val b = wide * (len * params.foliageWidthRatio)
        val c = thick * (len * params.foliageThicknessRatio)
        val color = floatArrayOf(params.foliageRed, params.foliageGreen, params.foliageBlue)

        // Six pôles de l'octaèdre.
        val px = floatArrayOf(cx + a.x, cx - a.x, cx + b.x, cx - b.x, cx + c.x, cx - c.x)
        val py = floatArrayOf(cy + a.y, cy - a.y, cy + b.y, cy - b.y, cy + c.y, cy - c.y)
        val pz = floatArrayOf(cz + a.z, cz - a.z, cz + b.z, cz - b.z, cz + c.z, cz - c.z)

        // Huit faces, chacune reliant un pôle « long », un pôle « large » et
        // un pôle « épais ». L'ordre des sommets suit le signe du produit
        // des trois demi-axes, pour que la normale sorte du volume.
        for (i in 0 until 2) {
            for (j in 2 until 4) {
                for (k in 4 until 6) {
                    // Le sens de parcours s'inverse quand un nombre IMPAIR
                    // de demi-axes est pris du côté négatif : sans cela une
                    // face sur deux regarderait vers l'intérieur. Vérifié
                    // numériquement (0 face retournée sur 8) avant d'être
                    // écrit, puis gardé par un test.
                    val flip = ((i == 1).toInt() + (j == 3).toInt() + (k == 5).toInt()) % 2 == 1
                    val v1 = if (flip) k else j
                    val v2 = if (flip) j else k
                    val nx = crossX(px, py, pz, i, v1, v2)
                    val ny = crossY(px, py, pz, i, v1, v2)
                    val nz = crossZ(px, py, pz, i, v1, v2)
                    val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12f)
                    o = putXyz(out, o, px[i], py[i], pz[i], nx / nl, ny / nl, nz / nl, color)
                    o = putXyz(out, o, px[v1], py[v1], pz[v1], nx / nl, ny / nl, nz / nl, color)
                    o = putXyz(out, o, px[v2], py[v2], pz[v2], nx / nl, ny / nl, nz / nl, color)
                }
            }
        }
        return o
    }

    private fun Boolean.toInt(): Int = if (this) 1 else 0

    private fun crossX(x: FloatArray, y: FloatArray, z: FloatArray, a: Int, b: Int, c: Int) =
        (y[b] - y[a]) * (z[c] - z[a]) - (z[b] - z[a]) * (y[c] - y[a])

    private fun crossY(x: FloatArray, y: FloatArray, z: FloatArray, a: Int, b: Int, c: Int) =
        (z[b] - z[a]) * (x[c] - x[a]) - (x[b] - x[a]) * (z[c] - z[a])

    private fun crossZ(x: FloatArray, y: FloatArray, z: FloatArray, a: Int, b: Int, c: Int) =
        (x[b] - x[a]) * (y[c] - y[a]) - (y[b] - y[a]) * (x[c] - x[a])

    /** Sommets attendus, pour dimensionner sans construire. */
    fun vertexCount(
        skeleton: TreeSkeleton,
        params: TreeParams? = null,
        sides: Int = DEFAULT_SIDES
    ): Int {
        val hasChild = BooleanArray(skeleton.segments.size)
        for (s in skeleton.segments) if (s.parent >= 0) hasChild[s.parent] = true
        var running = 0
        for (b in hasChild) if (b) running++
        val terminal = skeleton.segments.size - running
        val span = params?.foliageDepthSpan ?: 0
        var clusters = 0
        if (span > 0 && skeleton.segments.isNotEmpty()) {
            val maxDepth = skeleton.segments.maxOf { it.depth }
            for (seg in skeleton.segments) if (seg.depth >= maxDepth - span + 1) clusters++
        }
        return running * 6 * sides + terminal * 3 * sides +
            clusters * OCTAHEDRON_VERTICES
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
