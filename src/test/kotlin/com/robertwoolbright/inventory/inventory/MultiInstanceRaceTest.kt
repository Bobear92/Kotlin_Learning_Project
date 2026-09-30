// Study notes: docs/learning/notes/test/inventory/MultiInstanceRaceTest.kt.md
package com.robertwoolbright.inventory.inventory

import com.robertwoolbright.inventory.InventoryApplication
import com.robertwoolbright.inventory.support.InventoryHttpClient
import com.robertwoolbright.inventory.support.race
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Why: two full application contexts, each with its own Tomcat port and connection pool, like two
// pods behind a load balancer. Locks inside one JVM can't protect stock here; only the database can.
/**
 * Oversell test plan, across instances: two copies of the app share one Postgres, and buyers split
 * between them race over real HTTP. No purchase may oversell and no update may be lost, because the
 * guarantee lives in the database, not in either app.
 */
class MultiInstanceRaceTest {

    /** Starts every test from an empty table; both instances see the same one. */
    @BeforeEach
    fun clearInventory() {
        jdbcClient.sql("DELETE FROM inventory").update()
    }

    /** Buyers of 1 alternate between the instances; the oversell check is the same as for one instance. */
    @RepeatedTest(value = 3, name = RepeatedTest.LONG_DISPLAY_NAME)
    fun `purchases split across two instances never oversell`() {
        val stock = 10
        instanceA.receive("widget", stock)
        val buyers = 50

        val results = race(buyers) { i -> instances[i % 2].purchase("widget", 1) }

        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertTrue(failures.isEmpty(), "purchases failed: $failures")
        assertNoOversell(stock, results.map { it.getOrThrow() }, instanceB.quantityOf("widget"))
    }

    // Why: restocks go only to A and purchases only to B, so every interleaving of the two apps'
    // writes to the one row is exercised. Demand (30 x 5 = 150) exceeds supply (20 + 30 x 3 = 110).
    /** Restocks through one instance and purchases through the other keep an exact balance. */
    @RepeatedTest(value = 3, name = RepeatedTest.LONG_DISPLAY_NAME)
    fun `restocks on one instance and purchases on the other keep an exact balance`() {
        val initial = 20
        instanceA.receive("widget", initial)
        val threads = 60

        val results = race(threads) { i ->
            if (i % 2 == 0) {
                instanceA.receive("widget", 3)
                3
            } else {
                when (instanceB.purchase("widget", 5)) {
                    is PurchaseResult.Purchased -> -5
                    PurchaseResult.InsufficientStock -> 0
                    PurchaseResult.SkuNotFound -> error("an existing SKU was reported missing")
                }
            }
        }

        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertTrue(failures.isEmpty(), "operations failed: $failures")
        assertEquals(initial + results.sumOf { it.getOrThrow() }, instanceA.quantityOf("widget"))
    }

    private companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18"))
        private lateinit var appA: ConfigurableApplicationContext
        private lateinit var appB: ConfigurableApplicationContext
        private lateinit var instanceA: InventoryHttpClient
        private lateinit var instanceB: InventoryHttpClient
        private lateinit var instances: List<InventoryHttpClient>
        private lateinit var jdbcClient: JdbcClient

        /** One database, then two apps pointed at it; the second finds Flyway's migration already applied. */
        @BeforeAll
        @JvmStatic
        fun startInstances() {
            postgres.start()
            appA = startApp()
            appB = startApp()
            instanceA = InventoryHttpClient(baseUrl(appA))
            instanceB = InventoryHttpClient(baseUrl(appB))
            instances = listOf(instanceA, instanceB)
            jdbcClient = appA.getBean(JdbcClient::class.java)
        }

        /** Stops both apps before the database they depend on. */
        @AfterAll
        @JvmStatic
        fun stopInstances() {
            if (::appA.isInitialized) appA.close()
            if (::appB.isInitialized) appB.close()
            postgres.stop()
        }

        private fun startApp(): ConfigurableApplicationContext =
            SpringApplicationBuilder(InventoryApplication::class.java)
                .properties(
                    "server.port=0",
                    "spring.datasource.url=${postgres.jdbcUrl}",
                    "spring.datasource.username=${postgres.username}",
                    "spring.datasource.password=${postgres.password}",
                )
                .run()

        private fun baseUrl(app: ConfigurableApplicationContext): String =
            "http://localhost:${app.environment.getRequiredProperty("local.server.port")}"
    }
}
