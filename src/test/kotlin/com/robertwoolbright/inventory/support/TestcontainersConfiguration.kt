package com.robertwoolbright.inventory.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

// Spring: `@TestConfiguration` classes are skipped by `@SpringBootApplication`'s component
// scan, so this container only exists in tests that ask for it with
// `@Import(TestcontainersConfiguration::class)`.
// Kotlin: `proxyBeanMethods = false` is a named argument. Kotlin annotations can use names
// for their parameters just like function calls, and Java annotations look much the same.
// Spring: with proxying turned off, Spring doesn't subclass this class to intercept calls
// between `@Bean` methods. That's faster, and safe here because no bean method calls another.

/**
 * Shared test setup: a real PostgreSQL database in a throwaway Docker container.
 *
 * Any test that imports this class gets a DataSource pointing at the container, so
 * repository, controller and race tests all run against real Postgres behavior. Spring
 * caches the test context, so tests with the same setup share one container.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    // Spring: `@Bean` makes the container a Spring bean. Spring starts it when the test
    // context starts and stops it when the context closes.
    // `@ServiceConnection` reads the container's JDBC URL, username and password and uses
    // them for the DataSource, overriding any `spring.datasource.*` settings.
    // Kotlin: an expression body (`= value`) replaces `{ return value }` when a function is a
    // single expression. The return type is still written out, since it's part of the public API.

    /**
     * The Postgres container that backs every integration test.
     *
     * @return a container running the same Postgres version as compose.yaml.
     */
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        // Why: pinned to the same major version as compose.yaml, so tests and local runs match.
        PostgreSQLContainer(DockerImageName.parse("postgres:18"))
}
