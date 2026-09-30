// Study notes: docs/learning/notes/test/support/Race.kt.md
package com.robertwoolbright.inventory.support

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Why: a start gate. Every thread first checks in on `ready`, then waits on `start`; releasing
// `start` once makes them all hit the database together instead of one after another.
/**
 * Runs [action] on [threads] threads that all start at the same moment, and returns each thread's
 * outcome in thread-index order. An exception inside [action] becomes a failed [Result] instead of
 * stopping the race. Shared by every race test in the oversell test plan.
 */
fun <T> race(threads: Int, action: (Int) -> T): List<Result<T>> {
    val pool = Executors.newFixedThreadPool(threads)
    val ready = CountDownLatch(threads)
    val start = CountDownLatch(1)
    try {
        val futures = (0 until threads).map { i ->
            pool.submit(Callable {
                ready.countDown()
                start.await()
                action(i)
            })
        }
        ready.await()
        start.countDown()
        return futures.map { runCatching { it.get(30, TimeUnit.SECONDS) } }
    } finally {
        pool.shutdownNow()
    }
}
