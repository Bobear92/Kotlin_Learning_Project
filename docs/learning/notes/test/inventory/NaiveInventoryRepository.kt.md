# NaiveInventoryRepository.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/NaiveInventoryRepository.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/NaiveInventoryRepository.kt) · Last synced with code: 2026-09-30

## Role

A **deliberately broken** purchase, used only by [NegativeControlTest.kt](NegativeControlTest.kt.md).
It does in three steps (read, check in Kotlin, write) what the real
[InventoryRepository.kt](../../main/inventory/InventoryRepository.kt.md) does in one SQL statement.
Alone it gives correct answers; under concurrency it loses updates and oversells. That's the
point: the negative control needs known-bad code to prove the oversell check can catch it.

Robert wrote it on 2026-09-30.

## Walkthrough

### The class: test-only, not a bean

```kotlin
// Why: This repository lives only in test because the class is deliberately unsafe.
// We have it so NegativeControlTest can prove the oversell check catches a lost update.
/**
 * A purchase done the naive way: read the stock, check it in Kotlin, then write an absolute value.
 * Correct when buyers take turns; under concurrency, buyers overwrite each other and it oversells.
 */
class NaiveInventoryRepository(
    private val jdbcClient: JdbcClient,
    private val pauseBeforeWrite: () -> Unit
) {
```

**Why `src/test`:** code under `src/test` is never packaged into the app, so this can't ship by
accident, yet it stays in the repo as permanent proof instead of a one-off experiment.

**Spring:** no `@Repository`. It isn't a bean. The test builds it by hand,
`NaiveInventoryRepository(jdbcClient) { ... }`, passing the `JdbcClient` bean it got injected.

**Kotlin: a function-type property.** `pauseBeforeWrite: () -> Unit` holds a function that takes
no arguments (`()`) and returns nothing useful (`Unit`). Read a function type as
`(arguments) -> ReturnType`; compare `action: (Int) -> T` in [Race.kt](../support/Race.kt.md).
Java would use a `Runnable`.

**Kotlin: `Unit`.** Kotlin's `void`, but a real type with exactly one value (hence the name). A
function with no declared return type returns `Unit`, so it can be used anywhere a type is needed,
like here.

**Kotlin: `private val` in the constructor.** It makes the parameter a stored property, so
`removeStock` can call it later. A bare parameter (no `val`) exists only while the object is built.

**KDoc: the `// Why:` vs. the KDoc.** The `// Why:` says why the class *exists* (test-only, unsafe
on purpose, for the negative control); the KDoc says what it *does* (the three steps and the
consequence). The method's KDoc names `[pauseBeforeWrite]` because *where* the pause happens is the
one thing a caller can't guess. Square brackets are KDoc links (Javadoc's `{@link}`).

**Kotlin: a trailing lambda for a constructor.** Because the function type is the *last*
parameter, callers can write the lambda after the parentheses: `NaiveInventoryRepository(jdbcClient) {}`.

### Step 1: read

```kotlin
/** Sells [quantity] of [skuId] in three separate steps, calling [pauseBeforeWrite] between the read and the write. */
fun removeStock(skuId: String, quantity: Int): PurchaseResult  {

    val current: Int? = jdbcClient.sql("SELECT quantity FROM inventory WHERE sku_id = :skuId")

        .param("skuId", skuId)
        .query{ rs, _ ->
             rs.getInt("quantity")
        }
        .list().singleOrNull()
```

**Kotlin: block body vs. expression body** (first explained in
[InventoryService.kt](../../main/inventory/InventoryService.kt.md)). `findBySkuId` in the real repository is
`fun f(): T = <one expression>`. This function does several steps, so it has a block body: braces,
statements, and an explicit `return`. Watch out for `fun f(): T = { ... }`: with the `=`, the
braces create a *lambda*, and the function would return a function instead of running the code.

**Kotlin: a lambda returns its last expression.** The row mapper's body is just
`rs.getInt("quantity")`, and that value is what it returns for the row. Writing
`val x = rs.getInt(...)` as the last line would make the lambda return `Unit` instead.

**Why `getInt` and not `getObject(..., javaObjectType)`:** the real repository needs the boxed
version because its CTE can return a row whose value is `NULL`. Here a missing SKU means *no row*:
the mapper never runs, `.list()` is empty, and `.singleOrNull()` gives `null`. So `current` is an
`Int?` without the column ever being `NULL`.

### Steps 2 and 3: decide and compute in Kotlin

```kotlin
if(current == null) return PurchaseResult.SkuNotFound
if(quantity > current) return PurchaseResult.InsufficientStock

val newQuantity = current - quantity
```

**Kotlin: smart cast after an early return.** After `if (current == null) return ...`, the compiler
knows `current` can't be `null` on the following lines, so `quantity > current` and
`current - quantity` compile as plain `Int` math, with no `!!`.

**Why this is the bug:** the check uses a value read *earlier*. By the time the write happens, other
buyers may have changed the stock, and this code never looks again. The real repository puts the
check in the `UPDATE`'s `WHERE quantity >= :quantity`, so Postgres checks the current value under
the row lock.

### Step 4: the pause

```kotlin
pauseBeforeWrite()
```

**Kotlin:** a function stored in a property is called with parentheses, like any function.

**Why:** it widens the gap between the read and the write on purpose. `NegativeControlTest` passes
`{}` (no pause) when buyers take turns, and `{ barrier.await(...) }` when they race, so every buyer
reads before anyone writes.

### Step 5 and 6: the absolute write

```kotlin
jdbcClient.sql("UPDATE inventory SET quantity = :quantity WHERE sku_id = :skuId")
.param("skuId", skuId)
.param("quantity", newQuantity)
.update()

return PurchaseResult.Purchased(InventoryItem(skuId, newQuantity))
```

**Why this loses updates:** `SET quantity = 9` overwrites whatever is there. Twelve buyers who all
read 10 all write 9; eleven sales vanish. The real code writes relative to the current value
(`quantity - :quantity`) inside one statement, so nothing is overwritten. And since 9 is a valid
value, `CHECK (quantity >= 0)` never fires.

**Spring: `.update()`.** Runs the statement given to `.sql(...)` and returns the number of rows it
changed, not the new quantity. That's why the returned `InventoryItem` is built from `newQuantity`.

## Check your understanding

- Point to the exact gap where another buyer can slip in. What does that buyer change, and why
  doesn't this code notice?
- Why does the naive version never trigger `CHECK (quantity >= 0)`?
- What would `fun removeStock(...): PurchaseResult = { ... }` return, and why doesn't it compile here?
- Why is `current` an `Int?` even though the mapper calls `getInt`, which can't return `null`?
- Rewrite step 5 so the naive code can no longer lose updates. How close is your answer to the real
  `removeStock`?
