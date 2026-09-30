# InsufficientInventoryException.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/error/InsufficientInventoryException.kt](../../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/error/InsufficientInventoryException.kt) · Last synced with code: 2026-09-29

## Role

The third custom exception, written by Robert. It names a domain problem: "this SKU exists, but it
doesn't have enough stock for this purchase". The [repository](../InventoryRepository.kt.md#removestock-three-outcomes-from-one-statement)
reports that as [`PurchaseResult.InsufficientStock`](../PurchaseResult.kt.md); the
[service](../InventoryService.kt.md#purchaseinventory-one-branch-per-outcome) turns that into this
exception; [InventoryExceptionHandler](InventoryExceptionHandler.kt.md#handleinsufficientinventory-the-exception-comes-in-as-a-parameter)
turns it into a `400` with a `text/plain` body. It follows the same pattern as
[SkuNotFoundException](SkuNotFoundException.kt.md), whose notes explain the ideas in full.

## Walkthrough

### The whole class

```kotlin
/** Thrown by the service when removeStock reports insufficient stock for the purchase. Mapped to a 400 by InventoryExceptionHandler. */
class InsufficientInventoryException(skuId: String) : RuntimeException("Insufficient inventory for $skuId")
```

**Kotlin:** the same shape as `SkuNotFoundException`: `skuId` is a
[plain constructor parameter](SkuNotFoundException.kt.md#the-whole-class-in-one-line), used only to
build the message and then discarded. `: RuntimeException(...)` extends the class and calls its
constructor, and `$skuId` is a string template. The only differences are the class name and the
message.

**Why the message includes the SKU (Robert, 2026-09-29):** the body is
`Insufficient inventory for <skuId>`, following the same pattern as the `404`'s
`SKU not found: <skuId>`. A client that purchases several SKUs can tell which one failed. There's no
trailing period, for the same reason as the `404` message: the id `CW-1.` is possible, and a period
after it would make the end of the id unclear.

**Why the message doesn't include the stock level:** "only 3 left" would be more helpful, but the
repository's single statement doesn't return the current quantity on a refusal, and adding that
would change the SQL. It would also tell anyone who calls the API how much stock each SKU holds.
The contract gives no wording, so the shorter message was chosen.

**Why a `400` and not `409 Conflict`:** some APIs use `409` for "the request is valid, but the
server's current state won't allow it". The contract is explicit: "Returns 400 if there is
insufficient inventory". The handler follows the contract.

**Why `RuntimeException`, and why a custom exception:** the same reasons as for
[SkuNotFoundException](SkuNotFoundException.kt.md#the-whole-class-in-one-line). Kotlin has no
checked exceptions, and a domain exception keeps HTTP status codes out of the service.

**Contract:** `paths./inventory/{skuId}/purchase.post`, response `'400'`: "Insufficient inventory
or invalid request", `text/plain`. `InventoryServiceTest` checks the exact message, and
`InventoryControllerTest` checks the exact response body.

## Check your understanding

- What is `InsufficientInventoryException("widget").message`? Where does the client see it?
- Why can't the handler read `ex.skuId`? What would have to change for that to compile?
- Which layer decides that "not enough stock" is an error, and which layer decides it's a `400`?
