package com.terra.planet

import android.opengl.GLES20
import android.util.Log

/**
 * Recyclage des tampons de sommets — lot 0.10, resté en dette jusqu'ici.
 *
 * ## Pourquoi cela devient nécessaire
 *
 * Le renderer actuel gère un seul tampon, créé une fois. Avec le terrain à
 * tuiles, plusieurs centaines de tampons apparaissent et disparaissent en
 * continu pendant une descente.
 *
 * Créer et détruire un tampon à chaque fois pose deux problèmes. D'abord le
 * coût : `glGenBuffers` et `glDeleteBuffers` forcent une synchronisation avec le
 * pilote, et quelques dizaines par image suffisent à provoquer des à-coups.
 * Ensuite la fragmentation : les pilotes mobiles réutilisent mal la mémoire
 * ainsi libérée, et la consommation dérive à la hausse sur une longue session.
 *
 * ## Comment
 *
 * On conserve les tampons libérés dans des casiers de capacité, arrondie à la
 * puissance de deux supérieure. Une demande de 42 Ko réutilise un tampon de
 * 64 Ko sans réallouer. Le gaspillage moyen atteint vingt-cinq pour cent, ce qui
 * reste largement préférable au coût des allocations : sur les sept mégaoctets
 * du pire cas, cela représente moins de deux mégaoctets.
 *
 * ## Contrainte
 *
 * Toutes les méthodes doivent être appelées **depuis le fil OpenGL**. Un
 * identifiant de tampon n'a aucun sens hors de son contexte, et le contexte
 * n'est courant que sur ce fil.
 */
class GpuBufferPool(private val maxPooledBytes: Long = 24L * 1024 * 1024) {

    /** Casiers de tampons libres, indexés par capacité. */
    private val free = HashMap<Int, ArrayDeque<Int>>()

    /** Capacité réelle de chaque tampon vivant. */
    private val capacity = HashMap<Int, Int>()

    var liveBuffers: Int = 0
        private set

    var pooledBuffers: Int = 0
        private set

    var pooledBytes: Long = 0L
        private set

    /** Nombre de réutilisations, opposé aux créations. Bon indicateur d'efficacité. */
    var reuseCount: Long = 0L
        private set

    var createCount: Long = 0L
        private set

    /**
     * Fournit un tampon d'au moins [sizeBytes] octets, déjà lié à
     * `GL_ARRAY_BUFFER` et dimensionné.
     *
     * @return l'identifiant du tampon, ou 0 en cas d'échec
     */
    fun acquire(sizeBytes: Int): Int {
        if (sizeBytes <= 0) return 0
        val bucket = roundUpToPowerOfTwo(sizeBytes)

        val pooled = free[bucket]
        if (pooled != null && pooled.isNotEmpty()) {
            val id = pooled.removeLast()
            pooledBuffers--
            pooledBytes -= bucket.toLong()
            liveBuffers++
            reuseCount++
            return id
        }

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        val id = ids[0]
        if (id == 0) {
            Log.e(TAG, "glGenBuffers a échoué")
            return 0
        }

        // On alloue à la capacité du casier, pas à la taille demandée : c'est
        // ce qui rend le tampon réutilisable pour toute demande de ce casier.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, bucket, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        capacity[id] = bucket
        liveBuffers++
        createCount++
        return id
    }

    /** Rend un tampon au pool. Il n'est pas détruit, seulement mis de côté. */
    fun release(id: Int) {
        if (id == 0) return
        val bucket = capacity[id]
        if (bucket == null) {
            // Tampon inconnu : il vient d'un autre chemin, on le détruit.
            GLES20.glDeleteBuffers(1, intArrayOf(id), 0)
            return
        }
        liveBuffers--

        // Au-delà du plafond, on préfère rendre la mémoire au système plutôt
        // que de retenir indéfiniment des tampons qu'on ne réutilisera pas.
        if (pooledBytes + bucket > maxPooledBytes) {
            GLES20.glDeleteBuffers(1, intArrayOf(id), 0)
            capacity.remove(id)
            return
        }

        free.getOrPut(bucket) { ArrayDeque() }.addLast(id)
        pooledBuffers++
        pooledBytes += bucket.toLong()
    }

    /**
     * Détruit tout. À appeler lors d'une perte de contexte OpenGL : les
     * identifiants conservés y deviennent caducs, et les réutiliser
     * corromprait le rendu de façon très difficile à diagnostiquer.
     */
    fun disposeAll() {
        val all = capacity.keys.toIntArray()
        if (all.isNotEmpty()) {
            GLES20.glDeleteBuffers(all.size, all, 0)
        }
        free.clear()
        capacity.clear()
        liveBuffers = 0
        pooledBuffers = 0
        pooledBytes = 0L
    }

    /**
     * Oublie tout sans appeler OpenGL.
     *
     * Après une perte de contexte, les tampons ont déjà disparu avec lui :
     * tenter de les détruire viserait des identifiants appartenant désormais au
     * nouveau contexte.
     */
    fun forgetAll() {
        free.clear()
        capacity.clear()
        liveBuffers = 0
        pooledBuffers = 0
        pooledBytes = 0L
    }

    fun summary(): String =
        "$liveBuffers actifs, $pooledBuffers en réserve (${pooledBytes / 1024} Ko), " +
                "$reuseCount réutilisations / $createCount créations"

    companion object {
        private const val TAG = "TerraBufferPool"

        fun roundUpToPowerOfTwo(v: Int): Int {
            if (v <= MIN_BUCKET) return MIN_BUCKET
            var x = v - 1
            x = x or (x shr 1)
            x = x or (x shr 2)
            x = x or (x shr 4)
            x = x or (x shr 8)
            x = x or (x shr 16)
            return x + 1
        }

        /** Plancher : en dessous, le gain de granularité ne vaut pas les casiers. */
        const val MIN_BUCKET = 4096
    }
}
