# PurchaseResult.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/PurchaseResult.kt](../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/PurchaseResult.kt) · Last synced with code: 2026-09-29

## Role

Robert wrote this type. It is the answer the [repository's `removeStock`](InventoryRepository.kt.md#removestock-three-outcomes-from-one-statement)
gives the [service's `purchaseInventory`](InventoryService.kt.md#purchaseinventory-one-branch-per-outcome).
A purchase can end in three ways, and the API answers each one differently:

| Case | Meaning | Service does | Client gets |
| --- | --- | --- | --- |
| `Purchased(item)` | the SKU had enough stock, and it was deducted | returns `item` | `200` with the remaining stock |
| `SkuNotFound` | there is no row for that SKU | throws `SkuNotFoundException` | `404` |
| `InsufficientStock` | the row exists but holds less than requested | throws `InsufficientInventoryException` | `400` |

This file contains no logic. It only names the three outcomes, so the repository can report what the
SQL found without deciding what that means for HTTP.

## Walkthrough

### Why a new type at all

```kotlin
// Why: We used the sealed interface because we have 3 possible outcomes we need to account for. Not just a success or null so returning InventoryItem? doesn't help us between when we need to throw 404 or 400.
```

**Why:** `addStock` has two outcomes, a row or a refusal, so it returns
[`InventoryItem?`](InventoryRepository.kt.md#findbyskuid-a-nullable-return-type) and uses `null`
for the refusal. A purchase has two different failures. If `removeStock` returned `InventoryItem?`,
a `null` couldn't say which failure happened, and the service would have to go back to the database
to find out. That second query is a race: another request could create or change the SKU between
the two queries. The decision (Robert, 2026-09-29) was to have one SQL statement work out the
outcome, and one type that carries each outcome separately.

Alternatives that were considered:
- **Throw from the repository.** This would work, but then the repository decides what counts as
  an error, which is the service's job (see
  [why throw in the service](InventoryService.kt.md#getinventory-turning-no-row-into-an-error)).
- **An `enum` plus a nullable item.** `enum class Outcome { PURCHASED, NOT_FOUND, INSUFFICIENT }`
  can't carry the item, so it would need a separate `item: InventoryItem?` next to it. The compiler
  couldn't then guarantee that `PURCHASED` always comes with an item.

### A sealed interface

```kotlin
/** PurchaseResult is returned by the repository and translated by the service. */
sealed interface PurchaseResult {
```

**Kotlin (`interface`):** a Kotlin interface works like a Java interface: a type that other classes
implement. This one has no members at all. It exists only so the three cases share one type, which
lets `removeStock` declare a single return type, `PurchaseResult`.

**Kotlin (`sealed`):** `sealed` closes the list of implementations. Every class that implements
`PurchaseResult` must be declared in the same package and the same module (for this project, the
`inventory` package inside this Gradle build). No other code can add a fourth case. The compiler
therefore knows the complete list, and that is what makes the service's `when` work without an
`else` branch (see [exhaustive `when`](InventoryService.kt.md#purchaseinventory-one-branch-per-outcome)).
If someone later adds a fourth case here, every `when` over `PurchaseResult` that lacks an `else`
stops compiling until it handles the new case. A type change produces a list of every place that
needs updating.

**Java equivalent:** Java 17 has the same feature. The subtypes are listed with `permits`, and the
list can be left out only when they are all in the same file:

```java
public sealed interface PurchaseResult
        permits PurchaseResult.Purchased, PurchaseResult.SkuNotFound, PurchaseResult.InsufficientStock {
    record Purchased(InventoryItem item) implements PurchaseResult {}
    final class SkuNotFound implements PurchaseResult { /* a singleton, by hand */ }
    final class InsufficientStock implements PurchaseResult { /* a singleton, by hand */ }
}
```

Kotlin never uses a `permits` list, and its rule is looser: a subtype can be in any file of the
same package and module, while Java without `permits` allows only the same file.

**Sealed interface or sealed class:** `sealed class PurchaseResult` would also work. An interface
fits better here because the cases share no state or code. A class can extend only one class but
can implement many interfaces, so using an interface keeps that option open.

### The success case: a `data class`

```kotlin
/** The SKU exists and had enough stock. Item holds the remaining stock. */
data class Purchased(val item: InventoryItem) : PurchaseResult
```

**Kotlin (nested classes):** `Purchased` is declared *inside* `PurchaseResult`, so its full name is
`PurchaseResult.Purchased`, and that is how the repository and the service refer to it. Nesting
keeps the three cases grouped under the type they belong to. A nested class in Kotlin is like a
`static` nested class in Java: it has no hidden link to an outer object. (In a class, Kotlin's `inner`
keyword would add that link. It isn't even allowed inside an interface, since there's no outer instance.)

**Kotlin (`data class`):** the same feature as [`InventoryItem`](InventoryItem.kt.md). The
generated `equals` is what lets the repository test compare a whole result in one line:
`assertEquals(PurchaseResult.Purchased(InventoryItem("widget", 3)), removeStock(...))`.

**Kotlin (`: PurchaseResult`):** this declares that `Purchased` implements the interface. Because
it's an interface, there are no parentheses after the name: there's no constructor to call. Compare
with [`: RuntimeException(...)`](error/SkuNotFoundException.kt.md#the-whole-class-in-one-line), which
extends a class and therefore calls its constructor.

**Why it carries an `InventoryItem`:** a successful purchase has data, the remaining stock, and
the service returns it as the `200` body. The two failures have no data, so they don't need a class
with properties.

### The failure cases: `data object`

```kotlin
/** There's no row for that SKU id. */
data object SkuNotFound : PurchaseResult

/** The row exists but has less than the requested quantity. */
data object InsufficientStock : PurchaseResult
```

**Kotlin (`object`):** `object` declares a class and creates its only instance in one step, which
makes it a **singleton**. There is exactly one `SkuNotFound` in the program, and code refers to it
by name without `()`: `PurchaseResult.SkuNotFound`, never `PurchaseResult.SkuNotFound()`. A case
that carries no data doesn't need a new object each time.

**Kotlin (`data object`):** adding `data` gives the object a readable `toString()`, `"SkuNotFound"`,
instead of the default `com.robertwoolbright.inventory.inventory.PurchaseResult$SkuNotFound@1b6d3586`.
That shows up in test failure messages: `expected: <InsufficientStock> but was: <SkuNotFound>`. It
also generates `equals` and `hashCode`. A plain `object` compares by identity; a `data object`'s
`equals` checks the *type* (`other is SkuNotFound`), so a second instance created behind Kotlin's
back (by reflection or deserialization) still counts as equal. Kotlin 1.9 made `data object`
stable, and it's the recommended form for the case of a sealed hierarchy that carries no data.

**Why the names are not HTTP names:** `SkuNotFound` rather than `NotFound404`. The repository
reports what it found in the database. The service turns that into a domain exception, and the
[handler](error/InventoryExceptionHandler.kt.md) turns that into a status code.

## Check your understanding

- Why can't `removeStock` return `InventoryItem?` the way `addStock` does? What would the service be
  unable to tell apart?
- A teammate adds `data object SkuDiscontinued : PurchaseResult`. What happens when the project
  compiles, and where?
- Why is it `PurchaseResult.SkuNotFound` and not `PurchaseResult.SkuNotFound()`? What would
  `PurchaseResult.Purchased` without arguments mean?
- What would `println(PurchaseResult.InsufficientStock)` print with `data object`, and what would it print with a plain `object`?
