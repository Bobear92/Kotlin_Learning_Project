# InventoryLoadTest.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryLoadTest.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryLoadTest.kt) · Last synced with code: 2026-09-30

## Role

The **HTTP load test** from the oversell test plan in [TODO.md](../../../../../.claude/TODO.md). The
race tests fire one burst of 50 requests. This one keeps 50 clients buying and restocking three
popular SKUs over real HTTP for 20 seconds, which is tens of thousands of requests. At the end, the
books must balance exactly for every SKU.

It runs with `./gradlew loadTest` (see [build.gradle.kts](../../build.gradle.kts.md#a-separate-task-for-the-load-test)),
not with `./gradlew test`, because it takes a fixed time (decided by Robert, 2026-09-30). It also
prints throughput and latency, so it doubles as a rough performance check.

A 5-second run on 2026-09-30 (a Windows laptop, Postgres in Docker) gave:

```
Load test: 50 clients for 5.0 s
  requests:   12223 (2439 per second)
  purchases:  4225 succeeded, 4418 refused
  restocks:   3580
  latency ms: p50 18.336, p95 38.576, p99 56.805, max 140.117
  final stock: {hot-1=8, hot-2=0, hot-3=3}
```

## Walkthrough

### Setup: a real server on a random port

```kotlin
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
```

**Spring: `WebEnvironment.RANDOM_PORT`.** By default `@SpringBootTest` has no real server (MockMvc
calls the controllers directly). `RANDOM_PORT` starts the embedded Tomcat on a free port, so the
test talks to it through real sockets with [InventoryHttpClient.kt](../support/InventoryHttpClient.kt.md).
`@LocalServerPort` injects the port that was picked.

**Spring: `properties = [...]`.** Sets properties for this test's context only. Kotlin writes array
arguments of annotations with `[...]`.

**JUnit: `@Tag("load")`.** The label the Gradle tasks filter on.

### Settings from the command line

```kotlin
private val seconds = System.getProperty("load.seconds")?.toLong() ?: 20
private val clients = System.getProperty("load.clients")?.toInt() ?: 50
```

**Kotlin: `?.` and `?:` together.** `System.getProperty` returns `String?`. `?.toLong()` converts it
only if it's there, and `?: 20` supplies the default otherwise. Java would need
`p != null ? Long.parseLong(p) : 20`. The build forwards `-Dload.seconds=...` to the test JVM.

### Seeding and the clock

```kotlin
// Why: demand (70% of calls, 1-3 units) outruns supply (30%, 1-3 units), so every SKU spends
// most of the run near zero, where an oversell would happen, instead of far from it.
...
fun `sustained load on hot SKUs never oversells and keeps an exact balance`() {
    jdbcClient.sql("DELETE FROM inventory").update()
    val client = InventoryHttpClient("http://localhost:$port")
    hotSkus.forEach { client.receive(it, initialStock) }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)

    val started = System.nanoTime()
```

**Why seed over HTTP:** the three SKUs start at 100 units each through `POST /inventory/{skuId}`,
the same path a real client would use. The `DELETE` goes straight to the database, since no endpoint deletes. It's a precaution: no other
test shares this context today (`RANDOM_PORT` plus its own `properties` make it unique), but the
test shouldn't depend on that.

**Java: `System.nanoTime()`.** A clock for measuring *durations*, in nanoseconds. Unlike
`currentTimeMillis()`, it never jumps when the system clock is adjusted, so it's the right tool for
deadlines and latencies. `TimeUnit.SECONDS.toNanos(seconds)` converts the run length.
`(System.nanoTime() - started) / 1e9` turns the elapsed time into seconds: `1e9` is a `Double`
literal (10⁹), so the division gives a `Double`, not a truncated `Long`.

### Each client keeps its own tally

```kotlin
/** What one client did: units sold and received per SKU, outcome counts, and every latency. */
private class Tally {
    val sold = mutableMapOf<String, Int>()
    val received = mutableMapOf<String, Int>()
    var purchased = 0
    var refused = 0
    var restocks = 0
    val latenciesMicros = mutableListOf<Long>()
}
```

**Why per client:** each client writes only to its own `Tally`, so the maps and counters need no
locks or atomics. The tallies are added up once everyone has finished. The only thing the clients
contend on is what's under test: the app and the database.

### The run

```kotlin
val tallies = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
    (0 until clients)
        .map { i -> executor.submit(Callable { runClient(client, Random(i), deadline) }) }
        .map { it.get() }
}
```

**Java 21: virtual threads.** `newVirtualThreadPerTaskExecutor()` runs each task on a *virtual*
thread: a lightweight thread managed by the JVM, not the operating system. A blocked virtual thread
(waiting on HTTP) costs almost nothing, so thousands are fine. The race helper in
[Race.kt](../support/Race.kt.md) uses a fixed pool of ordinary *platform* threads. Virtual threads
would work there too (a virtual thread waiting on a `CountDownLatch` is simply parked); `race`
just predates this test and 50 platform threads are cheap enough. One real difference to know: on
Java 21, a virtual thread that blocks *inside a `synchronized` block* is "pinned" to its carrier
thread and loses its advantage (fixed in Java 24).

**Java: `.map { it.get() }`.** `Future.get()` waits for a client to finish and returns its `Tally`. If
the client threw (an unexpected HTTP answer, a timeout), `get()` rethrows it wrapped in an
`ExecutionException`, which fails the test with the original cause attached.

**Kotlin: `use`.** Runs the block, then closes the resource, even if the block throws. It's Java's
try-with-resources. Closing this executor waits for its tasks to finish.

**Kotlin: `Random(i)`.** A random generator seeded with the client's number, so each client's
sequence of choices is different but repeatable.

### One client's loop

```kotlin
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
```

**Why these odds:** 70% purchases against 30% restocks, with the same sizes (1 to 3 units), means
demand outruns supply. Each SKU starts at 100, sells out within the first second or so, and then
spends the rest of the run near zero, with every restock immediately fought over. That's the zone
where an oversell would happen. A test with plenty of stock would never get near the boundary.

**Kotlin: `random.nextInt(1, 4)`** gives 1, 2 or 3; the upper bound is excluded, like `until`.
`hotSkus.random(random)` picks a list element using that generator.

**Kotlin: `merge(key, value, Int::plus)`.** A `MutableMap` method from Java: store `value` if the key
is absent, otherwise combine the old and new value with the function. `Int::plus` is a *function
reference*, the `+` operator as a function. Java: `Integer::sum`.

**Kotlin: `+=` on a list.** `latenciesMicros += x` adds an element to a `MutableList` (`add(x)`).

**Kotlin: `1_000`.** Underscores in number literals are only for readability, as in Java.

### The checks

```kotlin
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
```

1. **Exact balance, per SKU:** what the database holds equals the starting stock plus every restock
   the clients were told succeeded, minus every purchase they were told succeeded. One lost update,
   or one sale the database didn't record, in tens of thousands of requests would break it.
2. **Never negative.**
3. **The boundary was reached:** some purchases succeeded *and* some were refused. Without this, a
   run that never ran out of stock would pass without testing anything.

The final stock is read with `GET /inventory` over HTTP, the way the brief's own example checks it.

**Kotlin: `associate { a to b }`** turns a list into a `Map` using the `Pair` each element returns.

### The summary

```kotlin
private fun printSummary(tallies: List<Tally>, elapsedSeconds: Double, stored: Map<String, Int>) {
    val latencies = tallies.flatMap { it.latenciesMicros }.sorted()
    fun percentileMillis(p: Double) = latencies[((latencies.size - 1) * p).toInt()] / 1_000.0
```

**Percentiles:** p95 = 38 ms means 95% of requests took 38 ms or less. Percentiles say more than an
average, because one slow request in a hundred is what users notice.

**Kotlin: a local function.** `fun percentileMillis` is declared *inside* `printSummary` and can use
its `latencies`. Java has no direct equivalent (a lambda comes closest).

**Kotlin: `trimMargin()`.** In the raw string, each line starts with `|`; `trimMargin()` strips
everything up to and including it, so the text can be indented with the code.

**Caveat:** the numbers depend on the machine. The app, Postgres (in Docker) and the 50 clients all
share one laptop here, so they're a smoke test for performance, not a benchmark.

## Check your understanding

- The race tests already prove there's no oversell. What does sustained load add?
- Why does the test assert that some purchases were *refused*?
- Why is demand set higher than supply? What would the test miss with an initial stock of 1,000,000?
- Why does each client get its own `Tally` instead of sharing one map?
- What makes virtual threads a good fit for this test? Would `race` also work on virtual threads?
- What does p99 = 56 ms tell you that "average 20 ms" doesn't?
