# Walkthrough: GET /inventory/{skuId}

- Date: 2026-09-27 to 2026-09-28
- Covers: b150acb..6778c9f (plus uncommitted: yes, note improvements and the new interview-prep sheet made during this session)
- Files: InventoryRepository.kt (`findBySkuId`), SkuNotFoundException.kt, InventoryService.kt (`getInventory`),
  InventoryExceptionHandler.kt, InventoryController.kt (`getInventory`) (all written by Robert; he explained
  each first), InventoryRepositoryTest.kt, InventoryControllerTest.kt (written by Claude; Claude explained)

## Concepts covered

- Nullable return `InventoryItem?`: the repository *reports* "no row", the caller must handle it; no wrapper at runtime.
- `.param` / named parameters: the value is sent separately from the SQL, so untrusted URL input can't change the query.
- `singleOrNull` returns `null` for 0 **or** 2+ rows; the primary key is what makes that safe.
- Custom domain exception: the service names the problem, the handler picks the HTTP response; `ResponseStatusException` would put HTTP in the service.
- `: RuntimeException(...)` = `extends` + `super(...)`; string templates.
- Storage vs visibility: `val` keeps a constructor parameter on the object, `private` limits who can read it. `private val` enforces the layering.
- Elvis `?:` (not a ternary; Kotlin has none, `if` is an expression) + `throw` as an expression of type `Nothing`.
- Kotlin only objects when a "maybe" is used as a "definitely"; passing `T?` up compiles, and a `null` controller return becomes 200 with an empty body.
- Spring catches every exception and asks resolvers in order; `@RestControllerAdvice` gets custom ones handled before the 500 fallback. Handler methods are found by scanning, matched by type (including subclasses).
- `ResponseEntity` to set status and content type; `ex.message` is `String?` because `Throwable.message` is declared nullable.
- `@PathVariable` binds by name; mismatches compile and start, then fail on the first request with a 500.
- Compile time vs startup vs first request, and why MockMvc tests matter.
- Tests target specific bugs; decoy rows are what prove a lookup filters.

## Questions Robert asked

- Q: How would a pass-through `InventoryItem?` still compile when nothing handles the `null`?
  A: Kotlin only requires honesty: a "maybe" returned as a "maybe" claims nothing false. It objects only when a nullable is used as non-null. Declaring `getInventory` non-null is what forces the Elvis.
- Requests (not questions): save the duplicate-SKU failure mode and the `.single()` alternative; start an interview study sheet and keep adding to it. Created `docs/learning/interview-prep.md`.

## Understanding checks

- Two `widget` rows (primary key dropped) → correct: looks missing, `SkuNotFoundException` flow, called it a bug. Added: client sees 404 for an existing SKU; `.single()` would be loud but also throws on zero rows.
- `ex.skuId` in the handler doesn't compile, why and one-word fix → first "pass the ex object / grab the nested id", then "make it `private`". Re-explained with the Java field version and a storage vs visibility table. Answer is `val`. Re-check (`inventoryService.inventoryRepository` from the controller) answered correctly: private, you go through the layers.
- Pass-through `null` to the controller → "healthy null, no NPE". Half right: no NPE, but Spring sends 200 with an empty body, a false success.
- Delete `@RestControllerAdvice` → correct: 500 default Spring error. Added: the body is JSON and hides the message (`include-message=never`).
- `@GetMapping("/{skuId}")` with `@PathVariable sku` → thought it wouldn't compile. It compiles and starts; the first request throws `MissingPathVariableException` → 500.
- No `WHERE` clause, predict the four repository tests → 3/4 results right (said zero stock fails; it passes by coincidence). Reasons were off: assumed several rows in every test and that the function could return "everything". Re-explained with a per-test trace table (rows → query → `singleOrNull` → compare).
- Service self-explanation called `?:` a ternary; corrected to the Elvis operator.
- Said "a 404 is thrown at the service layer"; corrected: the service throws a domain exception, the handler builds the 404.
- On the custom exception, first reason given was "a more detailed message"; corrected: `ResponseStatusException` takes a message too. The real reasons are keeping HTTP out of the service, reuse, and the `text/plain` contract.

## Follow-ups

- New study sheet `docs/learning/interview-prep.md` (linked from the README). Keep adding interview-worthy scenarios during walkthroughs.
- Folded into notes: failure mode traced end to end (repository), storage vs visibility table (SkuNotFoundException), not-a-ternary and pass-through null → 200 empty (service), full resolver chain (handler), path-variable mismatch timing (controller).
- Revisit before the interview: storage vs visibility (took three tries), predicting test failures (second walkthrough in a row it needed a trace), precise wording "throws an exception / builds a 404".
- Optional, offered and not taken for now: add a decoy row to the zero-stock tests so each stands on its own.
- Optional experiments: make `getInventory` a pass-through `InventoryItem?` and run the tests; rename the `@PathVariable` parameter and run the tests; remove `@RestControllerAdvice` and `curl` a missing SKU.
