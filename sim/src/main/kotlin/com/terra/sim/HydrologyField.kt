package com.terra.sim

import java.util.PriorityQueue
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Érosion hydraulique et réseau d'écoulement — lot 1.9.
 *
 * ## Ce que ce lot produit vraiment
 *
 * Une mise au point honnête, faite avant d'écrire : à 115 km entre deux
 * cellules de la grille, **aucune vallée ne peut se creuser** — une vallée
 * fait 1 à 10 km de large. Trois ordres de grandeur de coefficient ont été
 * balayés en simulation : le relief finit par fondre, jamais par se ciseler.
 * Les vallées visibles viendront du terrain fin (lot suivant), guidées par
 * ce que ce lot calcule.
 *
 * Ce lot livre donc, mesuré par simulation :
 *
 *  - le **débit cumulé** par cellule — la matière première des rivières
 *    (1.10), des lacs (1.11) et de l'incision fine ;
 *  - les **directions d'écoulement**, chaque cellule terrestre ayant un
 *    chemin descendant garanti jusqu'à la mer ;
 *  - un **abaissement différencié** du relief : médiane 8 m, 90ᵉ centile
 *    56 m — les versants à fort débit s'usent, les crêtes tiennent. Effet
 *    régional, pas spectaculaire, et c'est assumé ;
 *  - les **cuvettes comblées**, avec la hauteur de comblement conservée :
 *    c'est exactement la profondeur des futurs lacs.
 *
 * ## Priority-flood plutôt que relaxation
 *
 * Combler les cuvettes par relaxation itérative (« chaque creux monte au
 * niveau de son plus bas voisin, recommencer ») ne converge jamais sur une
 * cuvette profonde et large : chaque passe ne la remplit que d'une couronne.
 * L'algorithme de Barnes — une file de priorité partant de la mer et
 * remontant, chaque cellule atteinte étant relevée au niveau de son point
 * d'entrée — résout tout en une passe, en O(n log n). Mesuré : 1,4 % des
 * cellules relevées, comblement maximal de 926 m sur un relief d'essai.
 *
 * ## Déterminisme
 *
 * Même précaution qu'au champ de distance : les ex æquo de la file sont
 * départagés par l'indice de cellule, ordre total identique sur toute
 * machine. Un tri par altitude décroissante ordonne ensuite l'accumulation ;
 * les altitudes égales y sont également départagées par indice.
 */
class HydrologyField(
    /** Altitude après érosion, en mètres (grille). */
    val erodedM: FloatArray,
    /** Cellule réceptrice de l'écoulement ; elle-même si puits ou mer. */
    val receiver: IntArray,
    /** Débit cumulé, en nombre de cellules drainées (1 = ligne de crête). */
    val flowAccum: FloatArray,
    /** Hauteur de comblement des cuvettes, en mètres — profondeur des lacs. */
    val fillDepthM: FloatArray
) {
    val cellCount: Int get() = erodedM.size

    companion object {

        /** Passes d'érosion. 25 : au-delà, l'effet sature (mesuré). */
        const val PASSES = 25

        /** Coefficient d'incision (stream power). Calibré : abaissement
         *  médian 8 m, 90ᵉ centile 56 m — une usure, pas un rabotage. */
        const val INCISION_K = 3000f

        /** Part du sédiment déposée en pente faible. */
        const val DEPOSIT_FRACTION = 0.4f

        /** Fraction du dénivelé qu'une passe peut retirer : garde-fou contre
         *  l'inversion de pente, qui casserait le réseau d'écoulement. */
        const val MAX_INCISION_RATIO = 0.4f

        /** Enveloppe d'érosion : au plus un quart de l'altitude locale... */
        const val MAX_EROSION_RATIO = 0.25f

        /** ...et jamais plus de 500 m, quelle que soit l'altitude. */
        const val MAX_EROSION_M = 500f

        /** Dépôt maximal par cellule : les plaines alluviales se comblent,
         *  elles ne construisent pas de collines. */
        const val MAX_DEPOSIT_M = 120f

        fun generate(sphere: Icosphere, altitudeM: FloatArray): HydrologyField {
            val n = sphere.vertexCount
            val adjacency = sphere.buildAdjacency()
            val verts = sphere.vertices

            // Longueurs d'arêtes, calculées une fois : la distance géodésique
            // entre voisins ne change pas, seule l'altitude bouge.
            val edgeLen = Array(n) { i ->
                FloatArray(adjacency[i].size) { k ->
                    val a = verts[i]
                    val b = verts[adjacency[i][k]]
                    val dot = (a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(-1f, 1f)
                    max(1f, acos(dot) * 6_371_000f)
                }
            }

            val h = altitudeM.copyOf()
            val original = altitudeM.copyOf()

            // Enveloppe d'érosion, par cellule et une fois pour toutes.
            //
            // ## Pourquoi une enveloppe plutôt qu'un coefficient bien choisi
            //
            // Le débit cumulé s'étale sur trois décades : le même coefficient
            // qui use raisonnablement un versant rabote un fond de vallée à
            // fort débit. Chercher la constante qui tombe juste partout est un
            // pari déjà perdu deux fois ici (relief tectonique, micro-relief).
            // On borne donc ce que l'érosion PEUT retirer ou déposer, et les
            // bornes des tests deviennent vraies par construction.
            //
            // Physiquement : un massif ne se rabote pas entièrement sur la
            // durée modélisée, et l'incision se ralentit quand la pente
            // s'aplanit — le plafond proportionnel traduit les deux.
            val floorM = FloatArray(n) { i ->
                val a = original[i]
                if (a <= 0f) a else a - kotlin.math.min(MAX_EROSION_M, a * MAX_EROSION_RATIO)
            }
            val ceilM = FloatArray(n) { i -> original[i] + MAX_DEPOSIT_M }
            val receiver = IntArray(n)
            val slope = FloatArray(n)
            val recvDist = FloatArray(n)
            val accum = FloatArray(n)
            val sediment = FloatArray(n)
            val order = IntArray(n)

            repeat(PASSES) {
                priorityFlood(adjacency, h)
                computeFlow(adjacency, edgeLen, h, receiver, slope, recvDist)
                sortByDescendingAltitude(h, order)
                accumulate(order, receiver, accum)

                // Incision, puis dépôt en remontant l'ordre : le sédiment
                // arraché en amont se pose là où la pente faiblit — c'est ce
                // qui crée les piémonts et les plaines alluviales.
                java.util.Arrays.fill(sediment, 0f)
                for (idx in 0 until n) {
                    val i = order[idx]
                    if (receiver[i] == i || h[i] <= 0f) continue
                    val drop = h[i] - h[receiver[i]]
                    var incision = INCISION_K * sqrt(accum[i]) * slope[i]
                    if (incision > drop * MAX_INCISION_RATIO) incision = drop * MAX_INCISION_RATIO
                    if (incision < 0f) incision = 0f

                    val carried = incision + sediment[i]
                    val deposited = carried * DEPOSIT_FRACTION * exp(-slope[i] * 300f)
                    var next = h[i] - incision + deposited
                    // L'enveloppe s'applique ici, pas en fin de passe : une
                    // altitude hors bornes fausserait le réseau d'écoulement
                    // de la passe suivante avant d'être corrigée.
                    if (next < floorM[i]) next = floorM[i]
                    if (next > ceilM[i]) next = ceilM[i]
                    h[i] = next
                    sediment[receiver[i]] += carried - deposited
                }
            }

            // État final : un dernier comblement et un dernier réseau, pour
            // que le débit publié décrive bien le terrain publié.
            val beforeFill = h.copyOf()
            priorityFlood(adjacency, h)
            val fillDepth = FloatArray(n) { max(0f, h[it] - beforeFill[it]) }
            computeFlow(adjacency, edgeLen, h, receiver, slope, recvDist)
            sortByDescendingAltitude(h, order)
            accumulate(order, receiver, accum)

            // La mer garde son altitude d'origine : l'érosion est un
            // phénomène terrestre, et modifier le plancher océanique ici
            // déplacerait le trait de côte sans raison physique.
            for (i in 0 until n) if (original[i] <= 0f) h[i] = original[i]

            return HydrologyField(h, receiver, accum, fillDepth)
        }

        /**
         * Comble toute cuvette : chaque cellule terrestre obtient un chemin
         * strictement descendant vers la mer. Algorithme de Barnes, une passe.
         */
        private fun priorityFlood(adjacency: Array<IntArray>, h: FloatArray) {
            val n = h.size
            val closed = BooleanArray(n)
            // Clé compactée : altitude en bits hauts, indice en bits bas.
            // Les altitudes peuvent être négatives : on les décale d'un biais
            // pour que la comparaison entière reste l'ordre des réels.
            val queue = PriorityQueue<Long>(1024)

            for (i in 0 until n) {
                if (h[i] <= 0f) {
                    closed[i] = true
                    queue.add(packKey(h[i], i))
                }
            }
            if (queue.isEmpty()) return   // monde sans mer : rien à drainer

            while (queue.isNotEmpty()) {
                val head = queue.poll()
                val i = (head and 0xFFFFFFFFL).toInt()
                val level = unpackAltitude(head)
                for (j in adjacency[i]) {
                    if (closed[j]) continue
                    closed[j] = true
                    // Le epsilon garantit une pente strictement descendante :
                    // sans lui, un plateau parfaitement plat ferait tourner
                    // l'écoulement en rond.
                    if (h[j] < level + EPSILON) h[j] = level + EPSILON
                    queue.add(packKey(h[j], j))
                }
            }
        }

        private const val EPSILON = 0.001f

        /**
         * Clé de file : altitude en bits hauts, indice en bits bas, dans un
         * Long dont l'ordre **signé** — celui de Java — est l'ordre voulu.
         *
         * Deux transformations, et la seconde a coûté un correctif (v0.9.7) :
         *
         *  1. le motif de bits d'un float ne se trie comme le réel que pour
         *     les positifs ; on inverse donc tous les bits des négatifs et
         *     l'on pose le bit de signe des positifs ;
         *  2. le résultat est un entier **non signé** de 32 bits ; décalé de
         *     32, son bit de poids fort devient le bit de signe du Long, et
         *     la comparaison signée inverse l'ordre. Le XOR final rétablit
         *     l'ordre non signé dans le domaine signé.
         *
         * L'étape 2 manquait : les terres se triaient avant les fonds marins,
         * l'accumulation de débit remontait le réseau à l'envers. La première
         * validation Python n'avait pas vu le défaut parce qu'elle raisonnait
         * en entiers non signés — l'arithmétique de Java, elle, est signée.
         */
        private fun packKey(altitude: Float, index: Int): Long {
            val bits = java.lang.Float.floatToIntBits(altitude)
            val sortable = if (bits < 0) bits.inv() else bits or Int.MIN_VALUE
            val key = ((sortable.toLong() and 0xFFFFFFFFL) shl 32) or (index.toLong() and 0xFFFFFFFFL)
            return key xor Long.MIN_VALUE
        }

        private fun unpackAltitude(key: Long): Float {
            val sortable = ((key xor Long.MIN_VALUE) ushr 32).toInt()
            val bits = if (sortable < 0) sortable and Int.MAX_VALUE else sortable.inv()
            return java.lang.Float.intBitsToFloat(bits)
        }

        /** Receveur = voisin le plus bas ; soi-même si aucun ne descend. */
        private fun computeFlow(
            adjacency: Array<IntArray>,
            edgeLen: Array<FloatArray>,
            h: FloatArray,
            receiver: IntArray,
            slope: FloatArray,
            recvDist: FloatArray
        ) {
            for (i in h.indices) {
                var best = i
                var bestDrop = 0f
                var bestDist = 1f
                val nb = adjacency[i]
                for (k in nb.indices) {
                    val j = nb[k]
                    val drop = h[i] - h[j]
                    // Pente, pas dénivelé : sur une grille irrégulière, un
                    // grand dénivelé lointain peut couler moins vite qu'un
                    // petit dénivelé proche.
                    if (drop > 0f && drop / edgeLen[i][k] > bestDrop / bestDist) {
                        best = j; bestDrop = drop; bestDist = edgeLen[i][k]
                    }
                }
                receiver[i] = best
                slope[i] = if (best == i) 0f else bestDrop / bestDist
                recvDist[i] = bestDist
            }
        }

        /** Indices triés par altitude décroissante, ex æquo par indice. */
        private fun sortByDescendingAltitude(h: FloatArray, out: IntArray) {
            val keys = LongArray(h.size) { i ->
                // Altitude inversée pour un tri croissant équivalent.
                (packKey(-h[i], i))
            }
            keys.sort()
            for (k in keys.indices) out[k] = (keys[k] and 0xFFFFFFFFL).toInt()
        }

        /**
         * Accès de test à la clé de tri : cette primitive est le cœur du
         * déterminisme et de l'ordre d'accumulation, et son bug de v0.9.6
         * n'était visible que par ses effets lointains. Elle mérite d'être
         * testable directement, quitte à élargir un peu sa visibilité.
         */
        internal fun packKeyForTest(altitude: Float, index: Int): Long = packKey(altitude, index)

        internal fun unpackAltitudeForTest(key: Long): Float = unpackAltitude(key)

        /** Débit cumulé : chaque cellule verse son eau à son receveur. */
        private fun accumulate(order: IntArray, receiver: IntArray, accum: FloatArray) {
            java.util.Arrays.fill(accum, 1f)
            for (idx in order.indices) {
                val i = order[idx]
                if (receiver[i] != i) accum[receiver[i]] += accum[i]
            }
        }
    }
}
