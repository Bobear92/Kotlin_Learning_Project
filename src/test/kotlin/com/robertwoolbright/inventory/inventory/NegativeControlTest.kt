// Study notes: docs/learning/notes/test/inventory/NegativeControlTest.kt.md
package com.robertwoolbright.inventory.inventory

import com.robertwoolbright.inventory.support.TestcontainersConfiguration
import com.robertwoolbright.inventory.support.race
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Oversell test plan, negative control: proves the race check in [assertNoOversell] can fail. A
 * naive read-then-write purchase ([NaiveInventoryRepository], test-only) passes the check when
 * buyers take turns, and fails it when they race.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class NegativeControlTest @Autowired constructor(
    private val jdbcClient: JdbcClient,
) {

    private val stock = 10
    private val buyers = 12

    /** Every test starts with one SKU, `widget`, holding [stock] units. */
    @BeforeEach
    fun seedWidget() {
        jdbcClient.sql("DELETE FROM inventory").update()
        jdbcClient.sql("INSERT INTO inventory (sku_id, quantity) VALUES ('widget', ?)").param(stock).update()
    }

    private fun quantityOf(skuId: String): Int? =
        jdbcClient.sql("SELECT quantity FROM inventory WHERE sku_id = ?")
            .param(skuId)
            .query { rs, _ -> rs.getInt("quantity") }
            .list()
            .singleOrNull()

    /** Without concurrency the naive code is correct, so a failure below comes from the race alone. */
    @Test
    fun `the naive purchase passes the oversell check when buyers take turns`() {
        val naive = NaiveInventoryRepository(jdbcClient) {}

        val outcomes = (1..buyers).map { naive.removeStock("widget", 1) }

        assertNoOversell(stock, outcomes, quantityOf("widget"))
    }

    // Why: a real race only sometimes interleaves badly. The barrier holds every buyer between its
    // read and its write until all of them have read, so the lost update happens on every run.
    /**
     * With every buyer reading stock 10 before anyone writes, all of them "succeed" and all write 9.
     * No error is raised and the database CHECK never fires, yet the oversell check fails.
     */
    @Test
    fun `the oversell check catches a naive purchase under a race`() {
        val barrier = CyclicBarrier(buyers)
        val naive = NaiveInventoryRepository(jdbcClient) { barrier.await(10, TimeUnit.SECONDS) }

        val results = race(buyers) { naive.removeStock("widget", 1) }

        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertTrue(failures.isEmpty(), "the naive purchases raised errors: $failures")
        val outcomes = results.map { it.getOrThrow() }
        val caught = assertFailsWith<AssertionError> {
            assertNoOversell(stock, outcomes, quantityOf("widget"))
        }
        assertTrue(caught.message.orEmpty().startsWith("oversold"), "expected an oversell, got: ${caught.message}")
    }
}
