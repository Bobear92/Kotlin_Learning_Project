package com.robertwoolbright.inventory

import com.robertwoolbright.inventory.support.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

// Spring: `@SpringBootTest` starts the whole application context, the same one `main` builds.
// `@Import` adds the Postgres container from support/, so the context has a real database.

/**
 * Smoke test for the whole application wiring.
 *
 * If the context starts, then every bean could be created, auto-configuration found a
 * working DataSource, Postgres was reachable and the Flyway migrations applied cleanly.
 * It's the cheapest test that catches a broken build, dependency or config file.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class InventoryApplicationTests {

    // Kotlin: `TestcontainersConfiguration::class` above is a class reference, Kotlin's version
    // of Java's `TestcontainersConfiguration.class`.

    /** Passes as long as the Spring context starts; the test has no body on purpose. */
    @Test
    fun contextLoads() {
    }
}
