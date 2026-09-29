# AddStockOverflowException.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/error/AddStockOverflowException.kt](../../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/error/AddStockOverflowException.kt) · Last synced with code: 2026-09-28

## Role

The second custom exception, written by Robert. It names a domain problem: "adding this much would
push the stock past the largest number we can store". The
[repository](../InventoryRepository.kt.md#the-overflow-guard) refuses such an add inside its SQL and
returns `null`; the [service](../InventoryService.kt.md#receiveinventory-a-precondition-and-a-block-body)
turns that `null` into this exception; [InventoryExceptionHandler](InventoryExceptionHandler.kt.md)
turns it into a `400` with a `text/plain` body. It follows the same pattern as
[SkuNotFoundException](SkuNotFoundException.kt.md), which explains the ideas in full.

## Walkthrough

### The whole class

```kotlin
/** Thrown by the service when addStock returns null because of the overflow guard. Maps to a 400 error in the InventoryExceptionHandler. */
class AddStockOverflowException : RuntimeException("Quantity would exceed the maximum stock")
```

**Kotlin (no constructor parameters):** compare with `SkuNotFoundException(skuId: String)`. Here
there are no parentheses after the class name, so the class has a primary constructor with **no**
parameters, and it's created with `AddStockOverflowException()`. The message is fixed, so there's
nothing to pass in. `: RuntimeException("...")` still calls the superclass constructor with the
message (see [extending a class](SkuNotFoundException.kt.md#the-whole-class-in-one-line)). Java
equivalent:

```java
public class AddStockOverflowException extends RuntimeException {
    public AddStockOverflowException() {
        super("Quantity would exceed the maximum stock");
    }
}
```

**Why the message is in the exception (Robert's option A, 2026-09-28):** the handler returns
`ex.message`, the same way `handleSkuNotFound` does, so every custom exception carries its own
client-facing text. The alternative was a no-message exception plus a fixed string in the handler.
Either works; this way the two custom exceptions look alike.

**Why no SKU in the message:** the client already knows which SKU it posted to, and the contract
gives no wording. The message says what went wrong, not the internal mechanism ("overflow guard"),
which the client can't do anything with.

**Why `RuntimeException`, and why a custom exception at all:** the same reasons as for
[SkuNotFoundException](SkuNotFoundException.kt.md#the-whole-class-in-one-line): Kotlin has no checked
exceptions, `@Transactional` rolls back on unchecked ones, and a domain exception keeps HTTP status
codes out of the service.

**Why a `400` and not a `500`:** the request asks for something the API can't store (a total above
`Int.MAX_VALUE`, 2,147,483,647). Nothing is broken on the server; the client asked for too much.
The contract lists only `200` and `400` for this endpoint, so `400` is also the only honest choice
(README "Contract gaps (POST)").

**KDoc:** it says who throws it and where it's mapped, so a reader who finds this class can follow
the whole path. Robert's first version wrote the KDoc delimiters reversed (`//* ... /*`), which is
a line comment, not KDoc. It compiled, but `check_kdoc.py` caught it.

## Check your understanding

- Why is it `AddStockOverflowException()` with empty parentheses, but `SkuNotFoundException(skuId)` with an argument?
- Which layer decides that an overflow is an error, and which layer decides it's a `400`?
- Could the client ever see the text "Overflow Guard Triggered"? Where does it come from?
