# TODO

Working task list for the project. Claude reads this at the start of a session (see CLAUDE.md)
and checks items off as they are done. Newest decisions are recorded in the README.

## Environment setup (Robert)

- [x] Install Docker Desktop: 4.91.0 via winget (Docker 29.8.0, Compose v5.5.1) (2026-09-23)
- [x] Install JDK 21: Temurin 21.0.12.1 via winget, JAVA_HOME set (2026-09-23)
- [x] Docker engine verified with `docker run --rm hello-world` (2026-09-23). Restart VS Code if `java`/`docker` are not found in its terminal.

## Decisions

- [x] Data store: **PostgreSQL** (2026-09-23)
- [x] Concurrency approach: single atomic SQL statements (`INSERT ... ON CONFLICT DO UPDATE`,
      conditional `UPDATE ... WHERE quantity >= ?`), plus a `CHECK (quantity >= 0)` constraint
      as a backstop (2026-09-23)
- [x] Data-access library: **`JdbcClient`** (explicit SQL, immutable data classes) (2026-09-23)
- [x] Controllers: **hand-written**, plus a contract-validation test that checks responses against inventory.yaml (2026-09-23)
- [x] Contract gaps for `POST`: exact case-sensitive SKUs, strict JSON (400), wrong Content-Type → 400,
      overflow → 400; see README "Contract gaps (POST)" (2026-09-28)
- [x] Schema migrations: **Flyway** (versioned plain-SQL migrations) (2026-09-23)

## Scaffold

- [x] Project layout decided: single Gradle module at the repo root, package by feature
      (`com.robertwoolbright.inventory`), contract moved to `src/main/resources/openapi/` (2026-09-23)
- [x] Spring Boot + Kotlin + Gradle Kotlin DSL project (Initializr, versions pinned in README) (2026-09-23)
- [x] Docker Compose file for Postgres; Spring Boot Docker Compose support for `bootRun` (2026-09-23)
- [x] Testcontainers for tests (`support/TestcontainersConfiguration.kt`) (2026-09-23)
- [ ] README "Build and run" section verified from a clean clone (verified in the working tree only)
- [x] `V1__create_inventory.sql` migration (table + `CHECK (quantity >= 0)`), written by Robert (2026-09-24)
- [x] `/walkthrough` of the scaffold (build.gradle.kts, compose.yaml, application.yml, the three .kt files) (2026-09-23)

## Tooling

- [x] `scripts/check_notes.py` no longer waits on stdin when run by hand: hook mode is now an explicit `--hook` flag (2026-09-24)

## Features (one `/feature` + `/walkthrough` each)

- [ ] `GET /inventory`: ordered by `sku_id`, zero-stock SKUs included. Tests written 2026-09-24; Robert writes the code. Done: migration, `InventoryItem`, repository (4/4 green), service, controller (build 7/7 green, 2026-09-25); study notes written 2026-09-25; Claude's review done and Robert's KDoc/`// Contract:` fixes plus controller rename to `listInventory` (2026-09-25). Walkthrough done 2026-09-25. Next: `/ship`
- [x] `/walkthrough` of `GET /inventory` (2026-09-25)
- [ ] `GET /inventory/{skuId}`: 404 body `SKU not found: <skuId>` (text/plain), via `SkuNotFoundException` + `@RestControllerAdvice` (Robert, 2026-09-27). Tests written 2026-09-27 (4 repository, 4 controller); Robert writes the code. Done: `InventoryRepository.findBySkuId` (8/8 repository tests green, 2026-09-27, KDoc and `// Why:` comments done). Done: `error/SkuNotFoundException` (2026-09-27; `error` subpackage holds custom exceptions + the handler, Robert). Done: service `getInventory` (2026-09-27, `@throws` KDoc; commit 65c526c). Done: controller `getInventory` with `@GetMapping("/{skuId}")` + `@PathVariable` (2026-09-27). Done: `error/InventoryExceptionHandler` (`@RestControllerAdvice`, `handleSkuNotFound` → 404 text/plain; 15/15 tests green, commit 002a901, 2026-09-27). Study notes written 2026-09-27; error-handling decision recorded in the README. Walkthrough done 2026-09-28. Next: `/ship`
- [x] `/walkthrough` of `GET /inventory/{skuId}` (2026-09-28; started `docs/learning/interview-prep.md`)
- [ ] Optional: add a decoy row to the zero-stock `findBySkuId` / `GET /inventory/{skuId}` tests (today they pass even without a `WHERE` clause; offered 2026-09-28, not taken yet)
- [ ] `POST /inventory/{skuId}` (create or add). Contract gaps decided 2026-09-28 (README). Tests written 2026-09-28: 7 repository (`addStock`), 8 controller (incl. two parameterized), 2 concurrency (`InventoryConcurrencyTest`, `@RepeatedTest(5)`); Robert writes the code. Order: validation dependency → Jackson strictness in application.yml → `InventoryQuantity` → `addStock` → overflow exception → service `receiveInventory` → controller `@PostMapping` → four 400 handlers. Done: `InventoryRepository.addStock` (atomic upsert, `WHERE inventory.quantity <= :maxQuantity - EXCLUDED.quantity`, RETURNING → `singleOrNull`; 15/15 repository + concurrency tests green, 2026-09-28). `spring-boot-starter-validation` added; Jackson strictness in application.yml (`accept-float-as-int`, `allow-coercion-of-scalars` off; verified against Jackson 3 defaults) (2026-09-28). Done: `InventoryQuantity` DTO with `@Min(1, message = ...)` (cc34bc4, 2026-09-28). Done: `error/AddStockOverflowException` (no-arg, message in the exception, 2026-09-28). Done: service `receiveInventory(skuId, quantity)` (String + Int, not the DTO; commit 9a4c81a, 2026-09-28). Done: controller `receiveInventory` with `@PostMapping("/{skuId}")` + `@Valid @RequestBody InventoryQuantity` (create/add/case-sensitive tests green; the 400 tests fail as expected: empty-body 400s from Spring's default resolver, 415 for Content-Type, 500 for overflow, 2026-09-28). Done: the four 400 handlers in `InventoryExceptionHandler` (validation message read from `@Min`; Spring's `ex.message` never sent to clients; commit d972893, 2026-09-28). Review done 2026-09-28: comment tidy (0705412), service `require(quantity >= 1)` + `InventoryServiceTest` (3dd5d15; 0 used to create an empty SKU), handler renamed `handleValidationFailure` (a7e54e1); 53/53 green. Study notes written 2026-09-28. Walkthrough done 2026-09-29. Next: `/ship`
- [x] `/walkthrough` of `POST /inventory/{skuId}` (2026-09-29; six new interview-prep questions)
- [ ] `POST /inventory/{skuId}/purchase`. Decided 2026-09-29 (Robert): one CTE statement classifies the outcome
      (`WITH purchased AS (UPDATE … RETURNING) SELECT remaining, EXISTS(…)`), 400 body `Insufficient inventory for <skuId>`,
      validation before lookup (invalid body on a missing SKU is a 400). Tests written 2026-09-29: 7 repository, 4 service
      (one parameterized), 9 controller (two parameterized), 3 concurrency races (`@RepeatedTest(5)`); Robert writes the code.
      Order: `PurchaseResult` → `removeStock` → `InsufficientInventoryException` → service `purchaseInventory` → controller → handler.
      Done: `PurchaseResult` sealed interface (2026-09-29). Done: `InventoryRepository.removeStock` (CTE; `Int?` via
      `getObject(…, Int::class.javaObjectType)` so NULL ≠ 0; compiles, tests blocked until the service compiles, 2026-09-29)
- [ ] Contract-validation test: check MockMvc responses against inventory.yaml (decided with the
      Controllers decision; do it once there is more than one endpoint to validate)
- [ ] Decide on demo seed data for a running app, once `POST /inventory/{skuId}` exists
- [ ] Error handling: `text/plain` 400/404 bodies exactly as the contract specifies
      (404 for missing SKU done 2026-09-27 via `InventoryExceptionHandler`; POST's four 400s done 2026-09-28; purchase's 400/404 still to do)
- [ ] Decide whether error responses should declare `charset=UTF-8`: today `Content-Type: text/plain` has no charset while the body is UTF-8 (seen with `curl` on 2026-09-27; only matters for non-ASCII SKUs)

## Oversell protection: test plan

**Invariant: a purchase must never oversell, even when requests arrive at the same moment.**
Stock never goes below zero, and every unit sold was actually in stock. Test this at every
level we build, using several different mechanisms, because each one catches a different
class of bug. Each item below is also a teaching topic for a walkthrough.

### Correctness in isolation
- [ ] Repository tests against a real Postgres (Testcontainers, not H2): purchase exact stock → 0,
      purchase stock + 1 → rejected and quantity unchanged, purchase on missing SKU → not found
- [ ] Controller tests (`MockMvc`): 400 with `text/plain` body on insufficient stock, 404 on missing SKU

### Concurrency
- [ ] **Same-SKU purchase race.** Seed stock S, fire N > S concurrent purchases of 1 through
      a start gate (`CountDownLatch` or coroutines) so they really overlap. Assert: exactly S
      succeed (200), N − S are rejected (400), final quantity is 0, never negative.
- [ ] **Uneven quantities.** Concurrent purchases of different sizes. Assert: the sum of
      successful purchases ≤ S, and final = S − sum(successful).
- [ ] **Mixed receive + purchase race.** Concurrent restocks and purchases. Assert:
      final = initial + sum(received) − sum(successful purchases).
- [x] **New-SKU creation race.** Concurrent first-time `POST`s for a SKU that doesn't exist yet.
      All succeed, no duplicate-key errors, final = sum of all quantities.
      (`InventoryConcurrencyTest`, plus a lost-update race on an existing SKU; green with `addStock`, 2026-09-28)
- [ ] **Repeat to flush out flakiness.** Run the race tests many times (`@RepeatedTest`), since
      concurrency bugs are intermittent. (receive races use `@RepeatedTest(5)`, 2026-09-28)
- [ ] **Negative control.** Temporarily swap in a naive read-then-write implementation and show
      the race test *fails*. This proves the test can actually detect an oversell.

### Database backstop
- [ ] Test that the `CHECK (quantity >= 0)` constraint rejects a direct update that would go
      negative, so the database protects the data even if the application has a bug.
      (test written in `InventoryRepositoryTest` 2026-09-24; passes once Robert's V1 migration exists)

### Across instances and under load
- [ ] **Multiple app instances.** Two app instances (or two Spring contexts) against the same
      Postgres, running the purchase race. Correctness must hold because the database enforces it.
- [ ] **HTTP load test.** Gatling (it has a Kotlin DSL) or k6 against the running app: sustained
      concurrent purchases on hot SKUs, then check the final quantities with `GET /inventory`.

### Model-based (stretch)
- [ ] Property-based test (e.g. Kotest property testing): random sequences of receive and
      purchase operations checked against a simple in-memory model. The invariant must hold
      after every sequence.
