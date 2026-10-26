# V1__create_inventory.sql

> Source: [src/main/resources/db/migration/V1__create_inventory.sql](../../../../../../../src/main/resources/db/migration/V1__create_inventory.sql) · Last synced with code: 2026-09-25

## Role

The first database migration, written by Robert. It creates the one table the whole service
uses. It sits below the repository: every SQL statement in `InventoryRepository` reads or writes
this table. It is also the last line of the oversell defense, because the `CHECK` constraint
lives here.

## Before the SQL: contract schemas are not tables

The contract has two schemas, `InventoryItem` and `InventoryQuantity`, and it's tempting to make
a table for each. **Schemas in an OpenAPI contract describe JSON that travels over HTTP, not
database tables.** The database is an implementation detail the contract never sees.

| Schema | Used as | Direction | Stored? |
| --- | --- | --- | --- |
| `InventoryQuantity` `{quantity}` | request body of both `POST`s | client → API | No. It's the *input* to an operation ("add 5"); the SKU comes from the URL path. |
| `InventoryItem` `{skuId, quantity}` | every `200` response | API → client | Yes. One row = one `InventoryItem`. |

So there is **one table**. In Kotlin both schemas become classes: `InventoryItem` now, and
`InventoryQuantity` as a request-body class with the `POST` feature. Classes match the contract;
tables match what needs storing. In bigger systems the two drift apart. An API response is
often assembled from several tables, and tables hold columns (timestamps, internal ids) that the
contract never shows.

## Walkthrough

### The file name is the version

```sql
-- Study notes: docs/learning/notes/main/resources/db/migration/V1__create_inventory.sql.md
```

**Spring (Flyway):** Spring Boot's Flyway auto-configuration looks in `classpath:db/migration`
and runs every migration that hasn't run yet, in version order, during startup and before the
app handles any request. The name must be `V<version>__<description>.sql`, with **two**
underscores. Flyway turns `create_inventory` into the description "create inventory".

**Lesson learned:** the first draft was `V1_create_inventory.sql`, with one underscore. Flyway
doesn't recognize that as a migration and, by default, **skips it without an error**. The
startup log shows no migration, and the first query fails with "relation inventory does not
exist". Silent skipping is the dangerous part, so check the startup log for
`Successfully applied 1 migration`.

**Never edit a migration after it has run.** Flyway stores each applied migration in its
`flyway_schema_history` table with a checksum of the file. If the file changes afterwards,
startup fails with a checksum mismatch. Schema changes go in a new file (`V2__...`). While
you're still developing locally, the fix is to reset the local database instead.

**Migrations are changes, not snapshots.** Think of them like git commits: each file describes
what changes from the previous state, not the whole schema. So V2 doesn't recreate the table, it
alters it:

```sql
-- V2__add_created_at_to_inventory.sql
ALTER TABLE inventory ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
```

A second `CREATE TABLE inventory` would fail with `relation "inventory" already exists` on any
database that already ran V1, and dropping and recreating the table would delete its data. The
schema is the whole chain, V1 then V2 then V3: a fresh database (a new teammate, or
Testcontainers) runs them all in order, and an existing one runs only the files it hasn't seen.
Both end up the same. (Added after the 2026-09-25 walkthrough, where "V2 recreates the table" was
the first guess.)

### The table and its key

```sql
CREATE TABLE inventory (
    -- Why: the SKU is the natural key; a surrogate id column would never be used by the API.
    -- Why: TEXT because the contract sets no max length; TEXT also compares exactly (case-sensitive).
    sku_id TEXT PRIMARY KEY,
```

**Natural key vs surrogate key.** Two valid designs were considered:

| | A. Surrogate key | B. Natural key (chosen) |
| --- | --- | --- |
| Columns | `id` (generated number, primary key) + `sku_id` | `sku_id` is the primary key |
| Uniqueness of `sku_id` | Must be added separately (`UNIQUE NOT NULL`). Forget it and the same SKU can have two rows: `GET` lists it twice and a purchase can't know which row to use. | Automatic: a primary key is unique and not null |
| Benefit | Other tables (orders, shipments) can reference a small, stable number, and a SKU can be renamed without touching them | One less column and one less thing to forget; every query in this API looks up by SKU anyway |

**Why B:** the contract never exposes an internal id, and nothing here would use one. If a
surrogate key is ever needed, it's a `V2__` migration.

**Either way, `sku_id` must be unique.** The `POST` feature will use `INSERT ... ON CONFLICT
(sku_id) DO UPDATE` to create-or-add in one atomic statement, and Postgres only accepts
`ON CONFLICT (sku_id)` when there is a unique constraint or index on `sku_id`.

**`PRIMARY KEY` vs `UNIQUE NOT NULL`.** An earlier draft used `UNIQUE NOT NULL` without
declaring a primary key. That behaves almost the same, and `ON CONFLICT` would work with it. But
the table would have no primary key, and a reader would have to work out which column
identifies a row. `PRIMARY KEY` says it directly, and it implies `UNIQUE` and `NOT NULL`.
Postgres also builds an index on it, so lookups by SKU are fast.

**`snake_case` column names.** An earlier draft named the column `skuID`. Two problems:

- **Postgres folds unquoted names to lowercase**, so `skuID` actually creates a column named
  `skuid`. (Only `"skuID"`, in double quotes, keeps its case, and then every query has to quote
  it too.)
- The tests (the spec) insert into `sku_id`.

The convention is `snake_case` in SQL (`sku_id`) and `camelCase` in Kotlin and JSON (`skuId`).
The repository translates between them when it maps a row to an `InventoryItem`.

**`TEXT` vs `VARCHAR(n)`.** An earlier draft used `VARCHAR(100)`. The contract says `skuId` is a
`string` with **no maximum length**, so any limit is invented, and a 101-character SKU would
fail at the database. The client would get a 500 error, which the contract doesn't describe. In
Postgres, `TEXT` has no length limit and performs the same as `VARCHAR(n)`. If a limit is ever
wanted, that's a contract gap to decide and document (README, "Open decisions"), not a number to
pick silently.

**Case-sensitive.** `TEXT` equality is exact: `CW-XYCS-BM-01` and `cw-xycs-bm-01` are different
SKUs. Whether to normalize case is still an open decision for the `POST` feature.

**Names vs values: two kinds of case.** Don't confuse this with the lowercasing above:

| | Example | Quotes | Case |
| --- | --- | --- | --- |
| Identifier (a table or column name) | `skuID`, `"skuID"` | none, or double `"` | Folded to lowercase unless double-quoted |
| Value (data) | `'W'`, `'w'` | single `'` | Stored exactly as written, never folded |

So `INSERT ... VALUES ('W', 5)` succeeds even when `'w'` already exists: they are two different
SKUs. It's a natural first guess that "SQL lowercases things", but that only applies to names.

### The quantity and the oversell backstop

```sql
    -- Why: the database rejects negative stock even if application code has a bug (oversell backstop).
    quantity INT NOT NULL CHECK (quantity >= 0)
);
```

**`INT`** is Postgres's shorthand for `INTEGER`: a 32-bit signed integer, which maps to Kotlin's
`Int` (max 2,147,483,647). Overflow when adding stock is a known contract gap for the `POST`
feature.

**`NOT NULL` matters more than it looks.** JDBC's `ResultSet.getInt` returns `0` for SQL
`NULL`, so without this constraint a missing quantity would silently look like "out of stock".

**Why no `DEFAULT 0`.** An earlier draft had one. A default only applies when an `INSERT` leaves
the column out, and every insert this API does supplies a quantity (a `POST` always adds at least
1). So the default could only ever fill in for a bug where code forgot the quantity, and it
would hide that bug by quietly inserting 0. Without the default, `NOT NULL` makes the same
bug fail loudly.

**`CHECK (quantity >= 0)`: the backstop.** A `CHECK` constraint is a condition every row must
satisfy. Postgres rejects any `INSERT` or `UPDATE` that would break it, with SQLSTATE `23514`
(`check_violation`). Spring translates that error into a `DataIntegrityViolationException`.

The purchase `UPDATE` already refuses to oversell through its `WHERE quantity >= :requested_quantity`
(see [`removeStock`](../../../inventory/InventoryRepository.kt.md#the-sql-a-data-modifying-cte)), so this
constraint only matters if application code has a bug. That's the point of a second line of
defense. The test `database rejects an update that would make quantity negative` in
`InventoryRepositoryTest` deliberately runs an unguarded `UPDATE` to prove it.

**Watch the keyword:** `CHECK` is required. `quantity INT NOT NULL (quantity >= 0)`, without it,
is a syntax error, and Flyway fails the whole startup on it.

**Contract:** `InventoryItem.quantity` has `minimum: 0`. This constraint guarantees that no
response can ever break that rule.

## Try it

With the app running (`.\gradlew.bat bootRun` in PowerShell), open a database shell (the user and database name
are in `compose.yaml`).

`bootRun` never reaches 100%: Gradle's progress bar stops at about 85% `EXECUTING` because the
task *is* the running server, and it only finishes when you press Ctrl+C. Look in the log for:

- `Migrating schema "public" to version "1 - create inventory"`, which proves Flyway recognized
  the file name;
- `Successfully applied 1 migration ... now at version v1`, which proves the SQL ran.

Then use a second terminal:

```sh
docker exec -it kotlin_learning_project-postgres-1 psql -U <user> -d <db>
```

```sql
\d inventory                                              -- columns, the primary key, the check
SELECT * FROM flyway_schema_history;                      -- what Flyway recorded
INSERT INTO inventory (sku_id, quantity) VALUES ('w', -1); -- rejected: violates check constraint
INSERT INTO inventory (sku_id, quantity) VALUES ('w', 1);
INSERT INTO inventory (sku_id, quantity) VALUES ('w', 2);  -- rejected: duplicate key
```

## Check your understanding

- Why is there no `inventory_quantity` table, even though the contract has an
  `InventoryQuantity` schema?
- You rename this file to `V1_create_inventory.sql`. What happens at startup, and what's the
  first error you'd see?
- You need a `created_at` column after V1 has run on someone's machine. What do you do, and what
  happens if you edit V1 instead?
- With a surrogate `id` key and no unique constraint on `sku_id`, what goes wrong when two
  requests create the same SKU?
- Why would `DEFAULT 0` make a bug harder to find?
