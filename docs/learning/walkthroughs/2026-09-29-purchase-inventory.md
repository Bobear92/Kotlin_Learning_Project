# Walkthrough: POST /inventory/{skuId}/purchase (purchase inventory)
- Date: 2026-09-29
- Covers: decc7e0..69b82b2 (plus uncommitted: yes, Robert's comment rewording in InventoryQuantity.kt, InventoryRepository.kt and SkuNotFoundException.kt, and note / interview-prep additions from this session)
- Files: InventoryController.kt, InventoryService.kt, PurchaseResult.kt, InventoryRepository.kt,
  InsufficientInventoryException.kt, InventoryExceptionHandler.kt, InventoryConcurrencyTest.kt
  (application.yml only lost a trailing space; the controller, service and repository tests were summarized)

## Concepts covered
- Request pipeline order: routing → `@RequestBody` (Content-Type, then Jackson) → `@Valid` (`@Min`) → handler → DB. `@Valid` only runs Bean Validation; the other two checks belong to `@RequestBody`.
- A path variable matches one segment: `/{skuId}` never matches `widget/purchase`; `POST /inventory/purchase` is a receive for SKU `purchase`.
- The service takes `String` + `Int`, not the web DTO, so non-HTTP callers don't need a fake request body.
- `require` in the service stops negative purchases, which the DB `CHECK` can't catch (stock goes up).
- `when` with a subject (like `switch`: `is` for a type, equality for a `data object`) vs. without (if/else-if, first true wins, `else` required).
- `when (val x = ...)`: one call, name scoped to the `when`. `return when` works because `when` is an expression; `throw` branches are `Nothing`.
- Smart casts: `is Purchased` unlocks `.item`; `!= null` turns `Int?` into `Int`.
- Sealed interface: closed set of cases, exhaustive `when` with no `else`, so a new case breaks the build where it must be handled.
- `data class` carries data (new instance each time); `data object` is a singleton with a readable `toString`. Nested cases are `PurchaseResult.X`.
- Repository reports facts (`PurchaseResult`), service throws domain exceptions, handler maps to HTTP.
- Data-modifying CTE: the stock check is in the `UPDATE`'s `WHERE`, so check and write are atomic. Zero rows in `purchased` → scalar subquery gives `NULL`; `EXISTS` separates the two failures.
- One snapshot per statement: only `RETURNING` shows the new quantity.
- `.single()` is about rows (the outer `SELECT` has no `FROM`, so always one row), not about the three outcomes.
- `getObject(..., Int::class.javaObjectType)` + `: Int?` keeps `NULL` distinct from a real `0`.
- Constructor parameter with no `val`: used only during construction, not a property. `ex.message` is `String?` because it comes from `Throwable`.
- Concurrency test: `remaining == [0..9]` proves each unit sold exactly once; `filterIsInstance`.

## Questions Robert asked
- Q: Is there a specific order between `@Valid` and `@RequestBody`? / A: Yes: `@RequestBody` (converter, then Jackson) first, then `@Valid`, all before the function runs. Shown as a numbered pipeline.
- Q: Does a `when` end when it finds its first true branch? / A: Yes, with no fall-through (unlike a Java `switch` without `break`).

## Understanding checks
- Missing SKU + `{"quantity": 0}`: 400 or 404? → guessed the URL problem would win. Re-explained: routing doesn't know which SKUs exist; only the SQL does, and validation stops the request first → 400. Robert restated it correctly.
- `purchaseInventory("widget", -5)` without `require` → got 15, but thought the `CHECK` would stop it. Walked through `WHERE 10 >= -5`, `10 - (-5) = 15`, `15 >= 0`: the `CHECK` checks the stored value, not the parameter. Takeaway in his words: input validation has to live in the application.
- Add `data object SkuDiscontinued` → correct: compile error, since the service's `when` is no longer exhaustive. Refined "arguments" → branches, and only the service's `when` breaks.
- Fill `remaining_quantity` / `sku_exists` for three cases → rows 2 and 3 right; wrote `5` for row 1 (should be `NULL`, the stock stays 5). Also placed the classification in the service; it's the repository's `when`.
- Swap the first two branches → correct: every success becomes `InsufficientStock`. Added: the stock was already deducted, so the client gets a 400 for a purchase that happened.
- Which handlers can answer a purchase → listed the five correctly; missed that `handleStockOverflow` never can (only `receiveInventory` throws it).
- Naive read-check-write purchase under the 50-buyer test → correct: the "sold exactly once" assertion fails first. Added: the `CHECK` doesn't catch lost updates either.

## Follow-ups
- Robert's `InventoryQuantity` KDoc now reads "...and paths./inventory/{skuId}/purchase.post". That's contract-path style; plain "POST /inventory/{skuId}/purchase" would match the rest of the KDoc. His call before shipping.
- Explain-backs skipped some direct questions (the "why" of passing an `Int`, `is` vs no `is`, `.single()`). Try answering each numbered question explicitly next time.
- Added to notes: the lost-update table (concurrency notes) and the "stock was already deducted" consequence of swapping branches (repository notes).
- Added to the interview-prep sheet: `CHECK` vs. overselling, 400 vs. 404 ordering, and why a sealed `PurchaseResult`.
