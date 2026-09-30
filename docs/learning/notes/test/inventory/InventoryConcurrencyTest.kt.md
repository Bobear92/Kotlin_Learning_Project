# InventoryConcurrencyTest.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryConcurrencyTest.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryConcurrencyTest.kt) · Last synced with code: 2026-09-30

## Role

This is the **concurrency** section of the oversell test plan in
[TODO.md](../../../../../.claude/TODO.md). Many threads call `InventoryRepository.addStock` and
`removeStock` for the same SKU at the same moment. The tests check that no update is lost, none
fails, and, above all, that **no purchase ever oversells**. The three purchase races were added
on 2026-09-29, before Robert wrote `removeStock`, as its spec.

The other test classes send one request at a time, so they can't catch a race. This one can.

## Walkthrough

### Why a full context and not `@JdbcTest`

```kotlin
// Why: @SpringBootTest, not @JdbcTest. @JdbcTest's rolled-back transaction belongs to the test thread
// only: rows it inserts stay invisible to the worker threads, and the workers' commits escape the rollback.
/**
 * Oversell test plan, concurrency section: many threads change the same SKU at the same moment
 * through [InventoryRepository], and no update may be lost or fail. Covers the new-SKU creation
 * race, concurrent restocks of an existing SKU, and the purchase races: same-size buyers, uneven
 * quantities, and restocks mixed with purchases. No race may ever oversell.
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

Every race below runs through `race(threads) { i -> ... }`, the start gate in
[support/Race.kt](../support/Race.kt.md). That file's notes explain the two `CountDownLatch`es,
the thread pool, generic functions, function types and `runCatching`. The helper moved there on
2026-09-30 so the negative control and the HTTP races can share it.

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

### The same-SKU purchase race

```kotlin
// Why: assertNoOversell also checks the "remaining" values, proving each unit was sold exactly
// once; NegativeControlTest shows the same check catching a read-then-write purchase.
/**
 * Same-SKU purchase race: with stock S and N > S buyers of 1, exactly S succeed, N - S are refused
 * as insufficient stock, and the SKU ends at 0, never negative.
 */
@RepeatedTest(5)
fun `concurrent purchases never sell more than the stock`() {
    val stock = 10
    jdbcClient.sql("INSERT INTO inventory (sku_id, quantity) VALUES ('widget', ?)").param(stock).update()
    val threads = 50

    val results = race(threads) { inventoryRepository.removeStock("widget", 1) }

    val failures = results.mapNotNull { it.exceptionOrNull() }
    assertTrue(failures.isEmpty(), "purchases failed: $failures")
    assertNoOversell(stock, results.map { it.getOrThrow() }, quantityOf("widget"))
}
```

**What it catches:** the oversell itself. The naive purchase reads the stock, checks
`stock >= quantity` in Kotlin, then writes `stock - quantity`. Between the read and the write,
another buyer can do the same, and both sell the same last unit. The conditional
`UPDATE ... WHERE quantity >= :quantity` does the check and the write in one statement under a row
lock, so the second buyer waits and then re-checks against the new value.

**Why `CHECK (quantity >= 0)` wouldn't catch the naive version (walkthrough, 2026-09-29):** the
naive code writes an absolute value (`SET quantity = 9`), not a relative one. Picture twelve buyers
who all read 10 at once:

| | Buyers 1–12 (together) | Buyer 13 (a moment later) |
| --- | --- | --- |
| Read | all read `10` | reads `9` |
| Kotlin check | `10 >= 1` passes for all | passes |
| Write | all run `SET quantity = 9` | `SET quantity = 8` |
| Returned | twelve `Purchased(widget, 9)` | `Purchased(widget, 8)` |

More than 10 units are "sold", yet every value written is `>= 0`, so the constraint never fires.
The stock can even end *above* 0. The constraint protects "stock is never negative", not "every
unit is sold once". The shared oversell check's first assertion is the one that fails.

**The checks live in [OversellAssertions.kt](OversellAssertions.kt.md).** `assertNoOversell`
asserts that nothing is oversold, each unit is sold exactly once (the remaining values are
`0..9`), exactly `threads - stock` buyers are refused, and the SKU ends at 0. It's shared so
[NegativeControlTest.kt](NegativeControlTest.kt.md) can prove these very checks catch the naive
purchase from the table above.

**Kotlin: `getOrThrow()`.** Reads a `Result`'s value, or rethrows its exception. It's safe here
because the line before already asserted there are no failures.

### Uneven quantities

```kotlin
@RepeatedTest(5)
fun `concurrent purchases of different sizes never oversell`() {
    val stock = 100
    jdbcClient.sql("INSERT INTO inventory (sku_id, quantity) VALUES ('widget', ?)").param(stock).update()
    val threads = 50

    val results = race(threads) { i ->
        val quantity = i % 7 + 1
        quantity to inventoryRepository.removeStock("widget", quantity)
    }

    val failures = results.mapNotNull { it.exceptionOrNull() }
    assertTrue(failures.isEmpty(), "purchases failed: $failures")
    val outcomes = results.map { it.getOrThrow() }
    assertTrue(outcomes.none { (_, result) -> result == PurchaseResult.SkuNotFound }, "an existing SKU was reported missing")
    val sold = outcomes.filter { (_, result) -> result is PurchaseResult.Purchased }.sumOf { (quantity, _) -> quantity }
    assertTrue(sold <= stock, "sold $sold of $stock")
    assertEquals(stock - sold, quantityOf("widget"))
}
```

**What it catches:** a check that only works for purchases of 1, for example comparing against
`> 0` instead of `>= :quantity`. The buyers ask for 1 to 7 units (`i % 7 + 1`), about 200 in total
against a stock of 100, so the SKU runs low and a big order can be refused while a small one
still fits.

**Why not assert an exact count:** which buyers win depends on thread timing, so the test asserts
the invariant instead. What was sold never exceeds the stock, and the database agrees exactly with
what the successful buyers were told.

**Kotlin: `to` and `Pair`.** `quantity to result` builds a `Pair(quantity, result)`. `to` is an
*infix* function: called without a dot or parentheses. Each thread returns what it asked for next
to what happened, so the test can add up the successful quantities.

**Kotlin: destructuring in lambdas.** `{ (_, result) -> ... }` unpacks the `Pair` into its two
parts right in the parameter list; `_` skips the part the lambda doesn't need. `sumOf { }` adds up
whatever the lambda returns for each element.

### Restocks mixed with purchases

```kotlin
// Why: demand (30 x 5 = 150) exceeds supply (20 + 30 x 3 = 110), so some purchases are refused
// and the test exercises both outcomes under contention.
...
@RepeatedTest(5)
fun `concurrent restocks and purchases keep an exact balance`() {
    val initial = 20
    jdbcClient.sql("INSERT INTO inventory (sku_id, quantity) VALUES ('widget', ?)").param(initial).update()
    val threads = 60

    val results = race(threads) { i ->
        if (i % 2 == 0) {
            checkNotNull(inventoryRepository.addStock("widget", 3)) { "restock refused" }
            3
        } else {
            when (inventoryRepository.removeStock("widget", 5)) {
                is PurchaseResult.Purchased -> -5
                PurchaseResult.InsufficientStock -> 0
                PurchaseResult.SkuNotFound -> error("an existing SKU was reported missing")
            }
        }
    }

    val failures = results.mapNotNull { it.exceptionOrNull() }
    assertTrue(failures.isEmpty(), "operations failed: $failures")
    assertEquals(initial + results.sumOf { it.getOrThrow() }, quantityOf("widget"))
}
```

**What it catches:** interference between the two writes. The upsert and the conditional
`UPDATE` both lock the same row; if either did a read-then-write, a restock could overwrite a
purchase (or the other way round) and the books wouldn't balance. Each thread returns its change
to the stock (`+3`, `-5` or `0`), and the final quantity must equal the initial one plus the sum
of the changes.

**Kotlin: `if` and `when` are expressions.** The lambda's last expression is its return value, and
here that's an `if`/`else` whose branches end in `3` or a `when`. In Java both would be
statements, and you'd need a variable or `return`s.

**Kotlin: an exhaustive `when` over a sealed type.** `PurchaseResult` is sealed, so the compiler
knows all its cases. This `when` lists all three and needs no `else`. If someone adds a fourth
case, this code stops compiling until it's handled. `is PurchaseResult.Purchased` checks the type
(a data class, with a new instance each time), while the two objects are compared by value.

**Kotlin: `checkNotNull` and `error`.** `checkNotNull(x) { "msg" }` returns `x` if it isn't null,
otherwise throws `IllegalStateException("msg")`. `error("msg")` always throws one. Inside `race`,
`runCatching` collects that exception, so the `failures` assertion reports it.

## Check your understanding

- Why would these tests be unreliable under `@JdbcTest`? Give both reasons.
- The new-SKU test adds `i + 1` rather than a constant. Describe a bug the constant version
  would miss.
- Why run each test five times? Does passing five times prove there's no race?
- In the same-SKU race, a read-then-write purchase sells 12 units out of 10. Which checks in
  `assertNoOversell` would fail, and what does each one show?
- The uneven-quantities test asserts `sold <= stock`, not `sold == stock`. Why can't it expect all
  100 units to sell?
- In the mixed race, why does the `when` need no `else` branch? What happens to it if a fourth
  `PurchaseResult` case is added?
