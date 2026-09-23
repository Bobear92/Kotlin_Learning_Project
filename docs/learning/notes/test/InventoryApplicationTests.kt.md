# InventoryApplicationTests.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/InventoryApplicationTests.kt](../../../../src/test/kotlin/com/robertwoolbright/inventory/InventoryApplicationTests.kt) · Last synced with code: 2026-09-23

## Role

A smoke test that checks the whole application can start. It's the cheapest test that
catches a broken dependency, a bad config file or a failing migration.

## Walkthrough

### Starting the real application in a test

```kotlin
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class InventoryApplicationTests {
```

**Spring: `@SpringBootTest`.** Searches up the package tree for a `@SpringBootConfiguration` class
(ours gets it through `@SpringBootApplication`), then
starts the *full* application context, the same one `main` builds. (Test "slices" such as
`@WebMvcTest` start only part of it; we'll use those for controller tests.) Here there's no web
server, because `@SpringBootTest` uses a mock web environment unless told otherwise.

**Spring: `@Import`.** Adds the Postgres container from `support/` to this context. Without it
there's no database, the DataSource can't be configured, and the context fails to start.

**Kotlin: class references.** `TestcontainersConfiguration::class` is a `KClass`, Kotlin's
equivalent of Java's `TestcontainersConfiguration.class`. For an annotation argument Kotlin passes
the Java class automatically.

### An empty test that still tests something

```kotlin
/** Passes as long as the Spring context starts; the empty body is intentional. */
@Test
fun contextLoads() {
}
```

The work happens before the method runs. JUnit asks Spring's test extension to prepare the test
instance, which starts the context. If that fails, the test fails. So "passes" means every bean
was created, Postgres was reachable and Flyway applied every migration.

**Kotlin:** JUnit Jupiter doesn't need test classes to be `open`, so Kotlin's final-by-default
classes are fine. (This one happens to be open anyway: `plugin.spring` opens classes annotated
with `@SpringBootTest`.)

## Check your understanding

- Delete the `@Import` line and run `./gradlew test`. What error do you predict, and at which step does it happen?
- Why is `contextLoads` worth keeping once there are more detailed tests?
