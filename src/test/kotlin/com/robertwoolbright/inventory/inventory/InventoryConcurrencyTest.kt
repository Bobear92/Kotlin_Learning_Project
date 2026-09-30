// Study notes: docs/learning/notes/test/inventory/InventoryConcurrencyTest.kt.md
package com.robertwoolbright.inventory.inventory

import com.robertwoolbright.inventory.support.TestcontainersConfiguration
import com.robertwoolbright.inventory.support.race
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Why: @SpringBootTest, not @JdbcTest. @JdbcTest's rolled-back transaction belongs to the test thread
// only: rows it inserts stay invisible to the worker threads, and the workers' commits escape the rollback.
/**
 * Oversell test plan, concurrency section: many threads change the same SKU at the same moment
 * through [InventoryRepository], and no update may be lost or fail. Covers the new-SKU creation
 * race, concurrent restocks of an existing SKU, and the purchase races: same-size buyers, uneven
 * quantities, and restocks mixed with purchases. No race may ever oversell.
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

    // Why: assertNoOversell also checks the "remaining" values, proving each unit was sold exactly
    // once; NegativeControlTest shows it catches two buyers who both read 10 and both wrote 9.
    /**
     * Same-SKU purchase race: with stock S and N > S buyers of 1, exactly S succeed, N - S are refused
     * as insufficient stock, and the SKU ends at 0, never negative.
     */
    @RepeatedTest(5)
    fun `concurrent purchases never sell more than the stock`() {
        val stock = 10
        jdbcClient.sql("INSERT INTO inventory (sku_id, quantity) VALUES ('widget', ?)").param(stock).update()
        val threads = 50

        val results = race(threads) { inventoryRepository.removeStock("widget", 1) }

        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertTrue(failures.isEmpty(), "purchases failed: $failures")
        assertNoOversell(stock, results.map { it.getOrThrow() }, quantityOf("widget"))
    }

    /**
     * Uneven quantities: buyers of different sizes race for one SKU. What was sold never exceeds the
     * stock, and the final quantity is exactly the stock minus what was sold.
     */
    @RepeatedTest(5)
    fun `concurrent purchases of different sizes never oversell`() {
        val stock = 100
        jdbcClient.sql("INSERT INTO inventory (sku_id, quantity) VALUES ('widget', ?)").param(stock).update()
        val threads = 50

        val results = race(threads) { i ->
            val quantity = i % 7 + 1
            quantity to inventoryRepository.removeStock("widget", quantity)
        }

        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertTrue(failures.isEmpty(), "purchases failed: $failures")
        val outcomes = results.map { it.getOrThrow() }
        assertTrue(outcomes.none { (_, result) -> result == PurchaseResult.SkuNotFound }, "an existing SKU was reported missing")
        val sold = outcomes.filter { (_, result) -> result is PurchaseResult.Purchased }.sumOf { (quantity, _) -> quantity }
        assertTrue(sold <= stock, "sold $sold of $stock")
        assertEquals(stock - sold, quantityOf("widget"))
    }

    // Why: demand (30 x 5 = 150) exceeds supply (20 + 30 x 3 = 110), so some purchases are refused
    // and the test exercises both outcomes under contention.
    /**
     * Mixed receive and purchase race: restocks and purchases of one SKU interleave. Every restock
     * counts, every successful purchase counts, nothing else changes the stock.
     */
    @RepeatedTest(5)
    fun `concurrent restocks and purchases keep an exact balance`() {
        val initial = 20
        jdbcClient.sql("INSERT INTO inventory (sku_id, quantity) VALUES ('widget', ?)").param(initial).update()
        val threads = 60

        val results = race(threads) { i ->
            if (i % 2 == 0) {
                checkNotNull(inventoryRepository.addStock("widget", 3)) { "restock refused" }
                3
            } else {
                when (inventoryRepository.removeStock("widget", 5)) {
                    is PurchaseResult.Purchased -> -5
                    PurchaseResult.InsufficientStock -> 0
                    PurchaseResult.SkuNotFound -> error("an existing SKU was reported missing")
                }
            }
        }

        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertTrue(failures.isEmpty(), "operations failed: $failures")
        assertEquals(initial + results.sumOf { it.getOrThrow() }, quantityOf("widget"))
    }
}
