package com.terra.planet

import android.opengl.GLES20
import com.terra.sim.TileId
import com.terra.sim.TileMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Flux de tuiles : du sélecteur à l'écran — lot B.
 *
 * ## Répartition entre fils d'exécution
 *
 * Trois fils se partagent le travail, avec une frontière stricte :
 *
 *  - le **fil OpenGL** possède le cache, décide des demandes, téléverse et
 *    dessine. Toutes les méthodes de cette classe, sauf mention contraire,
 *    lui sont réservées ;
 *  - les **fils du pool** maillent les tuiles ([TileMesh], calcul pur) et
 *    déposent le résultat dans une file concurrente — c'est leur seul point
 *    de contact ;
 *  - le fil d'interface ne touche à rien ici.
 *
 * ## Budget de téléversement
 *
 * Un téléversement de tuile coûte environ 77 Ko de copie vers le pilote. En
 * téléverser vingt dans une même image provoquerait un à-coup visible ; on
 * plafonne donc par image, et les tuiles excédentaires attendent l'image
 * suivante — le repli sur l'ancêtre masque l'attente.
 *
 * ## Repli sur l'ancêtre
 *
 * Tant qu'une tuile demandée n'est pas prête, on affiche à sa place l'ancêtre
 * prêt le plus profond. Règle d'or : **jamais deux tuiles superposées**. Si un
 * ancêtre est affiché, aucun de ses descendants ne l'est, sinon les deux
 * surfaces, presque confondues, scintilleraient en se disputant le tampon de
 * profondeur. D'où la résolution en deux passes dans [resolveDrawSet].
 */
class TileStream(private val gpu: GpuBufferPool) {

    /** Une tuile prête à être dessinée. */
    class GpuTile(
        val key: Long,
        val vbo: Int,
        val vertexCount: Int,
        val centerXM: Double,
        val centerYM: Double,
        val centerZM: Double
    ) {
        var lastUsedFrame: Long = 0L
    }

    private class Pending(val mesh: TileMesh, val epoch: Int)

    /** Maillages terminés par les fils du pool, en attente de téléversement. */
    private val ready = ConcurrentLinkedQueue<Pending>()

    /** Tuiles vivantes sur le GPU. Fil OpenGL uniquement. */
    private val cache = HashMap<Long, GpuTile>()

    /**
     * Époque des maillages acceptés.
     *
     * ## La course que ce champ ferme
     *
     * Au changement de monde, `cancelAll` n'atteint pas un maillage déjà en
     * cours : le fil de travail le termine et le dépose **après** le vidage du
     * cache. Les clés de tuiles étant les mêmes d'un monde à l'autre, ce
     * retardataire serait téléversé tel quel — un lambeau de l'ancien terrain
     * incrusté dans le nouveau, le genre de bug qu'on met une soirée à
     * reproduire. Chaque dépôt porte donc l'époque de son monde, vérifiée au
     * dépôt et revérifiée au téléversement.
     */
    @Volatile var acceptEpoch: Int = -1

    val cachedCount: Int get() = cache.size
    val awaitingUpload: Int get() = ready.size

    /** Dépose un maillage terminé. Seule méthode appelée depuis le pool. */
    fun offer(mesh: TileMesh, epoch: Int) {
        if (epoch != acceptEpoch) return
        ready.add(Pending(mesh, epoch))
    }

    /**
     * Vrai si la tuile est déjà sur le GPU.
     *
     * Le suivi des demandes en cours, lui, appartient au [TileWorkerPool] :
     * `submit` y déduplique par clé. Tenir un second registre ici créerait le
     * bug classique du double suivi — une annulation côté pool laisserait la
     * clé orpheline ici, et la tuile ne serait plus jamais redemandée.
     */
    fun isCached(key: Long): Boolean = cache.containsKey(key)

    fun get(key: Long): GpuTile? = cache[key]

    /**
     * Téléverse au plus [maxPerFrame] maillages en attente.
     *
     * Le tampon direct intermédiaire est réutilisé d'un appel à l'autre : en
     * allouer un par tuile réveillerait le ramasse-miettes que tout ce lot
     * s'applique à laisser dormir.
     */
    private var staging: ByteBuffer? = null

    fun uploadPending(maxPerFrame: Int, currentFrame: Long): Int {
        var uploaded = 0
        while (uploaded < maxPerFrame) {
            val pending = ready.poll() ?: break
            if (pending.epoch != acceptEpoch) continue
            val mesh = pending.mesh
            val key = mesh.tile.packed()

            // Une tuile peut avoir été demandée puis dépassée : si une version
            // est déjà au cache, on jette la nouvelle plutôt que de fuir un
            // tampon.
            if (cache.containsKey(key)) continue

            val vbo = gpu.acquire(mesh.sizeBytes)
            if (vbo == 0) continue

            val buf = ensureStaging(mesh.sizeBytes)
            buf.clear()
            buf.asFloatBuffer().put(mesh.vertexData)
            buf.position(0)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, mesh.sizeBytes, buf)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

            val tile = GpuTile(
                key, vbo, mesh.vertexCount,
                mesh.centerXM, mesh.centerYM, mesh.centerZM
            )
            tile.lastUsedFrame = currentFrame
            cache[key] = tile
            uploaded++
        }
        return uploaded
    }

    private fun ensureStaging(sizeBytes: Int): ByteBuffer {
        val current = staging
        if (current != null && current.capacity() >= sizeBytes) return current
        val fresh = ByteBuffer.allocateDirect(sizeBytes).order(ByteOrder.nativeOrder())
        staging = fresh
        return fresh
    }

    /**
     * Résout la liste des tuiles à dessiner pour l'ensemble sélectionné.
     *
     * Première passe : chaque feuille demandée est remplacée par elle-même si
     * elle est prête, sinon par son ancêtre prêt le plus profond. Deuxième
     * passe : tout candidat dont un ancêtre figure aussi parmi les candidats
     * est écarté — c'est ce qui interdit les superpositions quand deux sœurs
     * se replient différemment.
     *
     * @param selection feuilles voulues par le sélecteur
     * @param out reçoit les tuiles à dessiner, sans doublon ni superposition
     * @param currentFrame numéro d'image, pour marquer l'usage
     * @return le nombre de feuilles manquantes (à demander au pool)
     */
    private val resolveSet = HashSet<Long>()

    fun resolveDrawSet(
        selection: List<TileId>,
        out: MutableList<GpuTile>,
        currentFrame: Long
    ): Int {
        out.clear()
        resolveSet.clear()
        var missing = 0

        for (tile in selection) {
            var key = tile.packed()
            if (!cache.containsKey(key)) {
                missing++
                // Remonte vers le premier ancêtre prêt, sur clés compactées :
                // instancier un TileId par étage réveillerait le
                // ramasse-miettes que tout ce lot laisse dormir.
                key = parentKey(key)
                while (key != -1L && !cache.containsKey(key)) key = parentKey(key)
                if (key == -1L) continue
            }
            resolveSet.add(key)
        }

        // Écarte tout candidat couvert par un ancêtre candidat : jamais deux
        // tuiles superposées, sinon les deux surfaces presque confondues se
        // disputent le tampon de profondeur et scintillent.
        for (key in resolveSet) {
            var ancestor = parentKey(key)
            var covered = false
            while (ancestor != -1L) {
                if (resolveSet.contains(ancestor)) { covered = true; break }
                ancestor = parentKey(ancestor)
            }
            if (!covered) {
                val tile = cache[key] ?: continue
                tile.lastUsedFrame = currentFrame
                out.add(tile)
            }
        }
        return missing
    }

    companion object {
        /**
         * Clé compactée du parent, ou −1 au niveau racine. Reprend exactement
         * la disposition de [TileId.packed] : face en bits 58+, niveau en
         * bits 52 à 57, x en bits 26 à 51, y en bits 0 à 25.
         */
        fun parentKey(key: Long): Long {
            val level = ((key ushr 52) and 0x3F).toInt()
            if (level == 0) return -1L
            val face = (key ushr 58) and 0x3F
            val x = ((key ushr 26) and 0x3FFFFFF) shr 1
            val y = (key and 0x3FFFFFF) shr 1
            return (face shl 58) or ((level - 1).toLong() shl 52) or (x shl 26) or y
        }
    }

    /**
     * Rend au pool les tampons des tuiles inutilisées depuis [keepFrames]
     * images. À appeler de loin en loin, pas à chaque image : l'itération sur
     * tout le cache n'est pas gratuite.
     */
    fun evictStale(currentFrame: Long, keepFrames: Long) {
        val it = cache.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (currentFrame - entry.value.lastUsedFrame > keepFrames) {
                gpu.release(entry.value.vbo)
                it.remove()
            }
        }
    }

    /** Vide tout proprement — changement de monde, contexte encore valide. */
    fun clear() {
        for (tile in cache.values) gpu.release(tile.vbo)
        cache.clear()
        ready.clear()
    }

    /**
     * Oublie tout sans appeler OpenGL — perte de contexte : les identifiants
     * de tampons sont déjà caducs, les rendre viserait le nouveau contexte.
     */
    fun forgetGpu() {
        cache.clear()
        ready.clear()
    }
}
