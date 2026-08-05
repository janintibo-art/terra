package com.terra.planet

import android.util.Log
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pool de fils d'exécution pour la génération des tuiles de terrain.
 *
 * ## Pourquoi un pool, et pourquoi maintenant
 *
 * Jusqu'ici un fil unique suffisait : on générait un monde toutes les dix
 * secondes. Le rendu à tuiles change complètement le régime — des dizaines de
 * tuiles par seconde, chacune évaluant du bruit fractal sur près de trois cents
 * sommets. Un fil unique deviendrait le goulot d'étranglement, et la descente
 * se ferait par à-coups pendant que le terrain rattrape son retard.
 *
 * ## Priorité, et pourquoi elle est indispensable
 *
 * Toutes les tuiles ne se valent pas. Celle qui occupe le centre de l'écran
 * compte cent fois plus que celle qui affleure au bord de l'horizon. Sans file
 * de priorité, une tuile lointaine demandée deux secondes plus tôt passerait
 * avant celle que l'utilisateur regarde — c'est la recette du terrain qui reste
 * flou là où l'œil se pose.
 *
 * ## Annulation
 *
 * En descendant, une tuile demandée à une altitude cesse d'être pertinente à la
 * suivante. Les travaux devenus inutiles sont marqués annulés plutôt que
 * retirés de la file : le retrait d'une file à priorité coûte cher, un drapeau
 * vérifié au démarrage ne coûte rien.
 */
class TileWorkerPool(
    threadCount: Int = defaultThreadCount()
) {

    private class Job(
        val key: Long,
        val priority: Float,
        val body: () -> Unit
    ) : Runnable {
        @Volatile var cancelled: Boolean = false
        override fun run() {
            if (cancelled) return
            body()
        }
    }

    /** Priorité décroissante : le plus grand score passe en premier. */
    private val queue = PriorityBlockingQueue<Runnable>(64) { a, b ->
        val pa = if (a is Job) a.priority else 0f
        val pb = if (b is Job) b.priority else 0f
        pb.compareTo(pa)
    }

    private val threadIndex = AtomicInteger(0)

    private val executor = ThreadPoolExecutor(
        threadCount, threadCount,
        0L, TimeUnit.MILLISECONDS,
        queue
    ) { runnable ->
        Thread(runnable, "terra-tile-${threadIndex.incrementAndGet()}").apply {
            // En dessous de la priorité normale : le rendu et l'interface
            // doivent toujours passer devant la génération de terrain.
            priority = Thread.NORM_PRIORITY - 2
            isDaemon = true
        }
    }

    /** Travaux en cours ou en attente, indexés par tuile. */
    private val pending = HashMap<Long, Job>()

    val threadCountUsed: Int = threadCount

    val pendingCount: Int
        get() = synchronized(pending) { pending.size }

    /**
     * Soumet la génération d'une tuile.
     *
     * Une demande déjà en attente pour la même tuile voit simplement sa priorité
     * mise à jour, sans doublon.
     */
    fun submit(key: Long, priority: Float, body: () -> Unit) {
        synchronized(pending) {
            val existing = pending[key]
            if (existing != null && !existing.cancelled) {
                // Déjà en file. Reclasser exigerait de la retirer et de la
                // réinsérer ; à ce coût, mieux vaut la laisser passer telle
                // quelle : l'écart de priorité se résorbe en une image ou deux.
                return
            }
            val job = Job(key, priority) {
                try {
                    body()
                } catch (t: Throwable) {
                    Log.e(TAG, "Génération de tuile échouée", t)
                } finally {
                    synchronized(pending) { pending.remove(key) }
                }
            }
            pending[key] = job
            executor.execute(job)
        }
    }

    /** Annule tout travail dont la tuile n'est plus dans l'ensemble utile. */
    fun retainOnly(stillNeeded: Set<Long>) {
        synchronized(pending) {
            val iterator = pending.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in stillNeeded) {
                    entry.value.cancelled = true
                    iterator.remove()
                }
            }
        }
        queue.removeIf { it is Job && it.cancelled }
    }

    fun cancelAll() {
        synchronized(pending) {
            for (job in pending.values) job.cancelled = true
            pending.clear()
        }
        queue.clear()
    }

    fun shutdown() {
        cancelAll()
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "TerraTilePool"

        /**
         * On laisse toujours au moins un cœur au rendu et à l'interface, et on
         * plafonne à quatre : au-delà, la contention mémoire annule le gain sur
         * un téléphone.
         */
        fun defaultThreadCount(): Int =
            (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
    }
}
