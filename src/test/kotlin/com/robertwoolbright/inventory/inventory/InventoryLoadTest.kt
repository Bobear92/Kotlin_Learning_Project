// Study notes: docs/learning/notes/test/inventory/InventoryLoadTest.kt.md
package com.robertwoolbright.inventory.inventory

import com.robertwoolbright.inventory.support.InventoryHttpClient
import com.robertwoolbright.inventory.support.TestcontainersConfiguration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Oversell test plan, HTTP load test: many clients buy and restock a few popular SKUs over real
 * HTTP for a fixed time, then the final stock of each SKU must equal what was received minus what
 * was sold, and never be negative. Prints throughput and latency. Tagged `load`: it runs with
 * `./gradlew loadTest`, not with `./gradlew test`.
 */
@Tag("load")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // Why: warnings only, so the summary isn't buried under startup logs in the loadTest output.
    properties = ["logging.level.root=WARN"],
)
@Import(TestcontainersConfiguration::class)
class InventoryLoadTest @Autowired constructor(
    private val jdbcClient: JdbcClient,
    @LocalServerPort private val port: Int,
) {

    private val seconds = System.getProperty("load.seconds")?.toLong() ?: 20
    private val clients = System.getProperty("load.clients")?.toInt() ?: 50
    private val hotSkus = listOf("hot-1", "hot-2", "hot-3")
    private val initialStock = 100

    /** What one client did: units sold and received per SKU, outcome counts, and every latency. */
    private class Tally {
        val sold = mutableMapOf<String, Int>()
        val received = mutableMapOf<String, Int>()
        var purchased = 0
        var refused = 0
        var restocks = 0
        val latenciesMicros = mutableListOf<Long>()
    }

    // Why: demand (70% of calls, 1-3 units) outruns supply (30%, 1-3 units), so every SKU spends
    // most of the run near zero, where an oversell would happen, instead of far from it.
    /** Sustained purchases and restocks on three SKUs; the books must balance exactly at the end. */
    @Test
    fun `sustained load on hot SKUs never oversells and keeps an exact balance`() {
        jdbcClient.sql("DELETE FROM inventory").update()
        val client = InventoryHttpClient("http://localhost:$port")
        hotSkus.forEach { client.receive(it, initialStock) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)

        val started = System.nanoTime()
        val tallies = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            (0 until clients)
                .map { i -> executor.submit(Callable { runClient(client, Random(i), deadline) }) }
                .map { it.get() }
        }
        val elapsedSeconds = (System.nanoTime() - started) / 1e9

        val stored = client.inventory().associate { it.skuId to it.quantity }
        for (sku in hotSkus) {
            val received = tallies.sumOf { it.received[sku] ?: 0 }
            val sold = tallies.sumOf { it.sold[sku] ?: 0 }
            assertEquals(initialStock + received - sold, stored[sku], "balance of $sku")
            assertTrue(stored.getValue(sku) >= 0, "$sku went negative: ${stored[sku]}")
        }
        val purchased = tallies.sumOf { it.purchased }
        val refused = tallies.sumOf { it.refused }
        assertTrue(purchased > 0 && refused > 0, "the run never reached the stock limit: $purchased sold, $refused refused")

        printSummary(tallies, elapsedSeconds, stored)
    }

    // Why: each client keeps its own Tally and they're added up at the end, so the clients never
    // contend on shared counters, only on the app and the database.
    private fun runClient(client: InventoryHttpClient, random: Random, deadline: Long): Tally {
        val tally = Tally()
        while (System.nanoTime() < deadline) {
            val sku = hotSkus.random(random)
            val quantity = random.nextInt(1, 4)
            val start = System.nanoTime()
            if (random.nextInt(100) < 70) {
                when (client.purchase(sku, quantity)) {
                    is PurchaseResult.Purchased -> {
                        tally.purchased++
                        tally.sold.merge(sku, quantity, Int::plus)
                    }
                    PurchaseResult.InsufficientStock -> tally.refused++
                    PurchaseResult.SkuNotFound -> error("$sku was reported missing")
                }
            } else {
                client.receive(sku, quantity)
                tally.restocks++
                tally.received.merge(sku, quantity, Int::plus)
            }
            tally.latenciesMicros += (System.nanoTime() - start) / 1_000
        }
        return tally
    }

    private fun printSummary(tallies: List<Tally>, elapsedSeconds: Double, stored: Map<String, Int>) {
        val latencies = tallies.flatMap { it.latenciesMicros }.sorted()
        fun percentileMillis(p: Double) = latencies[((latencies.size - 1) * p).toInt()] / 1_000.0
        println(
            """
            |Load test: $clients clients for ${"%.1f".format(elapsedSeconds)} s
            |  requests:   ${latencies.size} (${"%.0f".format(latencies.size / elapsedSeconds)} per second)
            |  purchases:  ${tallies.sumOf { it.purchased }} succeeded, ${tallies.sumOf { it.refused }} refused
            |  restocks:   ${tallies.sumOf { it.restocks }}
            |  latency ms: p50 ${percentileMillis(0.50)}, p95 ${percentileMillis(0.95)}, p99 ${percentileMillis(0.99)}, max ${latencies.last() / 1_000.0}
            |  final stock: $stored
            """.trimMargin()
        )
    }
}
