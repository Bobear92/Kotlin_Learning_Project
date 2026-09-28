# InventoryConcurrencyTest.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryConcurrencyTest.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryConcurrencyTest.kt) · Last synced with code: 2026-09-28

## Role

This is the first part of the **concurrency** section of the oversell test plan in
[TODO.md](../../../../../.claude/TODO.md). Many threads call `InventoryRepository.addStock` for the
same SKU at the same moment, and the tests check that no update is lost and none fails. The
purchase race tests will join this class when `POST /inventory/{skuId}/purchase` exists.

The other test classes send one request at a time, so they can't catch a race. This one can.

## Walkthrough

### Why a full context and not `@JdbcTest`

```kotlin
// Why: @SpringBootTest, not @JdbcTest. @JdbcTest's rolled-back transaction belongs to the test thread
// only: rows it inserts stay invisible to the worker threads, and the workers' commits escape the rollback.
/**
 * Oversell test plan, concurrency section: ...
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class InventoryConcurrencyTest @Autowired constructor(
    private val inventoryRepository: InventoryRepository,
    private val jdbcClient: JdbcClient,
) {
```

**Spring: test transactions are per thread.** `@JdbcTest` (see
[InventoryRepositoryTest.kt](InventoryRepositoryTest.kt.md)) opens a transaction on the test's
thread and rolls it back at the end. Spring ties that transaction to the **thread**. Worker
threads started by the test aren't part of it: each takes its own connection from the pool and
commits on its own. That causes two problems:

- A row the test inserts before the race (`widget` with 10) is uncommitted, so the workers can't
  see it. Worse, each worker's upsert would wait on that row's lock, which the test thread holds
  until the rollback, so the race would hang until the timeout.
- The workers' rows are committed for real, so the rollback wouldn't clean them up, and they'd
  leak into the next test.

So this class runs with no test transaction. Every call commits, exactly like in production,
and the `@BeforeEach` `DELETE` does the cleanup (the same pattern as
[InventoryControllerTest.kt](InventoryControllerTest.kt.md)).

**Spring: context caching.** These annotations differ from `InventoryControllerTest` (there's no
`@AutoConfigureMockMvc`), so Spring caches a separate context for this class, with its own
Postgres container. That costs a few seconds of startup, and in return the class doesn't depend on
MockMvc, which isn't meant to be called from many threads.

### The start gate

```kotlin
// Why: a start gate. Every thread first checks in on `ready`, then waits on `start`; releasing
// `start` once makes them all hit the database together instead of one after another.
private fun <T> race(threads: Int, action: (Int) -> T): List<Result<T>> {
    val pool = Executors.newFixedThreadPool(threads)
    val ready = CountDownLatch(threads)
    val start = CountDownLatch(1)
    try {
        val futures = (0 until threads).map { i ->
            pool.submit(Callable {
                ready.countDown()
                start.await()
                action(i)
            })
        }
        ready.await()
        start.countDown()
        return futures.map { runCatching { it.get(30, TimeUnit.SECONDS) } }
    } finally {
        pool.shutdownNow()
    }
}
```

**Why a gate at all:** starting 50 threads one by one in a loop spreads them out in time. The
first threads may finish before the last ones start, and then there's no race to test. The gate
makes them all wait at the same line and releases them together.

**Java concurrency: `CountDownLatch`.** A counter threads can wait on. `countDown()` decrements
it; `await()` blocks until it reaches zero. There are two latches here:

1. `ready` starts at `threads`. Each worker counts it down as it arrives, and the test thread's
   `ready.await()` returns once all of them are waiting.
2. `start` starts at 1. Every worker blocks on `start.await()`. The test's single
   `start.countDown()` releases all of them at once.

**Java concurrency: thread pool and futures.** `Executors.newFixedThreadPool(threads)` makes one
thread per worker, so all of them can wait at the gate together (a smaller pool would deadlock:
the queued tasks could never arrive at `ready`). `submit` returns a `Future`; `get(30, SECONDS)`
waits for its result and rethrows anything the task threw, wrapped in an `ExecutionException`.
The timeout makes a hung test fail instead of running forever. `shutdownNow()` in `finally`
stops the threads even when something failed.

**Kotlin: a generic function.** `fun <T> race(..., action: (Int) -> T): List<Result<T>>` works for
any result type. `T` is whatever `action` returns: `InventoryItem?` for `addStock`, and later
something else for purchases. Java writes it the same way: `<T> List<Result<T>> race(...)`.

**Kotlin: function types.** `action: (Int) -> T` is a parameter that holds a function taking an
`Int` (the thread's index) and returning a `T`. Java would use `IntFunction<T>` or
`Function<Integer, T>`. The call sites pass a trailing lambda: `race(threads) { i -> ... }`.

**Kotlin: `Callable { ... }`.** `submit` is overloaded for `Runnable` (no result) and `Callable`
(returns a result). Writing `Callable { ... }` picks the one that returns a value. This is a SAM
conversion, like the row mapper in [InventoryRepository.kt](../../main/inventory/InventoryRepository.kt.md).

**Kotlin: ranges.** `0 until threads` is `0, 1, ..., threads - 1` (the end is excluded), and
`.map { i -> ... }` turns each index into a submitted task. Java: `IntStream.range(0, threads)`.

**Kotlin: `runCatching` and `Result`.** `runCatching { block }` runs the block and returns a
`Result`: a success holding the value, or a failure holding the exception. Nothing is thrown.
That lets the test collect *every* thread's outcome first, then assert on all of them together.
`exceptionOrNull()` and `getOrNull()` read the two sides. Java has no built-in equivalent; you'd
catch the exception in a loop and keep it in a list.

### The new-SKU creation race

```kotlin
@RepeatedTest(5)
fun `concurrent first-time adds for a new SKU all succeed and sum up`() {
    val threads = 50

    val results = race(threads) { i -> inventoryRepository.addStock("new-sku", i + 1) }

    val failures = results.mapNotNull { it.exceptionOrNull() }
    assertTrue(failures.isEmpty(), "adds failed: $failures")
    assertTrue(results.all { it.getOrNull() != null }, "an add returned null (treated as overflow)")
    assertEquals((1..threads).sum(), quantityOf("new-sku"))
}
```

**What it catches:** the naive way to "create or add" takes two steps. First check whether the
row exists, then either `INSERT` or `UPDATE`. Two threads can both see "no row" and both
`INSERT`. The second one fails with a duplicate-key error (the primary key), and its stock is
lost. A single `INSERT ... ON CONFLICT DO UPDATE` statement can't split like that: Postgres
handles the conflict inside the one statement.

**Why each thread adds a different amount (`i + 1`):** if every thread added the same amount,
losing one add and double-counting another would cancel out. With 1, 2, ..., 50 the expected
total is `(1..50).sum()` = 1275, and a lost or repeated add shows up in the final number.

**Kotlin: `mapNotNull`, `all`, `sum`.** `mapNotNull` maps each element and drops the `null`s,
leaving only the exceptions. `all { }` is `true` if the condition holds for every element.
`(1..threads).sum()` adds up an inclusive range (`..` includes the end, `until` doesn't).

**Kotlin: assertion messages.** The last argument of `assertTrue` is the message printed on
failure. `"adds failed: $failures"` shows *which* exceptions happened, which beats a bare
"expected true".

**JUnit: `@RepeatedTest(5)`.** Runs the test five times, each a separate result with its own
`@BeforeEach`. Races are intermittent; a broken implementation might pass once by luck, but
rarely five times in a row.

### The lost-update race

```kotlin
@RepeatedTest(5)
fun `concurrent adds to an existing SKU are never lost`() {
    jdbcClient.sql("INSERT INTO inventory (sku_id, quantity) VALUES ('widget', 10)").update()
    val threads = 50

    val results = race(threads) { inventoryRepository.addStock("widget", 2) }

    val failures = results.mapNotNull { it.exceptionOrNull() }
    assertTrue(failures.isEmpty(), "adds failed: $failures")
    assertEquals(10 + threads * 2, quantityOf("widget"))
}
```

**What it catches:** a read-then-write implementation (read 10, compute 12, write 12). Two
threads that both read 10 both write 12, and one add disappears. That's a *lost update*. With
`SET quantity = inventory.quantity + EXCLUDED.quantity`, Postgres does the read and the write
under a row lock inside one statement, so the second thread waits and then adds to 12.

**Kotlin: an implicit lambda parameter.** `race(threads) { inventoryRepository.addStock(...) }`
doesn't use the index, so the lambda declares no parameter. A lambda with one parameter can
leave it out; if needed, it's available as `it`.

## Check your understanding

- Why would these tests be unreliable under `@JdbcTest`? Give both reasons.
- What happens to the race if you delete the `start` latch and keep only `ready`? And if you
  delete both?
- Why must the thread pool have `threads` threads, and not, say, 10?
- The new-SKU test adds `i + 1` rather than a constant. Describe a bug the constant version
  would miss.
- `race` returns `List<Result<T>>` instead of throwing on the first failure. What would the
  test report lose if it threw?
- Why run each test five times? Does passing five times prove there's no race?
