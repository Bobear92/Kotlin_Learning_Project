# Nuuly Inventory API

[![Tests](https://github.com/Bobear92/Nuuly_Inventory_Assessment/actions/workflows/tests.yml/badge.svg)](https://github.com/Bobear92/Nuuly_Inventory_Assessment/actions/workflows/tests.yml)

A small inventory-management REST API built for the Nuuly services assessment. It
receives stock by SKU, processes purchases without overselling, and reports current
inventory levels.

The API contract is [src/main/resources/openapi/inventory.yaml](src/main/resources/openapi/inventory.yaml)
(OpenAPI 3.0.3), and the implementation must conform to it exactly. The original brief
is in [docs/Nuuly_Assessment_Instructions.txt](docs/Nuuly_Assessment_Instructions.txt).

## Endpoints

| Method | Path | Behavior | Responses | Status |
| --- | --- | --- | --- | --- |
| `GET` | `/inventory` | List every SKU and its quantity (`[]` when empty) | `200` JSON array | Implemented; tests green; reviewed and walked through |
| `GET` | `/inventory/{skuId}` | Current quantity for one SKU (`404` body: `SKU not found: <skuId>`) | `200` JSON, `404` text | Implemented; tests green; reviewed and walked through |
| `POST` | `/inventory/{skuId}` | Add stock; creates the SKU if it is new (one atomic upsert) | `200` JSON, `400` text | Implemented; tests green; reviewed and walked through |
| `POST` | `/inventory/{skuId}/purchase` | Deduct stock, only if enough is on hand (one atomic statement; `400` body: `Insufficient inventory for <skuId>`) | `200` JSON, `400` / `404` text | Implemented; tests green; reviewed; walkthrough pending |

Request bodies are `{"quantity": <integer ≥ 1>}`. Success bodies are
`{"skuId": "...", "quantity": <integer ≥ 0>}`. Error bodies are plain-text strings
(`text/plain`), not JSON.

## Tech stack

| Choice | Why |
| --- | --- |
| **Kotlin** (JVM) | Nuuly's production language, and one I want to learn in depth (see [Learning Kotlin](#learning-kotlin-with-this-project)). |
| **Spring Boot**, Spring MVC | Nuuly's production framework. Nuuly evaluated Micronaut and found its speed gains didn't outweigh the environment problems it caused, so they chose Spring (see below). |
| **Gradle, Kotlin DSL** | The standard build for Kotlin and Spring. Build scripts are written in Kotlin too. |
| **PostgreSQL 18** + **Flyway** | The database enforces the no-oversell rule; Flyway applies versioned SQL migrations at startup. See the decision record below. |
| **JUnit 6** (Jupiter) + Spring test support | Controller tests via `MockMvc`, plus full-context integration tests. |
| **Testcontainers** | Tests run against a real Postgres in Docker, never an in-memory stand-in. |

Pinned versions: **Spring Boot 4.1.1**, **Kotlin 2.3.21**, **Java 21** (Gradle toolchain),
**Gradle 9.7.1** (wrapper), **PostgreSQL 18**. The project was generated with Spring
Initializr and then trimmed by hand; the exact command is in the commit
"Generate the Spring Boot project with Spring Initializr".

**Why Spring rather than Micronaut.** Seth, an architect at Nuuly, told me the team moved to
Spring because managing configuration across environments was painful with Micronaut. A bug in
that area once kept them out of production for three days. Micronaut starts faster, but for
long-running services a few seconds of startup matter far less than configuration that behaves
predictably everywhere. This project follows the same principle: the jar carries safe defaults in
`application.yml`, and each environment supplies its own values through environment variables or
command-line arguments, which Spring layers on top in a fixed order. No environment-specific
settings or passwords live in the repo.

## Open decisions

These are settled one at a time before the code that depends on them is written.
Each decision and its reasoning is recorded here once it is made.

| Decision | Options under consideration | Status |
| --- | --- | --- |
| Data store | **PostgreSQL**, run with Docker Compose. Considered in-memory and H2; see below. | Decided 2026-09-23 |
| Concurrency on purchase | **Single atomic SQL statements** + `CHECK (quantity >= 0)` backstop. See below. | Decided 2026-09-23 |
| Data-access library | **`JdbcClient`**: explicit SQL, maps to immutable data classes. Considered Spring Data JDBC, Spring Data JPA, Exposed. | Decided 2026-09-23 |
| Controllers | **Hand-written**, plus a contract-validation test (responses checked against `inventory.yaml`). Considered generating them with `openapi-generator`, but that hides the Spring MVC wiring and is overkill for 4 endpoints. | Decided 2026-09-23 |
| Schema migrations | **Flyway**: versioned plain-SQL files, run automatically at startup and in tests. Considered Liquibase (XML/YAML changesets, more ceremony) and `schema.sql` (no versioning). | Decided 2026-09-23 |
| List order and contents | **Ordered by `sku_id`** (the contract specifies no order; a fixed one keeps responses and tests stable). **Zero-stock SKUs are listed**: a sold-out SKU still exists, and `GET /inventory/{skuId}` returns it with quantity `0`. | Decided 2026-09-24 |
| Column types | `sku_id TEXT` (exact, case-sensitive match for now), `quantity INTEGER` (Kotlin `Int`). Considered `BIGINT`/`Long`; overflow will be handled when adding stock instead. | Decided 2026-09-24 |
| Error handling | A domain exception per error (`SkuNotFoundException`, `AddStockOverflowException`, `InsufficientInventoryException`), thrown by the service and mapped to a `text/plain` response by one `@RestControllerAdvice`. The same advice maps Spring's request-reading exceptions for `POST` (`MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `HttpMediaTypeNotSupportedException`) to `400`s, and never sends Spring's own exception messages to the client (2026-09-28). The handlers are global, so `POST /inventory/{skuId}/purchase` reuses the `404` and the three request-reading `400`s (2026-09-29). Exceptions and the handler live in the `inventory.error` subpackage. 404 body: `SKU not found: <skuId>`, no trailing period. Considered a `ResponseEntity` built in each controller (repeats per endpoint), `ResponseStatusException` (JSON body, the contract needs `text/plain`), and a flat or top-level `error` package. | Decided 2026-09-27 |
| Demo seed data | Tests insert their own rows. Whether a running app should start with sample data (e.g. a dev-only Flyway location) is decided once `POST /inventory/{skuId}` exists. | Open |
| Contract gaps (POST) | Settled for `POST /inventory/{skuId}` by Robert on 2026-09-28. **SKU ids: exact, case-sensitive match** (`W` and `w` are two SKUs; the contract sets no format, and normalizing would silently change the id a client sent). **Strict JSON:** malformed, empty or `null` bodies, a missing or `null` `quantity`, a string (`"5"`), a decimal (`1.5`) or a number too big for `Int` are all `400` `Invalid request body` (Jackson's lenient defaults would coerce `"5"` and truncate `1.5`). `quantity < 1`: `400` `quantity must be at least 1`, checked with Bean Validation in the controller layer (considered a manual check in the service). The service also has `require(quantity >= 1)` for callers that bypass HTTP; hitting it is our bug, so it's a `500`, not a `400` (added in review, 2026-09-28). **Wrong `Content-Type`:** `400` `Content-Type must be application/json` instead of Spring's `415`, which the contract doesn't list. **Overflow:** a total past `Int.MAX_VALUE` is `400` `Quantity would exceed the maximum stock`, refused atomically inside the upsert's SQL (considered leaving Postgres's `integer out of range` as a `500`). All `400` bodies are `text/plain`. | Decided 2026-09-28 |
| Contract gaps (purchase) | Settled for `POST /inventory/{skuId}/purchase` by Robert on 2026-09-29. **One SQL statement decides the outcome:** a data-modifying CTE (`WITH purchased AS (UPDATE ... WHERE quantity >= ? RETURNING quantity) SELECT remaining, EXISTS(...)`) deducts the stock and reports whether the SKU exists, and the repository returns a sealed `PurchaseResult` (`Purchased`, `SkuNotFound`, `InsufficientStock`). Considered an `UPDATE` followed by a lookup when it changed nothing (the SKU can change between the two statements) and `SELECT ... FOR UPDATE` in a transaction (correct, but holds the row lock for three round trips). **Insufficient stock:** `400` `Insufficient inventory for <skuId>`, no partial fill, stock unchanged. A sold-out SKU (quantity `0`) is insufficient stock, not a missing SKU. **Missing SKU:** `404` `SKU not found: <skuId>`, the same body as `GET /inventory/{skuId}`; a purchase never creates a SKU. **Invalid body:** the same four `400`s as `POST /inventory/{skuId}` (same `InventoryQuantity` schema). The body is validated before the SKU is looked up, so an invalid body for a missing SKU is a `400`, not a `404`. The service's `require(quantity >= 1)` also blocks direct callers, since a negative purchase would add stock. | Decided 2026-09-29 |
| Contract validation | `InventoryContractTest` checks every status of every endpoint against `inventory.yaml` with Atlassian's `openapi-request-validator-mockmvc` (declared status, content type, body schema). **`InventoryItem` is checked strictly** (Robert, 2026-09-30): the YAML marks neither field `required`, so a literal check would accept `{}`; the test requires both `skuId` and `quantity`, in memory, without changing the YAML. Extra fields are rejected by the validator's default. Requests that are invalid on purpose (the `quantity < 1` 400s) are checked for their response only. Self-checks prove the validator rejects a missing field, an extra field, a negative quantity, JSON errors, text/plain items and undeclared statuses such as `500`. | Decided 2026-09-30 |

### Decision record: PostgreSQL and atomic SQL

The key requirement is that **a purchase must never oversell, even when requests arrive at
the same moment.** In PostgreSQL, each write is a single atomic statement, so the database
guarantees this without any locking code in the application:

```sql
-- Receive stock: create the SKU or add to it
INSERT INTO inventory (sku_id, quantity) VALUES (?, ?)
ON CONFLICT (sku_id) DO UPDATE SET quantity = inventory.quantity + EXCLUDED.quantity
RETURNING sku_id, quantity;

-- Purchase: deducts only if enough stock exists, and reports which case happened
WITH purchased AS (
  UPDATE inventory SET quantity = quantity - :requested_quantity
  WHERE sku_id = :skuId AND quantity >= :requested_quantity
  RETURNING quantity
)
SELECT (SELECT quantity FROM purchased) AS remaining_quantity,       -- NULL: nothing bought
       EXISTS (SELECT 1 FROM inventory WHERE sku_id = :skuId) AS sku_exists;
-- remaining set -> 200; NULL + exists -> 400 insufficient; NULL + not exists -> 404
```

There is no read-then-write race, and correctness still holds with several instances of the
service running. A `CHECK (quantity >= 0)` constraint is a second line of defense.

Alternatives we rejected:

- **In-memory map:** needs no setup, but data is lost on restart and it only works with a
  single instance.
- **H2:** its SQL dialect differs from production databases.
- **Redis / document stores:** add nothing for a single integer per key.

Kotlin doesn't favor any particular database; it uses the same JDBC drivers as Java. It does
favor data-access libraries that work with immutable `data class`es (`JdbcClient`, Spring Data
JDBC, Exposed) over JPA, which needs compiler plugins to work around Kotlin's final-by-default classes.

The oversell guarantee is tested with several different mechanisms, each catching a different
class of bug (the full plan is in [.claude/TODO.md](.claude/TODO.md#oversell-protection-test-plan)):

| Test | What it proves |
| --- | --- |
| `InventoryConcurrencyTest` | Race tests: 50–60 threads released at once on one SKU (same-size buys, uneven sizes, restocks mixed with purchases, first-time creates). No oversell, no lost update. |
| `NegativeControlTest` | The race check can fail: a test-only read-then-write purchase passes it when buyers take turns and is caught when they race. |
| `MultiInstanceRaceTest` | Two complete app instances on one Postgres, buyers split across both over real HTTP. The guarantee lives in the database, not in either JVM. |
| `InventoryPropertyTest` | Kotest property test: 200 random sequences of receives and purchases agree with an in-memory model after every step. |
| `InventoryLoadTest` | 50 HTTP clients on three hot SKUs for 20 s, stock kept near zero; the books balance exactly per SKU. Run with `./gradlew loadTest`. |
| `InventoryRepositoryTest` | The `CHECK (quantity >= 0)` backstop rejects a direct write that would go negative. |

`InventoryContractTest` separately checks every response of every endpoint against `inventory.yaml`.

## Build and run

**Prerequisites:** JDK 21 and Docker Desktop (running). Postgres starts automatically in a
container; there is nothing to install or configure by hand.

```sh
./gradlew bootRun   # start the API on http://localhost:8080 (Windows PowerShell: .\gradlew.bat bootRun)
./gradlew test      # run all tests except the load test, each against a throwaway Postgres container
./gradlew loadTest  # HTTP load test, 20 s (options: -Dload.seconds=60 -Dload.clients=100)
./gradlew build     # compile, test and package build/libs/inventory-0.0.1-SNAPSHOT.jar
```

Try it once the app is running:

```sh
curl -i http://localhost:8080/inventory
# HTTP/1.1 200
# Content-Type: application/json
# []

curl -i http://localhost:8080/inventory/CW-XYCS-BM-01
# HTTP/1.1 404
# Content-Type: text/plain
# SKU not found: CW-XYCS-BM-01
```

`bootRun` starts Postgres from [compose.yaml](compose.yaml) automatically (Spring Boot's
Docker Compose support) and stops it on shutdown. Tests don't use Compose; Testcontainers
starts a fresh Postgres for them. No database URL or password needs configuring.

**Continuous integration.** Every push and pull request runs
[.github/workflows/tests.yml](.github/workflows/tests.yml) on GitHub Actions: one job runs
`./gradlew build` (every test except the load test), a second runs `./gradlew loadTest` in
parallel. Each run's summary page (Actions tab → a run) shows a readable report from
[scripts/test_report.py](scripts/test_report.py): the tests grouped by what they prove, every
failure with its assertion message, every test by name, and the load test's throughput and
latency. The full Gradle HTML report is attached to the run as an artifact. The same report works
locally after a build: `python scripts/test_report.py build/test-results/test`. The "Run
workflow" button on the Actions tab starts a fresh run. `/ship` waits for both checks to pass
before merging.

**Troubleshooting (Windows):** if `gradlew` says `JAVA_HOME is not set` right after installing
the JDK, the terminal (or VS Code itself) was opened before the install and has a stale
environment. Close and reopen it completely.

## Project layout

```text
build.gradle.kts         The build: plugins, dependencies, Kotlin/Java settings
settings.gradle.kts      Project name (single-module build)
gradlew, gradle/wrapper/ Gradle wrapper; pins the Gradle version, nothing to install
compose.yaml             Local Postgres for bootRun
src/main/
  kotlin/com/robertwoolbright/inventory/
    InventoryApplication.kt   Entry point; component scan starts here
    inventory/                The inventory feature: controller, service, repository, models
      error/                  Domain exceptions and the handler that maps them to HTTP errors
  resources/
    application.yml           Spring Boot configuration
    db/migration/             Flyway SQL migrations (V1__..., V2__...)
    openapi/inventory.yaml    The OpenAPI contract (source of truth)
src/test/kotlin/com/robertwoolbright/inventory/
  inventory/                  Feature tests: repository, service, controller, contract, races,
                              negative control, multi-instance, property-based, load
  support/                    Shared test setup (Testcontainers Postgres, race helper, HTTP client)
.github/workflows/       CI: tests + load test on every push, with a readable report
.claude/                 Claude Code configuration (hooks, agents, skills) — see below
CLAUDE.md                Project memory and coding standards for Claude Code
docs/
  learning/
    notes/               Study notes: one per source file, explaining it chunk by chunk
    walkthroughs/        Logs of each /walkthrough session
    kotlin-concepts.md   Index of every Kotlin/Spring concept, linked to its notes
  ai-sessions/           Exported transcripts of every AI session on this project
scripts/                 Hook scripts (branch guard, KDoc check, session export) and the CI test report
```

**Package by feature.** Code is grouped by what it does (`inventory/`), not by technical
layer (`controller/`, `service/`, `repository/`). File names still show the layer, so the
controller → service → repository flow is easy to follow. This is a general JVM/Spring
convention, not a Kotlin rule. One Kotlin difference is worth knowing: Kotlin has no
package-private visibility. Its `internal` modifier means "visible within this Gradle
module", so package boundaries are organizational, not enforced by the compiler.

## Learning Kotlin with this project

I'm using this assessment to learn Kotlin properly. The code itself stays clean, and the
teaching lives next to it in study notes:

- **In the code:** short KDoc on every non-private declaration, plus one-line comments for
  design decisions (`// Why:`) and OpenAPI rules (`// Contract:`).
- **In [docs/learning/notes/](docs/learning/notes/README.md):** one notes file per source file.
  It quotes the code a chunk at a time and explains the Kotlin features (compared with Java),
  the Spring behavior the code doesn't show, and the decisions behind it, then ends with
  self-check questions. A hook keeps the notes from falling behind the code.
- **A walkthrough after every feature.** Before moving on, I go through the new code line by
  line with Claude, answer check-your-understanding questions, and log the session in
  [docs/learning/walkthroughs/](docs/learning/walkthroughs/). New concepts are indexed in
  [docs/learning/kotlin-concepts.md](docs/learning/kotlin-concepts.md).
- **Interview prep.** Questions I expect in the follow-up interview, with folded answers, grow
  during each walkthrough in [docs/learning/interview-prep.md](docs/learning/interview-prep.md).

## AI-assisted development

I'm building this with [Claude Code](https://claude.com/claude-code). The configuration is
committed so reviewers can see how the AI was directed and checked:

| File | Purpose |
| --- | --- |
| [CLAUDE.md](CLAUDE.md) | Project memory: contract rules, stack, documentation standard, feature workflow. |
| [.claude/skills/feature](.claude/skills/feature/SKILL.md) | `/feature`: contract check → approved plan → Claude writes failing tests → **I write the code, Claude coaches and reviews** → `/sync-docs` → walkthrough hand-off. |
| [.claude/skills/sync-docs](.claude/skills/sync-docs/SKILL.md) | `/sync-docs`: updates study notes, concept index, README and TODO for whatever code changed, then runs `doc-reviewer`. |
| [.claude/skills/walkthrough](.claude/skills/walkthrough/SKILL.md) | `/walkthrough`: interactive, file-by-file teaching session with understanding checks, logged to `docs/learning/`. |
| [.claude/skills/ship](.claude/skills/ship/SKILL.md) | `/ship`: push the branch → open a PR → merge into `main` with a merge commit (never squash). |
| [.claude/agents/doc-reviewer.md](.claude/agents/doc-reviewer.md) | Read-only agent that audits changed code and its study notes against the documentation standard, accuracy first. |
| [.claude/TODO.md](.claude/TODO.md) | Working task list, including the oversell-protection test plan. |
| [.claude/settings.json](.claude/settings.json) | Hook configuration for the scripts below. |
| [scripts/branch_guard.py](scripts/branch_guard.py) | Keeps all work off `main`: flags a session that starts on `main`, blocks edits and commits there, and blocks direct pushes to `main` and squash merges. |
| [scripts/code_guard.py](scripts/code_guard.py) | Makes Claude Code ask me before Claude writes production code under `src/main/`: I write that code, Claude writes tests and coaches. |
| [scripts/test_report.py](scripts/test_report.py) | Turns Gradle's JUnit XML into the readable Markdown test report shown on each GitHub Actions run (also runnable locally). |
| [scripts/check_kdoc.py](scripts/check_kdoc.py) | Reports missing KDoc after every edit to a `.kt` file. |
| [scripts/check_notes.py](scripts/check_notes.py) | Stop hook: Claude can't finish a turn while a source file has changed more recently than its study notes. |
| [scripts/export_sessions.py](scripts/export_sessions.py) | Exports session transcripts to `docs/ai-sessions/` after every Claude turn. |
| [docs/ai-sessions/](docs/ai-sessions/) | Full transcripts of every session, including what I asked for and what I changed or rejected. |

**Git workflow:** every piece of work happens on its own branch (`feature/…`, `fix/…`,
`chore/…`) and reaches `main` only through a pull request merged by `/ship`, with the full
commit history preserved.
