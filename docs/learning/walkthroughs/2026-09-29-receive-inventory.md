# Walkthrough: POST /inventory/{skuId} (receive inventory)
- Date: 2026-09-29
- Covers: 6778c9f..decc7e0 (plus uncommitted: yes, note improvements and interview-prep additions made during this session, and Robert's in-code jottings, to be removed before shipping)
- Files: build.gradle.kts, application.yml, InventoryQuantity.kt, InventoryController.kt, InventoryService.kt,
  InventoryRepository.kt, AddStockOverflowException.kt, InventoryExceptionHandler.kt,
  InventoryConcurrencyTest.kt, InventoryServiceTest.kt

## Concepts covered
- Validation starter: `@Min` is only a label; Hibernate Validator (from the starter) plus `@Valid` make it run. Without either, it's silently ignored.
- Jackson strictness: `accept-float-as-int` (no `12.5` → `12`) and `allow-coercion-of-scalars` (no `"5"` → `5`).
- The two gates: Jackson asks "right type?" (`Invalid request body`); Bean Validation asks "allowed value?" (the `@Min` message).
- Non-null `Int` in the DTO: the type itself makes Jackson refuse `null` / `{}`.
- Request bodies are built per request by Jackson through the primary constructor; beans are built once at startup.
- `@PathVariable` fills from the URL, `@RequestBody` from the body; the service takes `String` + `Int`, not the web DTO.
- 200 vs 201 and 400 vs 415: Spring defaults and REST conventions lose to the contract; a test pins the 200.
- `require` throws `IllegalArgumentException`; its `{ }` is a lazy message lambda, not an "else" branch like Elvis.
- Block body needs `return`; a lambda returns its last expression.
- Lost update: find-then-write makes the decision in Kotlin between two DB trips; the upsert moves it into one atomic SQL statement.
- Row locks: per row, short, and cheaper than the two-trip alternative.
- `EXCLUDED` (the row the conflict kicked out), `RETURNING` (the new row from the same statement; without it, `.query` fails → 500).
- Overflow guard algebra: `current <= MAX - new`, because `current + new` would crash instead of returning false; relies on `new >= 1`.
- `: RuntimeException("...")` passes a fixed message to the parent constructor; `ex.message ?:` fallback exists only because `Throwable.message` is `String?`.
- Spring's own exceptions get proper statuses (415/400) from its default resolver; unknown exceptions get 500.
- Concurrency test: `CountDownLatch` start gate, `runCatching`, `@RepeatedTest(5)`, why `@SpringBootTest` over `@JdbcTest`.

## Questions Robert asked
- Q: Where is `@Min`? I don't see it. / A: In `InventoryQuantity.kt`; shown with `@Valid` in the controller and the starter in the build as the three pieces that must line up.
- Q: When `require` fails, does it throw the other side of the Elvis, or a generic Spring exception? / A: Neither. `require` is plain Kotlin and throws `IllegalArgumentException`; the function stops, so the Elvis line never runs. Compared `require` (condition) with `?: throw` (null).
- Q: `IllegalArgumentException` isn't one of the handlers we modified? / A: Correct, it's unhandled → 500, on purpose (our bug, not the client's). We wrote handlers *for* Spring's exceptions, not modified Spring's handlers.
- Q: Where do we make sure it's 200 and not 201? / A: Nowhere in production code; it's Spring's default for a plain return value. `status { isOk() }` in the controller test pins it.
- Q: Is the find-then-insert block an example from our service layer? / A: Hypothetical, not our code; it would most naturally live in a service, but the layer doesn't matter.
- Q: So the point is that it's outside the SQL? / A: Yes: the decision in Kotlin leaves a gap between two DB trips; `ON CONFLICT` is the `if` inside the statement.
- Q: Could the row lock slow the service down? / A: No: one row, well under a millisecond, and cheaper than two round trips or `SELECT ... FOR UPDATE`. Only a single extremely hot SKU would queue.
- Q: Are you setting Int.MAX_VALUE to 1? / A: No, `MAX_VALUE - 1` (a stock one below the ceiling); restated with real numbers.

## Understanding checks
- Remove the validation starter, send `{"quantity": 0}` → first said "the bug slips through"; walked to `require` → `IllegalArgumentException` → no handler → 500.
- Predict `null` / `0` / `7` → got the outcomes, but lumped both errors under "our message". Clarified the two gates and their different bodies.
- `{"quantity": "7"}` → right setting (`allow-coercion-of-scalars`), but called it "validation"; it's Gate 1 (Jackson).
- Controller explain-back → said "2 path variables" (one path variable, one body) and "Jackson validations" for both annotations; corrected. Got the URL composition right.
- Rename check (`requestQuantity` → `body`, `skuId` → `id`) → skipped at Robert's request.
- Block body vs `=` → "`=` is for a function that does one thing": right idea; added that a missing `return` is a compile error and that lambdas are the opposite.
- Lost-update table → explained the race in his own words (concurrent requests interleave in the Kotlin sequence; one SQL statement can't). Refined: the lock is per row, not global.
- `EXCLUDED` / `RETURNING` → both right first time (a big improvement on the last session). Added why "excluded", the second race `RETURNING` avoids, and the 500 without it.
- Overflow guard at MAX-1 + 2 → traced every layer correctly to the 400. Natural form → "Postgres freaks out and never gets to the comparison": correct.
- Negative quantity → correct (`MAX - (-5)` overflows); said "greater than 1" for `>= 1` again.
- No-arg exception message / when the fallback shows → both right.
- Delete the Content-Type handler → predicted 500; it's 415 (Spring's own exception, known to its default resolver). Contrasted with `IllegalArgumentException` → 500.
- Find-then-insert under the concurrency test → "a mess"; made precise: the total assertion fails, always too low, different each run.

## Follow-ups
- Robert: remove in-code jottings before shipping: the two comment lines in `application.yml`, the two extra KDoc lines in `InventoryQuantity`, and the "If above is thrown…" line in the `InventoryService` KDoc (content moved into the notes).
- Recurring slips to keep probing: "Jackson" vs "validation" (the gates), thrown vs called, "greater than 1" vs "at least 1", layer names (`addStock` vs `receiveInventory`).
- Optional (Robert's call): overflow fallback text "Overflow Guard Triggered" describes internals; reuse the real message.
- Exercise offered, not run: add `@ResponseStatus(HttpStatus.CREATED)` and watch `POST inventory creates a new SKU` fail.
- Next feature: `POST /inventory/{skuId}/purchase`, where "subtract only if enough stock" must live inside one SQL statement, like the upsert.
