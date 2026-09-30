# InventoryPropertyTest.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryPropertyTest.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryPropertyTest.kt) · Last synced with code: 2026-09-30

## Role

The **model-based** item of the oversell test plan in [TODO.md](../../../../../.claude/TODO.md).
Every other test checks examples someone thought of ("stock 5, buy 2"). This one states a *rule*
and lets a library invent the examples:

> For **any** sequence of receives and purchases, the repository gives the same answers as a
> simple in-memory model, holds the same stock after every step, and never goes negative.

Kotest generates 200 random sequences of 1 to 30 operations and runs each one against both the
real [InventoryRepository](../../main/inventory/InventoryRepository.kt.md) (in Postgres) and the
model. It's single-threaded on purpose: [InventoryConcurrencyTest.kt](InventoryConcurrencyTest.kt.md)
covers races. This test covers *orderings* nobody would write by hand, such as buying from a
SKU that was just sold out, then restocking it, then buying exactly the stock.

## Walkthrough

### The setup

```kotlin
@JdbcTest
@Import(TestcontainersConfiguration::class, InventoryRepository::class)
class InventoryPropertyTest @Autowired constructor(
    private val inventoryRepository: InventoryRepository,
    private val jdbcClient: JdbcClient,
) {
```

**Spring:** the same slice as [InventoryRepositoryTest.kt](InventoryRepositoryTest.kt.md): only the
database, Flyway and the repository. Everything runs on the test thread, so `@JdbcTest`'s rolled-back
transaction is fine here (unlike in the race tests). Each generated sequence starts with a `DELETE`
inside that transaction, and the rollback at the end cleans up the last one.

### The operations: a private sealed interface

```kotlin
/** One step of a generated sequence. */
private sealed interface Operation {
    val skuId: String
    val quantity: Int

    /** `POST /inventory/{skuId}`: add stock, creating the SKU if needed. */
    data class Receive(override val skuId: String, override val quantity: Int) : Operation

    /** `POST /inventory/{skuId}/purchase`: take stock if there is enough. */
    data class Purchase(override val skuId: String, override val quantity: Int) : Operation
}
```

**Kotlin: a sealed interface, again.** The same idea as
[PurchaseResult.kt](../../main/inventory/PurchaseResult.kt.md): a closed set of cases, so the `when`
below needs no `else`. New here: the interface declares properties (`val skuId`), and each case
must `override` them.

**Kotlin: nested and `private`.** Declared inside the test class and `private`, so nothing outside
this file can see it. Java would write a `private static` nested type.

**Why data classes:** generated `toString()`. When a property fails, Kotest prints the failing
sequence, and `Receive(skuId=a, quantity=9)` is readable as it is.

### The model

```kotlin
// Why: the obviously-correct version of the rules, with no SQL and no concurrency. If the
// repository ever disagrees with it, one of the two is wrong, and the model is easy to check by eye.
/** In-memory inventory: a map from SKU to stock, following the same rules as the contract. */
private class InventoryModel {
    private val stock = mutableMapOf<String, Int>()

    fun receive(skuId: String, quantity: Int): InventoryItem {
        val newQuantity = stock.getOrDefault(skuId, 0) + quantity
        stock[skuId] = newQuantity
        return InventoryItem(skuId, newQuantity)
    }

    fun purchase(skuId: String, quantity: Int): PurchaseResult {
        val current = stock[skuId] ?: return PurchaseResult.SkuNotFound
        if (current < quantity) return PurchaseResult.InsufficientStock
        stock[skuId] = current - quantity
        return PurchaseResult.Purchased(InventoryItem(skuId, current - quantity))
    }

    fun items(): List<InventoryItem> = stock.map { (skuId, quantity) -> InventoryItem(skuId, quantity) }.sortedBy { it.skuId }
}
```

**Why a model:** the repository's SQL is clever (an upsert, a data-modifying CTE). The model is
boring on purpose, a dozen lines anyone can verify by reading. It returns the same types as the
repository (`InventoryItem`, `PurchaseResult`), so the two can be compared with `==`.

**Kotlin: `mutableMapOf` and the index operator.** `mutableMapOf<String, Int>()` is an empty
`MutableMap` (a `LinkedHashMap`, Java: `new LinkedHashMap<>()`). `stock[skuId]` reads, returning
`Int?` (null when absent); `stock[skuId] = x` writes (Java: `get` and `put`).
`getOrDefault(skuId, 0)` treats a missing SKU as 0.

**Kotlin: `?: return`.** `val current = stock[skuId] ?: return PurchaseResult.SkuNotFound` either
gets the stock, or leaves the function right there with `SkuNotFound`. `return` is an expression in
Kotlin (of type `Nothing`, see [InventoryService.kt](../../main/inventory/InventoryService.kt.md)),
so it can sit on the right of the Elvis operator. After that line, `current` is a plain `Int`.

**Kotlin: destructuring a map entry.** `stock.map { (skuId, quantity) -> ... }` unpacks each entry
into key and value. `sortedBy { it.skuId }` matches the repository's `ORDER BY sku_id`, so the two
lists can be compared directly.

### The generators

```kotlin
// Why: three SKUs so operations collide on the same row often; purchases up to 15 against
// receives up to 10, so "insufficient" is common; "c" is never received, so "not found" happens too.
private val skuIds = Arb.element("a", "b", "c")
private val operation: Arb<Operation> = Arb.choice(
    Arb.bind(Arb.element("a", "b"), Arb.int(1..10)) { skuId, quantity -> Operation.Receive(skuId, quantity) },
    Arb.bind(skuIds, Arb.int(1..15)) { skuId, quantity -> Operation.Purchase(skuId, quantity) },
)
```

**Kotest: `Arb`.** An *arbitrary*: a generator of random values of one type, which also knows how to
make a value "smaller" (see shrinking below). They combine:

- `Arb.element("a", "b", "c")` picks one of the listed values.
- `Arb.int(1..10)` picks an `Int` in the range.
- `Arb.bind(a, b) { x, y -> ... }` draws from both and builds something from the pair.
- `Arb.choice(a, b)` draws from one of several generators, which is how a sequence mixes
  receives and purchases.
- `Arb.list(operation, 1..30)` (in the test) makes a list of 1 to 30 operations.

**Why these numbers:** random inputs are only useful if they reach the interesting cases. A
thousand SKUs would rarely touch the same row twice; purchases no bigger than receives would
rarely be refused. The ranges are tuned so every `PurchaseResult` case shows up often.

### The property

```kotlin
// Why: a block body, not `= runBlocking { ... }`. That form would return checkAll's result, and
// JUnit doesn't run @Test methods that don't return Unit (at most a discovery warning Gradle hides).
...
@Test
fun `the repository behaves like the in-memory model for any sequence of operations`() {
    runBlocking {
        checkAll(PropTestConfig(iterations = 200), Arb.list(operation, 1..30)) { operations ->
            jdbcClient.sql("DELETE FROM inventory").update()
            val model = InventoryModel()

            operations.forEachIndexed { step, operation ->
                val context = "step $step of $operations"
                when (operation) {
                    is Operation.Receive -> assertEquals(
                        model.receive(operation.skuId, operation.quantity),
                        inventoryRepository.addStock(operation.skuId, operation.quantity),
                        "receive at $context",
                    )
                    is Operation.Purchase -> assertEquals(
                        model.purchase(operation.skuId, operation.quantity),
                        inventoryRepository.removeStock(operation.skuId, operation.quantity),
                        "purchase at $context",
                    )
                }
                val stored = inventoryRepository.findAll()
                assertEquals(model.items(), stored, "stock after $context")
                assertTrue(stored.all { it.quantity >= 0 }, "negative stock after $context: $stored")
            }
        }
    }
}
```

**Kotest: `checkAll`.** Runs the block 200 times, each time with a fresh random list. If the block
throws (a failed `assertEquals`), the property fails. The block is a *suspend* lambda (it may call
other `suspend` functions); ordinary `kotlin.test` assertions still work inside it.

**Kotlin: `suspend` and `runBlocking` (a first look at coroutines).** `checkAll` is a `suspend`
function: one that can pause and resume without holding a thread (Kotlin's coroutines). A
`suspend` function can only be called from another `suspend` function or a coroutine.
`runBlocking { }` is the bridge: it starts a coroutine and blocks the current thread until it
finishes, so ordinary code, like a JUnit test, can call `suspend` code. It's fine in tests and
`main`; in production request handling it would waste the thread coroutines are meant to free.

**JUnit: why the block body (found 2026-09-30).** The first version was
`fun test() = runBlocking { checkAll(...) }`. With `=`, the function's return type is whatever
`runBlocking` returns, here `checkAll`'s result object, not `Unit`. JUnit only runs `@Test`
methods that return `void`. It doesn't run the others; since JUnit 5.13 it at most logs a discovery
warning, which Gradle doesn't show. All Gradle said was "No tests found".
Compare the `= { }` pitfall in [NaiveInventoryRepository.kt](NaiveInventoryRepository.kt.md):
another case where `=` changes more than it seems.

**Why compare after every step,** not only at the end: a wrong answer in the middle can be
"repaired" by later operations (a refused purchase that should have succeeded, followed by a
restock), and the final stock would hide it. Checking each step also makes the failure message
point to the exact step.

**Kotlin: `forEachIndexed`.** Like `forEach`, but the lambda also gets the index (Java: a classic
`for (int i = 0; ...)` loop).

### Proof it can fail: shrinking

On 2026-09-30 the model was broken on purpose (`current <= quantity` instead of `<`, so buying the
last unit is refused) to see the property catch it. Kotest reported:

```
Property failed after 4 attempts

    Arg 0: [Receive(skuId=a, quantity=9), Purchase(skuId=a, quantity=9)]
    (shrunk from [Purchase(skuId=c, quantity=7), Receive(skuId=a, quantity=9), Purchase(skuId=a, quantity=9)])

Repeat this test by using seed 6716888165162368955

purchase at step 1 of [...] ==> expected: <InsufficientStock> but was: <Purchased(item=InventoryItem(skuId=a, quantity=0))>
```

**Kotest: shrinking.** The random sequence that failed had three steps, one of them irrelevant.
Kotest then tried smaller versions of it (fewer elements, smaller values) and kept the smallest
that still fails: *receive 9, buy exactly 9*. That's the bug in one line. Without shrinking you'd
debug a 30-step sequence.

**Kotest: the seed.** The random values come from a seed. Passing that seed back
(`PropTestConfig(seed = 6716888165162368955)`) replays the exact same run, which turns a random
failure into a reproducible one.

**What this test can't catch:** the naive read-then-write purchase from
[NaiveInventoryRepository.kt](NaiveInventoryRepository.kt.md) would *pass* here, because it's
correct when calls take turns. That's why the plan has both kinds of test: this one for logic over
any ordering, the race tests for concurrency.

## Check your understanding

- What does the model give you that a list of hand-written examples doesn't?
- Why does the purchase generator allow up to 15 while receives only go to 10? What would the test
  miss if both went up to 1000?
- Why does `"c"` only appear in purchases?
- Would the naive repository pass this test? Why is that fine?
- Why is `fun test() = runBlocking { ... }` a bug for JUnit, and why did Gradle only say
  "No tests found"?
- Kotest shrank a 3-step failure to 2 steps. Why is the smaller one more useful, and what does the
  seed let you do?
- Why check the stock after every step instead of once at the end?
