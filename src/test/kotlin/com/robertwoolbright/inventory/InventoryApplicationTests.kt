// Study notes: docs/learning/notes/test/InventoryApplicationTests.kt.md
package com.robertwoolbright.inventory

import com.robertwoolbright.inventory.support.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Smoke test for the whole application wiring: if the context starts, every bean was created,
 * Postgres was reachable and the Flyway migrations applied cleanly.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class InventoryApplicationTests {

    /** Passes as long as the Spring context starts; the empty body is intentional. */
    @Test
    fun contextLoads() {
    }
}
