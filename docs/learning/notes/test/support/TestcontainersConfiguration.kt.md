# TestcontainersConfiguration.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/support/TestcontainersConfiguration.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/support/TestcontainersConfiguration.kt) · Last synced with code: 2026-09-23

## Role

Shared test setup. Any test that imports it gets a real Postgres 18 running in Docker, with the
app's DataSource pointed at it. Every repository, controller and concurrency test builds on this.

## Walkthrough

### `@TestConfiguration(proxyBeanMethods = false)`

```kotlin
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
```

**Spring:** `@TestConfiguration` is a `@Configuration` meant only for tests. Component scanning
from `@SpringBootApplication` deliberately skips it, so the container exists only in tests that
ask for it with `@Import(TestcontainersConfiguration::class)`.

**Spring: `proxyBeanMethods`.** By default Spring subclasses a `@Configuration` class so that
when one `@Bean` method calls another, you get the existing bean instead of a new object. We have
one bean and no such calls, so turning the proxy off is faster and changes nothing.

**Kotlin: named arguments.** `proxyBeanMethods = false` names the parameter it sets. Named
arguments work when calling Kotlin functions and for annotation parameters, but **not** for Java
methods, whose parameter names Kotlin can't rely on. For a Java annotation like this one,
every parameter except `value` *must* be named. `@TestConfiguration(false)` wouldn't compile,
because a positional argument goes to `value` (a `String`).

### The container bean

```kotlin
@Bean
@ServiceConnection
fun postgresContainer(): PostgreSQLContainer =
    // Why: same major version as compose.yaml, so tests and local runs behave the same.
    PostgreSQLContainer(DockerImageName.parse("postgres:18"))
```

**Spring: `@Bean`.** The return value becomes a Spring-managed object. Testcontainers containers
are `Startable`, and Spring Boot starts them when the bean is created and stops them when the
context closes.

**Spring: `@ServiceConnection`.** This annotation is what connects the container to the app. Spring Boot sees a
`PostgreSQLContainer`, asks it for its JDBC URL, username and password (random host port, test
credentials), and registers them as the connection details. Those override any
`spring.datasource.*` setting.

**Spring: context caching.** Spring caches test application contexts and reuses one for any
test class whose *whole* configuration is identical, so those classes share one container.
A different setup (a slice like `@WebMvcTest` or `@JdbcTest`, extra properties, a
`@MockitoBean`) gets its own context, and its own container if it also imports this
configuration. Cached contexts stay alive until the test JVM exits or they're evicted from the
cache (it holds 32 by default), so several Postgres containers can be running at once.

**Kotlin: expression body.** `fun f(): T = expr` replaces `fun f(): T { return expr }` when the
body is a single expression. The return type could be inferred, but we keep it because it's
part of what this function promises.

**Why:** a real Postgres rather than H2. See the [build notes](../../build.gradle.kts.md).

## Check your understanding

- What starts the container: JUnit, Testcontainers, or Spring? When does it stop?
- Two test classes both import this configuration. When do they share one Postgres container, and when do they get one each?
