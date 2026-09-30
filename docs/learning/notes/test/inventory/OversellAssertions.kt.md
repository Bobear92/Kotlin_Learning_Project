# OversellAssertions.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/OversellAssertions.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/OversellAssertions.kt) · Last synced with code: 2026-09-30

## Role

The oversell invariant, written once as a function. After a race of single-unit purchases, it
checks that nothing was oversold. Two tests call it:

- [InventoryConcurrencyTest.kt](InventoryConcurrencyTest.kt.md) runs it against the real
  `InventoryRepository.removeStock` and expects it to **pass**.
- [NegativeControlTest.kt](NegativeControlTest.kt.md) runs it against a naive read-then-write
  purchase and expects it to **fail**.

## Walkthrough

### Why the check is shared

```kotlin
// Why: one shared check, so the negative control proves that the exact assertions guarding the
// real repository can detect an oversell, not a separate check written to fail.
```

**Why:** a negative control is only worth something if it tests the *same* check. If the negative
control had its own assertions, it would only prove that *those* can fail. The real race test
could still have a weak check (say, only "final quantity >= 0", which the naive code passes).
Sharing one function removes that gap.

**Kotlin: a top-level function in the test package.** Like `race` in
[Race.kt](../support/Race.kt.md), it lives directly in the file. It sits in the `inventory`
package, next to the tests, because it works on `PurchaseResult`.

### The precondition

```kotlin
fun assertNoOversell(stock: Int, outcomes: List<PurchaseResult>, finalQuantity: Int?) {
    require(outcomes.size >= stock) { "needs at least $stock buyers, got ${outcomes.size}" }
    assertTrue(outcomes.none { it == PurchaseResult.SkuNotFound }, "an existing SKU was reported missing")
```

**Kotlin: `require` vs. an assertion.** `require` throws `IllegalArgumentException`: *the caller*
used the function wrongly (fewer buyers than units, so "exactly `stock` succeed" can't be
expected). The `assert...` functions throw `AssertionError`: *the code under test* is wrong.
Keeping them apart matters in the negative control, which expects an `AssertionError`, so a
misconfigured test can't pass by accident. `require` was introduced in
[InventoryService.kt](../../main/inventory/InventoryService.kt.md).

**Kotlin: `none { }`.** True when no element matches; the opposite of `any { }`.

**Kotlin: comparing to a `data object`.** `it == PurchaseResult.SkuNotFound` works because a
`data object` is a single instance with a generated `equals`.

### The four checks, in order

```kotlin
val remaining = outcomes.filterIsInstance<PurchaseResult.Purchased>().map { it.item.quantity }.sorted()
assertTrue(remaining.size <= stock, "oversold: ${remaining.size} purchases succeeded for a stock of $stock")
assertEquals((0 until stock).toList(), remaining, "each unit must be sold exactly once")
assertEquals(outcomes.size - stock, outcomes.count { it == PurchaseResult.InsufficientStock }, "refused purchases")
assertEquals(0, finalQuantity, "final quantity")
```

Each check catches a different bug:

1. **Oversold:** more purchases succeeded than there were units. This is the invariant itself,
   so it goes first, and its message starts with `oversold` (the negative control checks for it).
2. **Each unit sold exactly once:** every successful buyer was told a *different* remaining
   quantity, `0, 1, ..., stock - 1`. A lost update shows up as a repeated value.
3. **Refused purchases:** everyone who didn't get a unit got `InsufficientStock`.
4. **Final quantity 0:** not negative, and not above 0 (a buyer refused while stock remained).

**Kotlin: `filterIsInstance<T>()`.** Keeps only the elements that are a `T`, *and* gives back a
`List<T>`, so `it.item` compiles without a cast. It's the list version of the smart cast after
`is`. Java: `.filter(x -> x instanceof Purchased).map(x -> (Purchased) x)`.

**Kotlin: assertion messages.** `kotlin.test`'s `assertEquals(expected, actual, message)` takes the
message last (JUnit's `assertEquals` also takes it last in JUnit 5+). The message is what you read
first when a test fails, so each one names the rule that broke.

## Check your understanding

- Why is the negative control only convincing if it calls this same function?
- Why does the function use `require` for the buyer count but `assertTrue` for everything else?
- The naive purchase has twelve buyers who all read 10 and write 9. Which of the four checks
  would fail? Which one fails first, and why is that the one the negative control looks for?
- Why can't a `CHECK (quantity >= 0)` constraint replace check 1?
