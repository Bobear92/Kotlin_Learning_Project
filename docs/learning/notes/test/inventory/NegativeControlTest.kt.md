# NegativeControlTest.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/NegativeControlTest.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/NegativeControlTest.kt) · Last synced with code: 2026-09-30

## Role

The **negative control** from the oversell test plan in [TODO.md](../../../../../.claude/TODO.md).
A green race test only means something if it would go red on broken code. This class feeds a
deliberately naive purchase through the same race and the same check
([OversellAssertions.kt](OversellAssertions.kt.md)) as
[InventoryConcurrencyTest.kt](InventoryConcurrencyTest.kt.md), and asserts that the check fails.

The naive purchase, `NaiveInventoryRepository`, lives in `src/test`, so it never ships. Robert
wrote it (decided 2026-09-30).

## Walkthrough

### Setup

```kotlin
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class NegativeControlTest @Autowired constructor(
    private val jdbcClient: JdbcClient,
) {

    private val stock = 10
    private val buyers = 12
```

**Spring:** a full context with no test transaction, for the same reason as
`InventoryConcurrencyTest` (see its notes on per-thread transactions). The naive repository is not
a Spring bean. The test builds it with `NaiveInventoryRepository(jdbcClient) { ... }`, so each test
picks its own pause.

**Why 12 buyers for 10 units:** it's the scenario from the purchase walkthrough's last question.
There are more buyers than units, so a correct purchase must refuse two of them.

### A fresh `widget` for every test

```kotlin
/** Every test starts with one SKU, `widget`, holding [stock] units. */
@BeforeEach
fun seedWidget() {
    jdbcClient.sql("DELETE FROM inventory").update()
    jdbcClient.sql("INSERT INTO inventory (sku_id, quantity) VALUES ('widget', ?)").param(stock).update()
}
```

With no test transaction, each test cleans up at the start. `?` is a *positional* parameter, and
`.param(stock)` fills it; the real repository uses named ones (`:skuId`). The private `quantityOf`
helper below it is the same one as in
[InventoryConcurrencyTest.kt](InventoryConcurrencyTest.kt.md#the-new-sku-creation-race): a
`SELECT` straight from the database, with `singleOrNull()` giving `null` for a missing row.

### Control 1: the naive code works without a race

```kotlin
/** Without concurrency the naive code is correct, so a failure below comes from the race alone. */
@Test
fun `the naive purchase passes the oversell check when buyers take turns`() {
    val naive = NaiveInventoryRepository(jdbcClient) {}

    val outcomes = (1..buyers).map { naive.removeStock("widget", 1) }

    assertNoOversell(stock, outcomes, quantityOf("widget"))
}
```

**Why:** without this test, the next one could "catch" a naive repository that is simply buggy
(say, it subtracts 2). Passing here pins the blame for the failure below on concurrency alone.

**Kotlin: an empty lambda.** `{}` is a function that takes nothing and does nothing: no pause
between the read and the write. It's written after the parentheses, as a trailing lambda.

**Kotlin: `(1..buyers).map { ... }`.** Runs the purchase twelve times, one after another on the
test thread, and collects the results in a list.

### Control 2: the check catches the race

```kotlin
// Why: a real race only sometimes interleaves badly. The barrier holds every buyer between its
// read and its write until all of them have read, so the lost update happens on every run.
...
@Test
fun `the oversell check catches a naive purchase under a race`() {
    val barrier = CyclicBarrier(buyers)
    val naive = NaiveInventoryRepository(jdbcClient) { barrier.await(10, TimeUnit.SECONDS) }

    val results = race(buyers) { naive.removeStock("widget", 1) }

    val failures = results.mapNotNull { it.exceptionOrNull() }
    assertTrue(failures.isEmpty(), "the naive purchases raised errors: $failures")
    val outcomes = results.map { it.getOrThrow() }
    val caught = assertFailsWith<AssertionError> {
        assertNoOversell(stock, outcomes, quantityOf("widget"))
    }
    assertTrue(caught.message.orEmpty().startsWith("oversold"), "expected an oversell, got: ${caught.message}")
}
```

**Java concurrency: `CyclicBarrier`.** A meeting point for a fixed number of threads. Each thread
calls `await()` and blocks until all `buyers` threads have arrived; then all of them continue at
once. Compare the `CountDownLatch` in [Race.kt](../support/Race.kt.md): a latch is counted down by
anyone and opened once, while a barrier is where the waiting threads themselves meet (and it can be
reused, hence "cyclic"). The 10-second timeout turns a stuck thread into a
`TimeoutException` instead of a hang.

**Why the barrier sits between the read and the write:** `race` only makes the buyers *start*
together. Whether two of them then land between each other's read and write is luck. The barrier
removes the luck: no buyer writes until every buyer has read `10`. Then all twelve pass the Kotlin
check and all twelve write `9`. The test is deterministic, so it can't flake.

**What the test proves:**

- No errors: the naive code raises nothing, and the database's `CHECK (quantity >= 0)` never
  fires, because every value written (`9`) is valid on its own.
- The oversell check fails on its `oversold` assertion ("oversold: 12 purchases succeeded for a
  stock of 10"), the first invariant check after the "SKU exists" sanity check.

**Kotlin: `assertFailsWith<T> { }`.** Runs the block and passes only if it throws a `T`; it returns
the exception so you can inspect it. JUnit's Java equivalent is
`assertThrows(AssertionError.class, () -> ...)`. The reified type parameter `<AssertionError>`
replaces the `.class` argument.

**Kotlin: `orEmpty()`.** `caught.message` is a `String?`. `orEmpty()` turns `null` into `""`, so
`startsWith` can be called without a null check.

## Check your understanding

- What would it mean if control 1 failed? Why is it there at all?
- Without the `CyclicBarrier`, would control 2 still pass every time? What would you see instead?
- Why does the naive code never trigger `CHECK (quantity >= 0)`?
- `CountDownLatch` vs. `CyclicBarrier`: which one does `race` use, which one does the naive
  purchase use, and why does each fit its job?
- Why does the test insist the failure message starts with `oversold`, instead of accepting any
  `AssertionError`?
