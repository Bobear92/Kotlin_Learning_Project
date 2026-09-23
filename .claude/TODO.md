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
- [ ] Contract gaps: malformed/missing body, wrong `quantity` type, SKU id format/case,
      integer overflow when adding stock
- [x] Schema migrations: **Flyway** (versioned plain-SQL migrations) (2026-09-23)

## Scaffold

- [x] Project layout decided: single Gradle module at the repo root, package by feature
      (`com.robertwoolbright.inventory`), contract moved to `src/main/resources/openapi/` (2026-09-23)
- [x] Spring Boot + Kotlin + Gradle Kotlin DSL project (Initializr, versions pinned in README) (2026-09-23)
- [x] Docker Compose file for Postgres; Spring Boot Docker Compose support for `bootRun` (2026-09-23)
- [x] Testcontainers for tests (`support/TestcontainersConfiguration.kt`) (2026-09-23)
- [ ] README "Build and run" section verified from a clean clone (verified in the working tree only)
- [ ] `V1__create_inventory.sql` migration (table + `CHECK (quantity >= 0)`), with the first feature
- [ ] `/walkthrough` of the scaffold (build.gradle.kts, compose.yaml, application.yml, the three .kt files)

## Features (one `/feature` + `/walkthrough` each)

- [ ] `GET /inventory`
- [ ] `GET /inventory/{skuId}`
- [ ] `POST /inventory/{skuId}` (create or add)
- [ ] `POST /inventory/{skuId}/purchase`
- [ ] Error handling: `text/plain` 400/404 bodies exactly as the contract specifies

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
- [ ] **New-SKU creation race.** Concurrent first-time `POST`s for a SKU that doesn't exist yet.
      All succeed, no duplicate-key errors, final = sum of all quantities.
- [ ] **Repeat to flush out flakiness.** Run the race tests many times (`@RepeatedTest`), since
      concurrency bugs are intermittent.
- [ ] **Negative control.** Temporarily swap in a naive read-then-write implementation and show
      the race test *fails*. This proves the test can actually detect an oversell.

### Database backstop
- [ ] Test that the `CHECK (quantity >= 0)` constraint rejects a direct update that would go
      negative, so the database protects the data even if the application has a bug.

### Across instances and under load
- [ ] **Multiple app instances.** Two app instances (or two Spring contexts) against the same
      Postgres, running the purchase race. Correctness must hold because the database enforces it.
- [ ] **HTTP load test.** Gatling (it has a Kotlin DSL) or k6 against the running app: sustained
      concurrent purchases on hot SKUs, then check the final quantities with `GET /inventory`.

### Model-based (stretch)
- [ ] Property-based test (e.g. Kotest property testing): random sequences of receive and
      purchase operations checked against a simple in-memory model. The invariant must hold
      after every sequence.
