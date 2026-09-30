# MultiInstanceRaceTest.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/MultiInstanceRaceTest.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/MultiInstanceRaceTest.kt) · Last synced with code: 2026-09-30

## Role

The **multiple app instances** item of the oversell test plan in
[TODO.md](../../../../../.claude/TODO.md). In production a service runs as several copies behind a
load balancer. Anything that only works inside one JVM, such as a `synchronized` block, a Kotlin
`Mutex` or an in-memory cache of stock, breaks as soon as there are two. This test starts two
complete copies of the app against one Postgres and races buyers across both over real HTTP.

It passes because the protection was never in the app: it's the single SQL statement and the
row lock in Postgres (see the README decision record).

## Walkthrough

### No Spring test annotations

```kotlin
// Why: two full application contexts, each with its own Tomcat port and connection pool, like two
// pods behind a load balancer. Locks inside one JVM can't protect stock here; only the database can.
...
class MultiInstanceRaceTest {
```

**Spring:** `@SpringBootTest` gives a test *one* application context. This test needs two, so it
starts them itself (below) and is a plain JUnit class. Spring's test context cache doesn't know
about these contexts, which is why the class closes them in `@AfterAll`.

### Starting one database and two apps

```kotlin
private companion object {
    private val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18"))
    private lateinit var appA: ConfigurableApplicationContext
    private lateinit var appB: ConfigurableApplicationContext
...
    /** One database, then two apps pointed at it; the second finds Flyway's migration already applied. */
    @BeforeAll
    @JvmStatic
    fun startInstances() {
        postgres.start()
        appA = startApp()
        appB = startApp()
```

**JUnit: `@BeforeAll` / `@AfterAll`.** Run once for the whole class, not per test. Starting two
apps takes a few seconds, so all tests share them; `@BeforeEach` only empties the table. JUnit
requires these methods to be *static* (it calls them before any test instance exists).

**Kotlin: `companion object` + `@JvmStatic`.** Kotlin has no `static` (see
[InventoryContractTest.kt](InventoryContractTest.kt.md)). A companion object's functions are
called on the class, but in bytecode they live on a separate object. `@JvmStatic` also generates a
real static method on the class, which is what JUnit looks for.

**Kotlin: `lateinit var`.** A non-null property that is assigned later, not in a constructor. Here
the apps can only be created in `@BeforeAll`. Reading one before it's assigned throws
`UninitializedPropertyAccessException`. `::appA.isInitialized` asks whether it has been assigned,
so `@AfterAll` doesn't fail when startup crashed halfway. `lateinit` only works with `var` and
non-primitive types.

```kotlin
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
```

**Spring: `getBean`.** With `@SpringBootTest`, beans arrive through the constructor. Here there's no
test context to inject from, so the test asks app A's context for its `JdbcClient` directly. It's
used only for the `@BeforeEach` cleanup (`DELETE FROM inventory`); since both apps share the
database, emptying it through A empties it for B too. Fetching beans by hand is normal in test
setup and `main`-style code, and a smell inside application code, where injection is the rule.

**Why this order:** apps first, then the database. Closing a context shuts down its connection pool
cleanly; stopping Postgres first would make both pools log connection errors on the way out.

**Testcontainers:** here the container is started and stopped by hand instead of through
`@ServiceConnection` ([TestcontainersConfiguration.kt](../support/TestcontainersConfiguration.kt.md)),
because its connection details go to two apps, not one test context.

### What makes each copy a separate instance

```kotlin
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
```

**Spring: `SpringApplicationBuilder`.** The long form of `runApplication<InventoryApplication>()` in
[InventoryApplication.kt](../../main/InventoryApplication.kt.md): it builds and starts a complete
application context, with its own beans, `HikariDataSource` (connection pool) and embedded Tomcat.
Calling it twice gives two independent apps in one JVM. They share nothing but the database URL,
which is the situation that matters.

**Spring: `server.port=0`** picks a free random port, so the two Tomcats don't collide. Spring
publishes the chosen port as the `local.server.port` property once Tomcat has started.

**Spring: default properties.** `.properties(...)` sets defaults for that one app. This is where
the database connection comes from; `application.yml` deliberately has none (see its notes).

**Flyway:** both apps run migrations on startup. The first applies `V1`; the second finds it in
Flyway's history table and does nothing.

### The races

```kotlin
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
```

Same start gate ([Race.kt](../support/Race.kt.md)) and same invariant check
([OversellAssertions.kt](OversellAssertions.kt.md)) as the single-instance race, which is the
point: the guarantee is identical with one app or two. `i % 2` sends even buyers to A and odd ones
to B. The stock is seeded through A and read back through B, which also shows both apps really
share one database.

```kotlin
// Why: restocks go only to A and purchases only to B, so every interleaving of the two apps'
// writes to the one row is exercised. Demand (30 x 5 = 150) exceeds supply (20 + 30 x 3 = 110).
...
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
```

The second test sends every restock to A and every purchase to B, so the two apps' writes to the
same row interleave, and checks the exact balance like the mixed race in
[InventoryConcurrencyTest.kt](InventoryConcurrencyTest.kt.md#restocks-mixed-with-purchases): each
thread returns its change to the stock, and the final quantity must be the initial one plus their
sum. `receive` throws on anything but a 200, so a failed restock can't be silently counted.

**What would fail here but pass in `InventoryConcurrencyTest`:** a fix done in Kotlin, such as a
`synchronized` block or a per-SKU lock around a read-then-write purchase. That lock only exists in
one JVM. Two apps hold two separate locks, and the lost update comes back.

## Check your understanding

- Why can't this test use `@SpringBootTest`?
- What do the two apps share, and what does each have for itself?
- Why do `@BeforeAll` functions need `@JvmStatic` in Kotlin?
- Why does `@AfterAll` check `::appA.isInitialized` first?
- A teammate "fixes" oversell with a `synchronized` block around a read-then-write purchase. Which
  tests pass, which fail, and why?
- Why is the stock seeded through one instance and read through the other?
