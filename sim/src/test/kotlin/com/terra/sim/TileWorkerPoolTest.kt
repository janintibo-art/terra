package com.terra.sim

import com.terra.core.TerraLogger
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Première entrée de la concurrence dans le filet de la CI.
 *
 * ## Comment ces tests restent déterministes
 *
 * Un test de concurrence qui « dort et espère » est un test mal calibré —
 * la catégorie d'erreur que ce projet a déjà payée trois fois. Ici, aucun
 * ordonnancement n'est laissé au hasard :
 *
 *  - le pool est réduit à UN fil, et un travail « bouchon » l'occupe : tout
 *    ce qui est soumis ensuite s'accumule dans la file à priorité, où
 *    l'ordre est défini par construction ;
 *  - chaque franchissement de frontière entre fils passe par un
 *    [CountDownLatch] : on ne teste jamais « après un délai », toujours
 *    « après tel événement » ;
 *  - détail d'implémentation qui sert le déterminisme : le tout premier
 *    travail soumis à un ThreadPoolExecutor vide est remis DIRECTEMENT au
 *    fil créé, sans passer par la file. Le bouchon n'entre donc jamais en
 *    concurrence de priorité avec les travaux testés.
 *
 * Les délais de 5 s ne sont pas des attentes : ce sont des garde-fous qui
 * transforment un interblocage en échec lisible plutôt qu'en CI suspendue.
 */
class TileWorkerPoolTest {

    private fun awaitOrFail(latch: CountDownLatch, what: String) {
        if (!latch.await(5, TimeUnit.SECONDS)) fail("délai dépassé : $what")
    }

    /** Occupe l'unique fil du pool et rend la main une fois le fil dedans. */
    private fun plug(pool: TileWorkerPool, gate: CountDownLatch): CountDownLatch {
        val started = CountDownLatch(1)
        pool.submit(key = 999_999L, priority = 0f) {
            started.countDown()
            gate.await(5, TimeUnit.SECONDS)
        }
        awaitOrFail(started, "démarrage du bouchon")
        return started
    }

    @Test
    fun `la file sert les tuiles par priorite decroissante`() {
        val pool = TileWorkerPool(threadCount = 1)
        val gate = CountDownLatch(1)
        plug(pool, gate)

        val order = Collections.synchronizedList(ArrayList<String>())
        val done = CountDownLatch(3)
        // Soumis dans l'ordre C, A, B : seul le score doit compter.
        pool.submit(1L, priority = 1f) { order.add("C"); done.countDown() }
        pool.submit(2L, priority = 3f) { order.add("A"); done.countDown() }
        pool.submit(3L, priority = 2f) { order.add("B"); done.countDown() }

        gate.countDown()
        awaitOrFail(done, "exécution des trois tuiles")
        assertEquals(listOf("A", "B", "C"), order.toList(),
            "sans tri par priorité, la tuile au centre de l'écran attendrait " +
            "derrière celle de l'horizon")
        pool.shutdown()
    }

    @Test
    fun `une meme tuile soumise deux fois ne travaille qu une fois`() {
        val pool = TileWorkerPool(threadCount = 1)
        val gate = CountDownLatch(1)
        plug(pool, gate)

        val runs = AtomicInteger(0)
        val done = CountDownLatch(1)
        pool.submit(7L, priority = 1f) { runs.incrementAndGet(); done.countDown() }
        pool.submit(7L, priority = 5f) { runs.incrementAndGet(); done.countDown() }
        assertEquals(2, pool.pendingCount, "bouchon + une seule entrée pour la clé 7")

        gate.countDown()
        awaitOrFail(done, "exécution de la tuile 7")
        assertEquals(1, runs.get(),
            "la déduplication par clé a laissé passer un doublon : la même " +
            "tuile serait maillée deux fois et téléversée deux fois")
        pool.shutdown()
    }

    @Test
    fun `retainOnly annule les tuiles sorties du champ`() {
        val pool = TileWorkerPool(threadCount = 1)
        val gate = CountDownLatch(1)
        plug(pool, gate)

        val executed = Collections.synchronizedList(ArrayList<Long>())
        for (key in longArrayOf(1L, 2L, 3L)) {
            pool.submit(key, priority = 10f) { executed.add(key) }
        }
        // Le bouchon (999 999) doit rester : il est « en cours ».
        pool.retainOnly(setOf(999_999L, 1L))
        assertEquals(2, pool.pendingCount, "bouchon + tuile 1")

        // Sentinelle à priorité PLUS BASSE que les tuiles : elle ne peut
        // passer qu'en dernier. Quand elle a tourné, tout verdict est rendu.
        val drained = CountDownLatch(1)
        pool.submit(100L, priority = 1f) { drained.countDown() }

        gate.countDown()
        awaitOrFail(drained, "passage de la sentinelle")
        assertEquals(listOf(1L), executed.toList(),
            "une tuile annulée a tourné quand même : en descente rapide, le " +
            "pool gaspillerait son budget sur des tuiles déjà hors champ")
        pool.shutdown()
    }

    @Test
    fun `resoumission d une tuile annulee encore en cours - le retrait conditionnel`() {
        // LA course que le commentaire du retrait conditionnel décrit :
        // job1 (clé 7) est annulé PENDANT qu'il tourne ; job2, même clé, est
        // soumis avant la fin de job1. Le finally de job1 ne doit pas effacer
        // job2 du registre — sinon job2 tournerait hors de tout contrôle et
        // la soumission suivante créerait un doublon.
        val pool = TileWorkerPool(threadCount = 1)
        val gate1 = CountDownLatch(1)
        val started1 = CountDownLatch(1)
        pool.submit(7L, priority = 1f) {
            started1.countDown()
            gate1.await(5, TimeUnit.SECONDS)
        }
        awaitOrFail(started1, "démarrage de job1")

        pool.retainOnly(emptySet())          // job1 annulé, retiré du registre
        assertEquals(0, pool.pendingCount)

        val gate2 = CountDownLatch(1)
        val started2 = CountDownLatch(1)
        val runs2 = AtomicInteger(0)
        pool.submit(7L, priority = 1f) {
            started2.countDown()
            gate2.await(5, TimeUnit.SECONDS)
            runs2.incrementAndGet()
        }
        assertEquals(1, pool.pendingCount, "job2 enregistré sous la clé 7")

        gate1.countDown()                    // job1 finit ; son finally s'exécute
        awaitOrFail(started2, "démarrage de job2")
        // Le fil est unique : si job2 a démarré, le finally de job1 est passé.
        assertEquals(1, pool.pendingCount,
            "le finally de job1 a effacé job2 du registre : c'est le bug du " +
            "remove(key) aveugle que le retrait conditionnel doit empêcher")

        // Et la déduplication doit encore voir job2.
        pool.submit(7L, priority = 9f) { runs2.incrementAndGet() }
        assertEquals(1, pool.pendingCount, "job2 non doublé")

        gate2.countDown()
        // Le finally de job2 s'exécute APRÈS son corps : on borne l'attente
        // du nettoyage au lieu de la supposer instantanée.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (pool.pendingCount != 0 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(0, pool.pendingCount, "registre nettoyé après job2")
        assertEquals(1, runs2.get(), "job2 a tourné exactement une fois")
        pool.shutdown()
    }

    @Test
    fun `une exception dans un maillage ne tue pas le fil et laisse une trace`() {
        val logged = Collections.synchronizedList(ArrayList<Pair<String, Throwable?>>())
        val pool = TileWorkerPool(
            threadCount = 1,
            logger = TerraLogger { _, message, cause -> logged.add(message to cause) }
        )
        val boom = IllegalStateException("maillage volontairement cassé")
        val survived = CountDownLatch(1)
        pool.submit(1L, priority = 2f) { throw boom }
        pool.submit(2L, priority = 1f) { survived.countDown() }

        awaitOrFail(survived, "exécution de la tuile suivant l'exception")
        assertEquals(1, logged.size,
            "l'exception a été avalée sans trace — le pire des deux mondes")
        assertTrue(logged[0].second === boom, "la cause d'origine est conservée")
        pool.shutdown()
    }

    @Test
    fun `cancelAll vide la file au changement de monde`() {
        val pool = TileWorkerPool(threadCount = 1)
        val gate = CountDownLatch(1)
        plug(pool, gate)

        val executed = AtomicInteger(0)
        pool.submit(1L, priority = 5f) { executed.incrementAndGet() }
        pool.submit(2L, priority = 5f) { executed.incrementAndGet() }
        pool.cancelAll()
        assertEquals(0, pool.pendingCount)

        val drained = CountDownLatch(1)
        pool.submit(100L, priority = 1f) { drained.countDown() }
        gate.countDown()
        awaitOrFail(drained, "passage de la sentinelle")
        assertEquals(0, executed.get(),
            "une tuile de l'ancien monde a été maillée après cancelAll : " +
            "c'est la moitié amont de la course que acceptEpoch ferme en aval")
        pool.shutdown()
    }

    @Test
    fun `le nombre de fils par defaut respecte ses bornes`() {
        // Bornes documentées : au moins un fil, jamais plus de quatre —
        // au-delà, la contention mémoire annule le gain sur téléphone.
        val n = TileWorkerPool.defaultThreadCount()
        assertTrue(n in 1..4, "defaultThreadCount() = $n hors de [1, 4]")
    }
}
