# Nuuly Inventory API

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
| `GET` | `/inventory/{skuId}` | Current quantity for one SKU (`404` body: `SKU not found: <skuId>`) | `200` JSON, `404` text | Implemented; tests green; walkthrough pending |
| `POST` | `/inventory/{skuId}` | Add stock; creates the SKU if it is new | `200` JSON, `400` text | Not started |
| `POST` | `/inventory/{skuId}/purchase` | Deduct stock, only if enough is on hand | `200` JSON, `400` / `404` text | Not started |

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
| Error handling | A domain exception per error (`SkuNotFoundException`), thrown by the service and mapped to a `text/plain` response by one `@RestControllerAdvice`. Exceptions and the handler live in the `inventory.error` subpackage. 404 body: `SKU not found: <skuId>`, no trailing period. Considered a `ResponseEntity` built in each controller (repeats per endpoint), `ResponseStatusException` (JSON body, the contract needs `text/plain`), and a flat or top-level `error` package. | Decided 2026-09-27 |
| Demo seed data | Tests insert their own rows. Whether a running app should start with sample data (e.g. a dev-only Flyway location) is decided once `POST /inventory/{skuId}` exists. | Open |
| Contract gaps | Malformed or missing JSON body, `quantity` of wrong type, SKU id format/case sensitivity (currently exact, case-sensitive: `W` and `w` are two SKUs; options to decide with `POST`: keep exact match, normalize case in the app, a case-insensitive column, or reject non-canonical SKUs), integer overflow on add | Open |

### Decision record: PostgreSQL and atomic SQL

The key requirement is that **a purchase must never oversell, even when requests arrive at
the same moment.** In PostgreSQL, each write is a single atomic statement, so the database
guarantees this without any locking code in the application:

```sql
-- Receive stock: create the SKU or add to it
INSERT INTO inventory (sku_id, quantity) VALUES (?, ?)
ON CONFLICT (sku_id) DO UPDATE SET quantity = inventory.quantity + EXCLUDED.quantity
RETURNING sku_id, quantity;

-- Purchase: succeeds only if enough stock exists (0 rows updated -> 404 or 400)
UPDATE inventory SET quantity = quantity - ?
WHERE sku_id = ? AND quantity >= ?
RETURNING sku_id, quantity;
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

The oversell guarantee is tested with several different mechanisms: race tests, a negative
control, multi-instance tests, load tests and property-based tests. The full plan is in
[.claude/TODO.md](.claude/TODO.md#oversell-protection-test-plan).

## Build and run

**Prerequisites:** JDK 21 and Docker Desktop (running). Postgres starts automatically in a
container; there is nothing to install or configure by hand.

```sh
./gradlew bootRun   # start the API on http://localhost:8080 (Windows PowerShell: .\gradlew.bat bootRun)
./gradlew test      # run all tests, each against a throwaway Postgres container
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
  inventory/                  Feature tests: repository, controller, concurrency races
  support/                    Shared test setup (the Testcontainers Postgres)
.claude/                 Claude Code configuration (hooks, agents, skills) — see below
CLAUDE.md                Project memory and coding standards for Claude Code
docs/
  learning/
    notes/               Study notes: one per source file, explaining it chunk by chunk
    walkthroughs/        Logs of each /walkthrough session
    kotlin-concepts.md   Index of every Kotlin/Spring concept, linked to its notes
  ai-sessions/           Exported transcripts of every AI session on this project
scripts/                 Hook scripts (branch guard, KDoc check, session export)
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
| [scripts/check_kdoc.py](scripts/check_kdoc.py) | Reports missing KDoc after every edit to a `.kt` file. |
| [scripts/check_notes.py](scripts/check_notes.py) | Stop hook: Claude can't finish a turn while a source file has changed more recently than its study notes. |
| [scripts/export_sessions.py](scripts/export_sessions.py) | Exports session transcripts to `docs/ai-sessions/` after every Claude turn. |
| [docs/ai-sessions/](docs/ai-sessions/) | Full transcripts of every session, including what I asked for and what I changed or rejected. |

**Git workflow:** every piece of work happens on its own branch (`feature/…`, `fix/…`,
`chore/…`) and reaches `main` only through a pull request merged by `/ship`, with the full
commit history preserved.
