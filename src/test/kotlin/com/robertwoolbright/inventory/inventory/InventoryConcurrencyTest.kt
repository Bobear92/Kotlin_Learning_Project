// Study notes: docs/learning/notes/test/inventory/InventoryConcurrencyTest.kt.md
package com.robertwoolbright.inventory.inventory

import com.robertwoolbright.inventory.support.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Why: @SpringBootTest, not @JdbcTest. @JdbcTest's rolled-back transaction belongs to the test thread
// only: rows it inserts stay invisible to the worker threads, and the workers' commits escape the rollback.
/**
 * Oversell test plan, concurrency section: many threads change the same SKU at the same moment
 * through [InventoryRepository], and no update may be lost or fail. Covers the new-SKU creation
 * race and concurrent restocks of an existing SKU.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class InventoryConcurrencyTest @Autowired constructor(
    private val inventoryRepository: InventoryRepository,
    private val jdbcClient: JdbcClient,
) {

    /** Nothing is rolled back here, so every run starts from an empty table. */
    @BeforeEach
    fun clearInventory() {
        jdbcClient.sql("DELETE FROM inventory").update()
    }

    private fun quantityOf(skuId: String): Int? =
        jdbcClient.sql("SELECT quantity FROM inventory WHERE sku_id = ?")
            .param(skuId)
            .query { rs, _ -> rs.getInt("quantity") }
            .list()
            .singleOrNull()

    // Why: a start gate. Every thread first checks in on `ready`, then waits on `start`; releasing
    // `start` once makes them all hit the database together instead of one after another.
    private fun <T> race(threads: Int, action: (Int) -> T): List<Result<T>> {
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

    /**
     * New-SKU race: every first-time add for a missing SKU succeeds (no duplicate-key error from
     * two INSERTs colliding) and the final quantity is the sum of all of them.
     */
    @RepeatedTest(5)
    fun `concurrent first-time adds for a new SKU all succeed and sum up`() {
        val threads = 50

        val results = race(threads) { i -> inventoryRepository.addStock("new-sku", i + 1) }

        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertTrue(failures.isEmpty(), "adds failed: $failures")
        assertTrue(results.all { it.getOrNull() != null }, "an add returned null (treated as overflow)")
        assertEquals((1..threads).sum(), quantityOf("new-sku"))
    }

    /** Lost-update race: concurrent restocks of one existing SKU all count; none overwrites another. */
    @RepeatedTest(5)
    fun `concurrent adds to an existing SKU are never lost`() {
        jdbcClient.sql("INSERT INTO inventory (sku_id, quantity) VALUES ('widget', 10)").update()
        val threads = 50

        val results = race(threads) { inventoryRepository.addStock("widget", 2) }

        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertTrue(failures.isEmpty(), "adds failed: $failures")
        assertEquals(10 + threads * 2, quantityOf("widget"))
    }
}
