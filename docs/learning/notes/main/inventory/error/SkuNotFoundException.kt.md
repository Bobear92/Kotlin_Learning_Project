# SkuNotFoundException.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/error/SkuNotFoundException.kt](../../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/error/SkuNotFoundException.kt) · Last synced with code: 2026-09-27

## Role

The project's first custom exception, written by Robert. It names a domain problem, "this SKU
doesn't exist", without saying anything about HTTP. The [service](../InventoryService.kt.md) throws
it when the [repository](../InventoryRepository.kt.md) finds no row. It passes through the
[controller](../InventoryController.kt.md) untouched, and
[InventoryExceptionHandler](InventoryExceptionHandler.kt.md) turns it into a `404` with a
`text/plain` body.

It lives in the `error` subpackage, which was Robert's proposal (2026-09-27): custom exceptions and
the handler that maps them sit together, apart from the controller → service → repository files.
The alternatives were a flat `inventory` package (the error types mixed in with the layers) and a
top-level `com.robertwoolbright.inventory.error` (shared by every feature, but this project has only one).

## Walkthrough

### The package

```kotlin
package com.robertwoolbright.inventory.inventory.error
```

**Kotlin:** the package matches the folder `inventory/error/`. The compiler doesn't require that,
but the Kotlin style guide recommends it (Java tooling expects it), and every file in this project
follows it. Code in the
parent `inventory` package must import this class, as the [service does](../InventoryService.kt.md#imports).

### The whole class in one line

```kotlin
/** Thrown when the database is queried with the SKU, but the result comes back as null. This is thrown at the service level and is mapped to a 404 by InventoryExceptionHandler. */
class SkuNotFoundException(skuId: String) : RuntimeException("SKU not found: $skuId")
```

**Kotlin (extending a class):** `: RuntimeException(...)` after the constructor means "extends
`RuntimeException`". It's Java's `extends RuntimeException` plus a `super(...)` call in one step. The
parentheses are the call to the superclass constructor, and they're required: Kotlin always makes you
say which superclass constructor runs. Java equivalent:

```java
public class SkuNotFoundException extends RuntimeException {
    public SkuNotFoundException(String skuId) {
        super("SKU not found: " + skuId);
    }
}
```

**Kotlin (a plain constructor parameter):** `skuId: String` has no `val` or `var`, so it is *only* a
constructor parameter, not a property. It can be used while the object is being built (here, in the
superclass call), and then it's gone. `exception.skuId` doesn't compile. Compare
[`private val` in the repository](../InventoryRepository.kt.md#a-spring-bean-with-constructor-injection),
which keeps the value as a field. The SKU survives only inside the message text, which is all the
handler needs.

**Kotlin (string templates):** `"SKU not found: $skuId"` inserts the value of `skuId` into the string.
It's Java's `"SKU not found: " + skuId`. For anything more than a plain name, use braces:
`"${item.skuId}"` or `"${skuId.uppercase()}"`. Without braces, `$item.skuId` would insert `item`
and then the literal text `.skuId`.

**Kotlin (no body):** the class has nothing of its own to add, so it needs no `{ }`
(see [class with no body](../../InventoryApplication.kt.md#springbootapplication-and-a-class-with-no-body)). The class is also `final` by default,
so nothing can extend it, which is fine for an exception this specific.

**Why `RuntimeException`:** Kotlin has no checked exceptions, so extending `Exception` wouldn't force
anyone to catch it either. `RuntimeException` still matters for two reasons:
- Java callers and readers see an unchecked exception, which is what it behaves like.
- Spring's `@Transactional` rolls back by default only for unchecked exceptions (`RuntimeException`
  and `Error`). There's no transaction on this read path yet, but if a later write throws this
  exception, the transaction will roll back without any extra configuration.

**Why a custom exception and not `ResponseStatusException`:** Spring's
`ResponseStatusException(HttpStatus.NOT_FOUND, ...)` would carry the status itself, which puts HTTP
into the service. A custom exception keeps HTTP out of it: the service says *what* went wrong, and the handler decides how
that looks over HTTP. The purchase endpoint will throw the same exception for a missing SKU, and
the same handler will map it. (`ResponseStatusException` also doesn't give a `text/plain` body by
default; see [the handler notes](InventoryExceptionHandler.kt.md#the-class-and-why-its-an-advice).)

**Contract:** the message `SKU not found: <skuId>` is the `404` body, word for word. The contract
gives no example, so the wording was Robert's choice (2026-09-27), with **no trailing period**: a
period right after the id would make it unclear where the id ends (the id `CW-1.` is possible).
`InventoryControllerTest` checks the exact text.

## Check your understanding

- What would `SkuNotFoundException("widget").message` return? Could it ever be `null`?
- Why doesn't `ex.skuId` compile? What one-word change would make it compile?
- `"SKU not found: $sku.id"`: what does this string contain if `sku.id` is `"W-1"`?
- Why does the class name say nothing about `404`?
