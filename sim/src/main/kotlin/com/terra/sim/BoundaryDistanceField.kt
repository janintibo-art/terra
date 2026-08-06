package com.terra.sim

import com.terra.core.Vec3
import java.util.PriorityQueue
import kotlin.math.acos

/**
 * Distance de chaque cellule aux frontières de plaques, par type — lot 1.8,
 * remonté avant le 1.6 dont il est le prérequis.
 *
 * ## Ce que le champ transporte
 *
 * Pour chaque type de frontière (convergente, divergente, transformante) et
 * chaque sommet : la distance géodésique de graphe à la frontière la plus
 * proche de ce type, **et le caractère de cette frontière** — son intensité
 * (vitesse relative) et, pour les convergentes, la nature des croûtes en
 * présence. C'est tout ce qu'il faut au lot 1.6 : une chaîne de collision
 * continentale ne se dessine pas comme une fosse de subduction, et l'ampleur
 * du relief suivra la vitesse.
 *
 * ## Déterminisme du Dijkstra
 *
 * Deux sources à égale distance d'un sommet lui transmettraient des
 * caractères différents selon l'ordre d'extraction : la file de priorité
 * départage donc les ex æquo par l'indice du sommet — un ordre **total**, le
 * même sur toute machine. Sans cela, le relief du lot 1.6 aurait pu différer
 * d'un appareil à l'autre le long des médiatrices entre frontières, une
 * rupture de déterminisme presque impossible à débusquer.
 *
 * ## Résolution
 *
 * Les sources sont les sommets des arêtes de frontière, à distance zéro : la
 * position réelle de la frontière est connue à une demi-arête près (~115 km
 * au niveau 5). Suffisant pour des reliefs dont les demi-largeurs se
 * comptent en centaines de kilomètres ; le bruit d'habillage fera le reste.
 */
class BoundaryDistanceField(
    /** Distance angulaire (radians) à la frontière la plus proche, par type.
     *  [Float.MAX_VALUE] si aucune frontière de ce type n'existe. */
    val distConvergent: FloatArray,
    val distDivergent: FloatArray,
    val distTransform: FloatArray,
    /** Vitesse relative (rad/Ma) de la frontière qui a fourni la distance. */
    val intensityConvergent: FloatArray,
    val intensityDivergent: FloatArray,
    /** Croûtes en présence à la convergente la plus proche. */
    val contextConvergent: ByteArray,
    /**
     * Plaque **chevauchante** de la convergente la plus proche : celle qui
     * porte la cordillère ou l'arc, l'autre portant la fosse. Convention :
     * la continentale en subduction océan-continent ; la plus petite par
     * identifiant sinon — arbitraire mais déterministe, et documentée pour
     * que personne ne cherche une physique qui n'y est pas.
     */
    val upperConvergent: ByteArray
) {
    companion object {

        /** Convergence entre deux croûtes continentales : chaîne de collision. */
        const val CRUST_CC: Byte = 0

        /** Océanique contre continentale : cordillère et fosse. */
        const val CRUST_OC: Byte = 1

        /** Océanique contre océanique : arc insulaire et fosse. */
        const val CRUST_OO: Byte = 2

        fun generate(sphere: Icosphere, plates: PlateSet, boundaries: BoundarySet): BoundaryDistanceField {
            val adjacency = sphere.buildAdjacency()
            val verts = sphere.vertices
            val n = sphere.vertexCount

            val dConv = FloatArray(n) { Float.MAX_VALUE }
            val upConv = ByteArray(n)
            val dDiv = FloatArray(n) { Float.MAX_VALUE }
            val dTrans = FloatArray(n) { Float.MAX_VALUE }
            val iConv = FloatArray(n)
            val iDiv = FloatArray(n)
            val ctxConv = ByteArray(n)

            // Sources par type : sommets d'arêtes de frontière, avec caractère.
            val convSeeds = ArrayList<Int>()
            val convInt = ArrayList<Float>()
            val convCtx = ArrayList<Byte>()
            val convUp = ArrayList<Byte>()
            val divSeeds = ArrayList<Int>()
            val divInt = ArrayList<Float>()
            val transSeeds = ArrayList<Int>()

            for (i in 0 until boundaries.edgeCount) {
                val a = boundaries.edgeA[i]
                val b = boundaries.edgeB[i]
                val speed = boundaries.relSpeed[i]
                when (boundaries.edgeType[i].toInt()) {
                    BoundaryType.CONVERGENT.ordinal -> {
                        val oa = plates.plateOf(a).oceanic
                        val ob = plates.plateOf(b).oceanic
                        val ctx = when {
                            oa && ob -> CRUST_OO
                            oa || ob -> CRUST_OC
                            else -> CRUST_CC
                        }
                        val pa = plates.plateId[a]
                        val pb = plates.plateId[b]
                        val upper: Byte = when {
                            oa && !ob -> pb.toByte()      // la continentale chevauche
                            ob && !oa -> pa.toByte()
                            else -> minOf(pa, pb).toByte() // convention déterministe
                        }
                        convSeeds.add(a); convInt.add(speed); convCtx.add(ctx); convUp.add(upper)
                        convSeeds.add(b); convInt.add(speed); convCtx.add(ctx); convUp.add(upper)
                    }
                    BoundaryType.DIVERGENT.ordinal -> {
                        divSeeds.add(a); divInt.add(speed)
                        divSeeds.add(b); divInt.add(speed)
                    }
                    else -> {
                        transSeeds.add(a); transSeeds.add(b)
                    }
                }
            }

            dijkstra(adjacency, verts, convSeeds, convInt, convCtx, convUp, dConv, iConv, ctxConv, upConv)
            dijkstra(adjacency, verts, divSeeds, divInt, null, null, dDiv, iDiv, null, null)
            dijkstra(adjacency, verts, transSeeds, null, null, null, dTrans, null, null, null)

            return BoundaryDistanceField(dConv, dDiv, dTrans, iConv, iDiv, ctxConv, upConv)
        }

        /**
         * Dijkstra multi-source sur le graphe de la grille, poids = distance
         * angulaire des arêtes, ex æquo départagés par indice de sommet pour
         * un ordre total déterministe.
         */
        private fun dijkstra(
            adjacency: Array<IntArray>,
            verts: Array<Vec3>,
            seeds: List<Int>,
            seedIntensity: List<Float>?,
            seedContext: List<Byte>?,
            seedUpper: List<Byte>?,
            outDist: FloatArray,
            outIntensity: FloatArray?,
            outContext: ByteArray?,
            outUpper: ByteArray?
        ) {
            if (seeds.isEmpty()) return

            // Clé compactée dans un Long : distance en bits hauts (un float
            // positif se trie comme son motif de bits), indice de sommet en
            // bits bas. L'ordre naturel des Long donne ainsi l'ordre TOTAL
            // (distance puis indice) qui rend les ex æquo déterministes.
            val queue = PriorityQueue<Long>(seeds.size * 4)

            fun pack(dist: Float, node: Int): Long =
                (java.lang.Float.floatToIntBits(dist).toLong() shl 32) or node.toLong()

            for (k in seeds.indices) {
                val s = seeds[k]
                if (0f < outDist[s]) {
                    outDist[s] = 0f
                    outIntensity?.set(s, seedIntensity!![k])
                    outContext?.set(s, seedContext!![k])
                    outUpper?.set(s, seedUpper!![k])
                    queue.add(pack(0f, s))
                } else if (outIntensity != null) {
                    // Sommet déjà source : une frontière plus rapide au même
                    // endroit impose son caractère — règle déterministe car
                    // les arêtes sont parcourues dans un ordre fixe.
                    if (seedIntensity!![k] > outIntensity[s]) {
                        outIntensity[s] = seedIntensity[k]
                        outContext?.set(s, seedContext!![k])
                        outUpper?.set(s, seedUpper!![k])
                    }
                }
            }

            while (queue.isNotEmpty()) {
                val head = queue.poll()
                val node = (head and 0xFFFFFFFFL).toInt()
                val dist = java.lang.Float.intBitsToFloat((head ushr 32).toInt())
                if (dist > outDist[node]) continue   // entrée périmée

                val vn = verts[node]
                for (m in adjacency[node]) {
                    val vm = verts[m]
                    val dot = (vn.x * vm.x + vn.y * vm.y + vn.z * vm.z).coerceIn(-1f, 1f)
                    val next = dist + acos(dot)
                    if (next < outDist[m]) {
                        outDist[m] = next
                        if (outIntensity != null) outIntensity[m] = outIntensity[node]
                        if (outContext != null) outContext[m] = outContext[node]
                        if (outUpper != null) outUpper[m] = outUpper[node]
                        queue.add(pack(next, m))
                    }
                }
            }
        }
    }
}
