// Study notes: docs/learning/notes/test/support/TestcontainersConfiguration.kt.md
package com.robertwoolbright.inventory.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Shared test setup: a real PostgreSQL database in a throwaway Docker container.
 *
 * Tests that `@Import` this class get a DataSource pointing at the container. Spring caches
 * the test context, so tests with the same setup share one container.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    /** The Postgres container behind every integration test; its connection details become the DataSource. */
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        // Why: same major version as compose.yaml, so tests and local runs behave the same.
        PostgreSQLContainer(DockerImageName.parse("postgres:18"))
}
