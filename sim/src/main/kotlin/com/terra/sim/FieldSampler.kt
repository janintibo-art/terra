package com.terra.sim

import com.terra.core.Vec3

/**
 * Évaluation continue d'un champ défini par sommet de la grille — le pont
 * entre les champs simulés (par cellule) et le terrain (une fonction).
 *
 * ## Pourquoi cette classe existe
 *
 * L'invariant n°3 du projet exige que le terrain fin et la grille grossière
 * soient la même fonction. Tant que l'altitude n'était que du bruit, c'était
 * gratuit : le bruit s'évalue en tout point. Le relief tectonique (lot 1.6),
 * lui, naît d'un calcul **sur la grille** — et l'érosion, l'hydrologie, le
 * climat avancé suivront le même chemin. Il faut donc savoir évaluer un champ
 * de grille en tout point, continûment, et exactement aux sommets.
 *
 * ## La construction, et pourquoi elle est prouvable
 *
 * Coordonnées barycentriques par déterminants : pour un point p et un
 * triangle (A, B, C), les poids sont les volumes signés `det(p,B,C)`,
 * `det(A,p,C)`, `det(A,B,p)`, normalisés. Trois propriétés en découlent **par
 * construction**, chacune verrouillée par un test :
 *
 *  - exactitude aux sommets : en p = A, les deux autres déterminants
 *    contiennent deux fois le même vecteur, donc s'annulent ;
 *  - continuité à travers une arête : sur AB, le poids de C s'annule et les
 *    deux poids restants ne dépendent que de A, B et p — identiques dans les
 *    deux triangles qui partagent l'arête ;
 *  - partition de l'unité : un champ constant s'interpole en lui-même.
 *
 * ## Localisation
 *
 * Le triangle contenant p est cherché parmi ceux qui entourent le sommet le
 * plus proche (descente sur le graphe d'adjacence, avec indice de départ
 * comme le [CoarseSampler]). Aux limites numériques — p exactement sur une
 * arête du bord de l'éventail — aucun triangle ne peut contenir p à epsilon
 * près : on prend alors le moins mauvais et l'on tronque les poids négatifs
 * avant de renormaliser, ce qui coïncide avec la valeur du triangle voisin
 * sur l'arête partagée : la continuité survit au cas limite.
 *
 * Sans état mutable : plusieurs fils peuvent échantillonner en parallèle.
 */
class FieldSampler(private val sphere: Icosphere) {

    private val adjacency: Array<IntArray> = sphere.buildAdjacency()

    /** Pour chaque sommet, les triangles incidents (indice = position/3 dans faces). */
    private val vertexFaces: Array<IntArray>

    init {
        val counts = IntArray(sphere.vertexCount)
        val f = sphere.faces
        var i = 0
        while (i < f.size) {
            counts[f[i]]++; counts[f[i + 1]]++; counts[f[i + 2]]++
            i += 3
        }
        val lists = Array(sphere.vertexCount) { IntArray(counts[it]) }
        val fill = IntArray(sphere.vertexCount)
        i = 0
        while (i < f.size) {
            val t = i / 3
            val a = f[i]; val b = f[i + 1]; val c = f[i + 2]
            lists[a][fill[a]++] = t
            lists[b][fill[b]++] = t
            lists[c][fill[c]++] = t
            i += 3
        }
        vertexFaces = lists
    }

    /** Sommet de la grille le plus proche, par descente depuis [hint]. */
    fun nearestVertex(p: Vec3, hint: Int): Int {
        val verts = sphere.vertices
        var best = if (hint in verts.indices) hint else 0
        var bestDot = dot(verts[best], p)
        while (true) {
            var improved = false
            for (n in adjacency[best]) {
                val d = dot(verts[n], p)
                if (d > bestDot) { bestDot = d; best = n; improved = true }
            }
            if (!improved) return best
        }
    }

    /**
     * Valeur du champ en p. [hint] accélère la localisation (indice du dernier
     * sommet trouvé) ; il n'influe jamais sur le résultat, seulement sur le
     * temps — un test le vérifie.
     */
    fun sample(field: FloatArray, p: Vec3, hint: Int = 0): Float {
        val v = nearestVertex(p, hint)
        val verts = sphere.vertices
        val f = sphere.faces

        // Coïncidence bit à bit avec un sommet de la grille : la valeur du
        // sommet, EXACTEMENT. C'est ce qui transporte l'invariant n°3 — la
        // grille et la fonction rendent le même nombre, pas deux nombres à
        // un epsilon d'interpolation près.
        val vv = verts[v]
        if (p.x == vv.x && p.y == vv.y && p.z == vv.z) return field[v]

        // Meilleur triangle : celui dont le plus petit déterminant est le plus
        // grand — positif s'il contient p, à peine négatif au pire cas limite.
        var bestTri = -1
        var bestMin = Float.NEGATIVE_INFINITY
        var wA = 0f; var wB = 0f; var wC = 0f
        for (t in vertexFaces[v]) {
            val a = verts[f[t * 3]]
            val b = verts[f[t * 3 + 1]]
            val c = verts[f[t * 3 + 2]]
            val dA = det(p, b, c)
            val dB = det(a, p, c)
            val dC = det(a, b, p)
            val mn = minOf(dA, dB, dC)
            if (mn > bestMin) {
                bestMin = mn; bestTri = t
                wA = dA; wB = dB; wC = dC
            }
            if (mn >= 0f) break   // contenant strict : inutile de chercher mieux
        }

        // Troncature des poids négatifs (cas limite de bord d'éventail) puis
        // normalisation — voir le commentaire de classe pour la continuité.
        if (wA < 0f) wA = 0f
        if (wB < 0f) wB = 0f
        if (wC < 0f) wC = 0f
        val sum = wA + wB + wC
        if (sum <= 0f || bestTri < 0) return field[v]

        val ia = f[bestTri * 3]
        val ib = f[bestTri * 3 + 1]
        val ic = f[bestTri * 3 + 2]
        return (field[ia] * wA + field[ib] * wB + field[ic] * wC) / sum
    }

    /**
     * Variante avec indice de départ mutable : `holder[0]` est lu puis mis à
     * jour avec le sommet trouvé. Un tableau d'une case par appelant (ou par
     * fil) suffit à rendre la descente quasi constante sur des requêtes
     * spatialement cohérentes, sans partager le moindre état entre fils.
     */
    fun sample(field: FloatArray, p: Vec3, hintHolder: IntArray): Float {
        val v = nearestVertex(p, hintHolder[0])
        hintHolder[0] = v
        return sample(field, p, v)
    }

    private fun dot(a: Vec3, b: Vec3): Float = a.x * b.x + a.y * b.y + a.z * b.z

    /** Volume signé du trièdre (a, b, c) : le produit mixte a · (b × c). */
    private fun det(a: Vec3, b: Vec3, c: Vec3): Float =
        a.x * (b.y * c.z - b.z * c.y) +
                a.y * (b.z * c.x - b.x * c.z) +
                a.z * (b.x * c.y - b.y * c.x)
}
