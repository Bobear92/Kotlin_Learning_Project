# InventoryRepository.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryRepository.kt](../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryRepository.kt) · Last synced with code: 2026-09-29

## Role

The data-access layer for inventory, written by Robert. It is the only class that talks to the
`inventory` table (created by [V1__create_inventory.sql](../resources/db/migration/V1__create_inventory.sql.md)).
Request flow: controller → service → **repository** → Postgres. `findAll` serves
`GET /inventory`, `findBySkuId` serves `GET /inventory/{skuId}`, `addStock`, the first write,
serves `POST /inventory/{skuId}`, and `removeStock` serves `POST /inventory/{skuId}/purchase`. Rows
come back as `InventoryItem`s, and a purchase comes back as a [`PurchaseResult`](PurchaseResult.kt.md).
The repository only reports what the table holds or what a write did. It never decides what a missing row or a refused
write *means* for the API; that's the [service's](InventoryService.kt.md) job.
[InventoryRepositoryTest](../../test/inventory/InventoryRepositoryTest.kt.md) checks it against a
real Postgres, and [InventoryConcurrencyTest](../../test/inventory/InventoryConcurrencyTest.kt.md)
fires `addStock` and `removeStock` from many threads at once.

Robert's own summary, written while he worked on it:

> The class initializes the JDBC client, which allows us to make SQL queries. `findAll` has the
> return type `List<InventoryItem>`: we query the database for all entries and order them
> alphabetically by `sku_id`. We are explicit in the query with the result-set lambda so that
> Kotlin knows we are getting inventory items and each item is non-null. Otherwise Kotlin won't
> compile, because we haven't dealt with the null case.

## Walkthrough

### Imports

```kotlin
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
```

**Kotlin:** Kotlin imports `kotlin.*` automatically (like Java's `java.lang`), and classes in the
same package need no import, which is why `InventoryItem` has none. Everything from a library,
Spring included, has to be imported.

### A Spring bean with constructor injection

```kotlin
/** Reads and writes inventory rows from Postgres, used by the service layer. */
@Repository
class InventoryRepository(
    private val jdbcClient: JdbcClient,
){
```

**Kotlin:** the parentheses after the class name are the **primary constructor**. `private val`
in front of a parameter declares a property and assigns it in one step. The Java equivalent is a
`private final JdbcClient jdbcClient;` field plus a constructor that sets it. `private` because
nothing outside the repository should reach into its database handle. `val` because the reference
never changes.

The **trailing comma** after the last parameter is optional. Kotlin style recommends it when each
item sits on its own line: adding a second parameter later then changes one line in the diff
instead of two, and lines can be reordered without fixing commas. Java allows this in array
initializers but not in parameter lists.

**Kotlin (KDoc):** `/** ... */` is **KDoc**, Kotlin's Javadoc. It belongs to the declaration
*directly* below it (annotations may sit in between, but a blank line or another comment
detaches it). The editor shows it when you hover over the class or function anywhere in the
project. It answers "what does this do?" for the caller. A `// Why:` comment answers "why is it
built this way?" for whoever changes the file, and the editor ignores it. The project's
[check_kdoc.py](../../../../../scripts/check_kdoc.py) requires KDoc on every non-private declaration.

**Spring:** two separate things happen here.
1. **Bean creation.** At startup, `@SpringBootApplication` scans its package and every package
   below it for classes with a stereotype annotation: `@Component`, or one of its specialized
   forms, `@Repository`, `@Service` and `@RestController`. `@Repository` is what makes Spring create
   an `InventoryRepository` in the running app. Without it, the service can't be injected, and the
   app fails to start with `NoSuchBeanDefinitionException`. The test doesn't need the annotation,
   because `@Import(InventoryRepository::class)` registers the class by hand. That's why the test
   alone can't prove the annotation is there. The full-context controller test does.
   `@Repository` rather than a plain `@Component` because it names the class's role: data access.
2. **Constructor injection.** Spring sees that the class has a single constructor, and fills in its
   parameters with matching beans. No `@Autowired` is needed for a single constructor. The test
   class needs `@Autowired` because JUnit creates it, not Spring. The `JdbcClient` bean itself
   comes from Spring Boot's auto-configuration, which builds a `DataSource` from the datasource
   settings and a `JdbcClient` on top of it. Beans are created at startup, so a missing dependency
   fails fast instead of on the first request.

**Spring (exception translation):** `JdbcClient` already turns `SQLException`s into Spring's
`DataAccessException` hierarchy (see the backstop test's `DataIntegrityViolationException`).
For JDBC code, `@Repository` is mostly about bean creation and naming the role.

### `findAll`: the function signature

```kotlin
/** Returns every SKU with its quantity, sorted by SKU id; zero-stock SKUs included, empty list when none. */
fun findAll(): List<InventoryItem> = jdbcClient.sql("SELECT sku_id, quantity FROM inventory ORDER BY sku_id")
```

**Kotlin:** a function is `fun name(params): ReturnType`. Parameters are written `name: Type`,
the reverse of Java, and the return type comes after the parentheses. `findAll` takes no
parameters. No modifier means `public`, which is what the service needs
([public by default](../../test/inventory/InventoryRepositoryTest.kt.md)).

`List<InventoryItem>` is a generic type, as in Java. Two Kotlin differences:
- **`List` is read-only.** It has no `add` or `remove`. A changeable list is a `MutableList`. The
  caller gets a snapshot it can't modify.
- **No `?`, so never null.** The compiler won't let this function return `null`. That's how the
  "empty list, never null" test is guaranteed by the type, not just by the implementation.

**Kotlin (expression body):** `= expression` instead of `{ ... return ... }`. The whole body is one
call chain, so its result is the return value. Robert first wrote a block body with a local `val`
and `return`, and shortened it once it passed. Concept explained in full in
[TestcontainersConfiguration.kt](../../test/support/TestcontainersConfiguration.kt.md#the-container-bean).

**SQL:**
- `SELECT sku_id, quantity` names the columns instead of `SELECT *`. The row mapper below reads
  those two columns by name, so the query states exactly what the mapper depends on. A column
  added later can't silently change what the query returns.
- `ORDER BY sku_id`: without `ORDER BY`, SQL guarantees **no** order. Postgres returns rows in
  whatever order is cheapest, which can change after updates, deletes or a new query plan. The
  decision (2026-09-24) was to sort by SKU id so every response is deterministic. `sku_id` is
  `TEXT`, so the order is by text, not numeric. Postgres sorts text by the database's **collation**
  (its sorting rules), so uppercase, lowercase and digits don't always follow a plain A-Z order.
  The rule we promise is simply "ordered by `sku_id`". The test inserts SKUs out of order to prove it.
- Column names are the **database** names (`sku_id`, snake_case), not the Kotlin property
  names (`skuId`).

**Spring:** `jdbcClient.sql(...)` only *prepares* a statement. Nothing runs until a terminal
call such as `.list()`:

| Step | What it does | Touches the database? |
| --- | --- | --- |
| `.sql("SELECT ...")` | stores the SQL text | no |
| `.query { ... }` | stores the recipe for turning **one row** into an `InventoryItem` | no |
| `.list()` | runs the query, applies the recipe to each row, collects the results | **yes** |

`.query` doesn't work on results; it's handed over *before* the query runs.

### Mapping each row: the lambda

```kotlin
fun findAll(): List<InventoryItem> = jdbcClient.sql("SELECT sku_id, quantity FROM inventory ORDER BY sku_id")

// Why: Chose the result set lambda instead of InventoryItem::class.java so Kotlin sees the items as non null.
        .query { rs, _ ->
            InventoryItem(rs.getString("sku_id"), rs.getInt("quantity"))
        }
        .list()
```

**Kotlin (lambdas):** `{ rs, _ -> ... }` is a **lambda**, a function written inline. Parameters go
left of `->` and the result goes right. The last expression is the return value, with no `return`.
- `rs` is only a name (short for `ResultSet`, the JDBC convention). `row` would work just as well.
- `_` means "there's a parameter here, and I don't use it". Spring's `RowMapper` always passes
  two arguments (the result set and the row number), so the lambda must accept two. `_` has
  nothing to do with looping: `.list()` does the looping and calls the lambda once per row.
- **The lambda returns a value on every call.** Its last expression, `InventoryItem(...)`, is the
  return value, and `.list()` collects those return values into the list. Without them there would
  be nothing to collect. It's the same shape as `map` on a plain list:

  ```kotlin
  val books = listOf(Book("Dune", 1965), Book("Emma", 1815))
  val titles = books.map { book -> book.title.uppercase() }   // ["DUNE", "EMMA"]
  ```

  | Call | Input | Return value |
  | --- | --- | --- |
  | 1 | `Book("Dune", 1965)` / row `('a-sku', 5)` | `"DUNE"` / `InventoryItem("a-sku", 5)` |
  | 2 | `Book("Emma", 1815)` / row `('b-sku', 0)` | `"EMMA"` / `InventoryItem("b-sku", 0)` |

  Only what the lambda returns reaches the result: `{ book -> book.year }` would give
  `[1965, 1815]`, a `List<Int>`, and the titles would be gone.
- The types are inferred from `RowMapper`, so you never write `rs: ResultSet`.
- **Trailing lambda:** when a lambda is the last argument, it moves outside the parentheses, and
  when it's the only one, the parentheses disappear, so `.query { ... }` instead of `.query({ ... })`.
  See [trailing lambdas](../../build.gradle.kts.md#the-file-is-kotlin-trailing-lambdas).
- **SAM conversion:** `RowMapper` is a Java interface with a single abstract method. Kotlin lets a
  lambda stand in for it. In Java you'd write the lambda `(rs, rowNum) -> new InventoryItem(...)`.

**JDBC:** `rs.getString("sku_id")` and `rs.getInt("quantity")` read the current row's columns by
database name. Every name here must appear in the `SELECT`, or it fails at runtime with "column
not found". Reading by position (`getInt(2)`) also works, but breaks silently if the `SELECT` is
reordered.

**Why a hand-written lambda and not `query(InventoryItem::class.java)`:** Robert's first version
used the class-based mapping, and it didn't compile:

```
Return type mismatch: expected 'List<InventoryItem>', actual '(Mutable)List<InventoryItem?>'.
```

Spring 7 annotates its Java API with JSpecify nullability annotations, and Kotlin reads them
(see [platform types](../../build.gradle.kts.md#compiler-options)). In spring-jdbc 7.0.9:

```java
<T> MappedQuerySpec<@Nullable T> query(Class<T> mappedClass);
<T extends @Nullable Object> MappedQuerySpec<T> query(RowMapper<T> rowMapper);
```

- The class-based version is declared as "each row *might* map to null", because it also handles
  single-column queries where the value can be SQL `NULL`. So Kotlin sees `InventoryItem?`.
- The row-mapper version keeps the lambda's own return type. The lambda always builds a real
  `InventoryItem`, so the list is `List<InventoryItem>`.

The fix works at **compile time**: the class-based mapping would never actually have returned null
for these rows, but the compiler couldn't prove it. The compiler never looks at the database, so it
can't know `sku_id` is a primary key; it only reads Spring's declared types. The non-null columns
matter at *runtime* instead (see "Null safety, one level deeper" below). It's also more explicit, since you can see
which column goes into which property. That fits the reason `JdbcClient` was chosen (explicit SQL).

`(Mutable)List` is Kotlin saying it can't tell whether a Java `List` is meant to be changed. It's
harmless, because it fits a read-only `List` return type.

**Null safety, one level deeper:** `rs.getString(...)` is a Java method, so its result is a
platform type (`String!`). If it were ever null, Kotlin would throw when passing it into
`InventoryItem`'s non-null `skuId`. That can't happen: `sku_id` is the primary key, and a primary
key is never NULL. The migration's constraints back up the Kotlin types.

**Spring:** `.list()` runs the query, calls the lambda once per row, and collects the results. No
rows means an empty list, never null.

**Kotlin (a comment in the middle of a chain):** the `// Why:` comment and the blank line sit
*inside* the expression, between `.sql(...)` and `.query`. That still compiles: a line that starts
with `.` continues the expression above it, and comments and blank lines in between are ignored.
The comment sits next to the part of the chain it explains.

### `findBySkuId`: a nullable return type

```kotlin
/** Returns a SKU with its quantity for a specific SKU. Will return null if no such SKU exists */
fun findBySkuId(skuId: String): InventoryItem? = jdbcClient.sql("SELECT sku_id, quantity FROM inventory WHERE sku_id = :skuId")
```

**Kotlin (nullable types):** the `?` in `InventoryItem?` is part of the type. `InventoryItem`
means "always an item". `InventoryItem?` means "an item or `null`". They are two different types, and
the compiler keeps them apart:
- You can't call `.quantity` on an `InventoryItem?` without first dealing with the `null` case
  (`?.`, `?:`, an `if (x != null)` check, or `!!`).
- You can't return `null` from a function declared as `InventoryItem`, or pass an `InventoryItem?`
  where an `InventoryItem` is expected.

In Java every object reference can be null and nothing warns you. The nearest Java habit is
returning `Optional<InventoryItem>`. Kotlin puts that information in the type itself, at no cost:
an `InventoryItem?` is just a normal reference at runtime, with no wrapper object.

**Why nullable and not an exception here:** "no row" is an ordinary answer to a lookup, not a
failure. The repository says "nothing there" with `null`, and the service decides that, for this
endpoint, nothing there means `404`. (The purchase endpoint doesn't use this lookup: a separate
lookup after the write would be a race, so `removeStock` reports "no such SKU" from inside its own
statement.) The repository test `findBySkuId returns null for a missing SKU` pins this down.

**SQL:** `WHERE sku_id = :skuId` compares text exactly, so `widget` and `WIDGET` are different
SKUs (the case-sensitivity decision in the README's contract-gaps row). `:skuId` is a **named
parameter**: a placeholder, not the value. It is filled in by the `.param(...)` call below.

### Binding the parameter and mapping the row

```kotlin
// Why: We parameterized the skuid instead of adding it directly to the sql above for sql injection prevention.
        .param("skuId", skuId)
        .query{rs, _ ->
            InventoryItem(skuId = rs.getString("sku_id"), quantity = rs.getInt("quantity"))
        }
```

**Spring (`.param`):** `.param("skuId", skuId)` binds the Kotlin value to the `:skuId` placeholder.
`JdbcClient` turns `:skuId` into JDBC's `?` and sends the value separately from the SQL text, as
a `PreparedStatement` parameter. The database parses the SQL first and only then receives the value,
so the value can never become part of the SQL.

**Why (SQL injection):** building the SQL with a string template instead,
`"... WHERE sku_id = '$skuId'"`, would let a request for the SKU `x' OR '1'='1` rewrite the query.
The SKU comes straight from the URL, so it's untrusted input. Binding it as a parameter
makes any SKU, however strange, just a string to compare. Named parameters (`:skuId`) instead of
positional ones (`?`) are a readability choice: the name says which value goes where. The
`.param` call also accepts a position, `.param(1, skuId)`, for `?` placeholders.

**Kotlin (named arguments):** `InventoryItem(skuId = ..., quantity = ...)` names each constructor
argument, where `findAll` passes them by position. Both compile to the same call. Named arguments
make it impossible to swap two values of compatible types and can be in any order. Concept explained in
[TestcontainersConfiguration.kt](../../test/support/TestcontainersConfiguration.kt.md#testconfigurationproxybeanmethods--false).

The lambda is the same row mapper as in `findAll` (see [the lambda](#mapping-each-row-the-lambda)).
`.query{` without a space is legal, and it formats the same.

### `singleOrNull`: zero or one row

```kotlin
// Why: The list singleOrNull below, we did this over the Java optional so we can stay within kotlin. We wont have more than one entry in the list because sku_id is the primary key so it is unique.
        .list().singleOrNull()
```

**Kotlin (`singleOrNull`):** a standard-library function on any `List`. It returns the only
element if the list has exactly one, and `null` otherwise:

| List | `singleOrNull()` | `single()` | `firstOrNull()` |
| --- | --- | --- | --- |
| `[]` | `null` | throws `NoSuchElementException` | `null` |
| `[a]` | `a` | `a` | `a` |
| `[a, b]` | **`null`** | throws `IllegalArgumentException` | `a` |

Note the last row: with two elements, `singleOrNull()` returns `null` silently. It doesn't
throw. So if two rows could ever match, the SKU would be reported as missing. That can't happen here
for the reason in the `// Why:` comment: `sku_id` is the primary key, so the database never holds two
rows with the same SKU. As with `findAll`, the migration's constraints are what make the Kotlin code
correct.

**The failure mode, traced end to end** (walkthrough, 2026-09-28): if the primary key were dropped
and two `widget` rows existed, `singleOrNull()` would return `null`, the service would throw
`SkuNotFoundException`, and the client would get `404 SKU not found: widget` for a SKU that exists
twice. The bug would be silent. Swapping in `.single()` would make it loud (a 500), but `.single()`
also throws on zero rows, the normal "not found" case. A lookup that allowed duplicates would need
all three cases spelled out: empty → `null`, one → the item, more → an error. The real fix is
keeping the primary key. See [interview prep](../../../interview-prep.md#what-happens-if-two-rows-ever-share-the-same-sku).

**Why not `JdbcClient`'s own `.optional()`:** the query spec also has `.optional()`, which returns a
Java `Optional<InventoryItem>`. Kotlin code would then convert it with `.orElse(null)` or
`.getOrNull()`. `.list().singleOrNull()` gets `InventoryItem?` directly, and a nullable type is the
Kotlin way to say "maybe absent". `.single()` was ruled out because it throws on no rows, and no rows
is a normal answer here.

**Kotlin (why the return type works):** `.list()` returns `List<InventoryItem>` (non-null items,
thanks to the lambda), and `singleOrNull()` on a `List<T>` returns `T?`. That gives
`InventoryItem?`, which matches the declared return type.

### `addStock`: the signature and the two decisions

```kotlin
/**
 * Adds quantity to a SKU's stock, creating the SKU if it doesn't exist yet, and returns the row with the new total.
 * Returns null, changing nothing, only if the new total would go past Int.MAX_VALUE. Quantity must be at least 1.
 */
fun addStock(skuId: String, quantity: Int): InventoryItem? = jdbcClient.sql(
// Why: one upsert instead of find-then-insert, so concurrent requests can't race each other.
// Why: the overflow guard in the WHERE clause is written as current <= MAX - new, because current + new would itself overflow in Postgres.
```

**Kotlin:** the same shape as `findBySkuId`: an expression body returning a nullable
[`InventoryItem?`](#findbyskuid-a-nullable-return-type). Here `null` means "refused", not "not
found". The two `// Why:` comments sit *inside* the argument list of `sql(`, which is legal for the
same reason as the [comment in the middle of a chain](#mapping-each-row-the-lambda): the compiler
ignores comments wherever they are.

**KDoc:** it states the precondition ("Quantity must be at least 1") because nothing in this function
checks it. The [service's `require`](InventoryService.kt.md#receiveinventory-a-precondition-and-a-block-body)
enforces it. The guard's arithmetic below only holds for a positive quantity.

**Naming:** `addStock` is a data word: what happens to the row. The service calls the same action
`receiveInventory`, a business word. Each layer names things in its own vocabulary (see the
[service notes](InventoryService.kt.md#receiveinventory-a-precondition-and-a-block-body)).

**Why one upsert, not find-then-insert:** the obvious version is two steps (hypothetical, not in
the file):

```kotlin
val existing = findBySkuId(skuId)
if (existing == null) insert(skuId, quantity) else update(skuId, existing.quantity + quantity)
```

With two requests for the same SKU at the same moment, both can run `findBySkuId` before either
writes:

| Time | Request A (+3) | Request B (+4) | Row |
| --- | --- | --- | --- |
| 1 | reads 5 | | 5 |
| 2 | | reads 5 | 5 |
| 3 | writes 5 + 3 = 8 | | 8 |
| 4 | | writes 5 + 4 = 9 | **9** (A's 3 units are lost) |

For a new SKU it's worse: both see "no row", both `INSERT`, and the second fails with a duplicate-key
error. A single SQL statement can't be interleaved like this: Postgres runs it atomically and locks
the row while it changes it. `InventoryConcurrencyTest` proves both cases.

**Where the decision lives** (walkthrough, 2026-09-29): in the two-step version, the decision
("does it exist? add or insert?") is made in **Kotlin, outside the SQL**, between two trips to the
database. The database sees two unrelated statements and doesn't hold anything for us in between.
In the upsert, `ON CONFLICT` *is* the `if`, and Postgres makes the decision inside one statement.
The rule: if Kotlin reads a value and then writes based on it, there's a gap. Moving the decision
into one SQL statement closes it. Purchase needs the same thing: "subtract only if there's enough"
has to live in the SQL, and it does (see [`removeStock`](#removestock-three-outcomes-from-one-statement)).

**Does the lock slow things down?** (Robert's question, 2026-09-29) Not in practice:
- Postgres locks **one row**, not the table. Two requests for `widget` queue; `widget` and `gadget`
  run in parallel.
- The lock is held only while one statement runs, typically well under a millisecond.
- The two-step version is slower even when nothing collides: two round trips instead of one. Making
  it correct (a transaction plus `SELECT ... FOR UPDATE`) holds the lock across both round trips
  and the Kotlin in between, which is *longer* than the upsert's lock.

It only hurts with one extremely hot SKU (thousands of writes a second to a single row), and that
scale problem has its own fixes (batching, splitting the counter across rows).

### The upsert

```kotlin
    """
        INSERT INTO inventory (sku_id, quantity)
            VALUES (:skuId, :quantity)
                ON CONFLICT (sku_id)
                    DO UPDATE SET quantity = inventory.quantity + EXCLUDED.quantity
                        WHERE inventory.quantity <= :maxQuantity - EXCLUDED.quantity
                            RETURNING sku_id, quantity
            """
)
```

**Kotlin (raw string):** `"""` is a raw string, explained in
[InventoryControllerTest.kt](../../test/inventory/InventoryControllerTest.kt.md). It spans lines
without `\n` or `+`. There's no `trimIndent()`: SQL ignores the extra spaces and line breaks. The
deep indentation is Robert's layout and means nothing to Postgres.

**SQL, clause by clause:**

| Clause | What it does |
| --- | --- |
| `INSERT INTO inventory (sku_id, quantity) VALUES (:skuId, :quantity)` | try to add a new row |
| `ON CONFLICT (sku_id)` | if a row with that `sku_id` already exists (the primary key would be violated)… |
| `DO UPDATE SET quantity = inventory.quantity + EXCLUDED.quantity` | …update that row instead of failing |
| `WHERE ...` | …but only if this condition holds (the overflow guard, below) |
| `RETURNING sku_id, quantity` | send back the row as it is after the insert or update |

This is called an **upsert** (update or insert). The endpoint's contract is exactly that: "If the SKU
does not yet exist in inventory, it is created with the given quantity. If it already exists, the
quantity is added to the current stock."

**SQL (`inventory` vs `EXCLUDED`):** inside `DO UPDATE`, two rows are in play:
- `inventory.quantity` is the row **already in the table** (the current stock).
- `EXCLUDED.quantity` is the row the `INSERT` **tried to add** and couldn't, so it holds the
  requested quantity. It's called "excluded" because the conflict kept it out.

Like `it` in a Kotlin lambda, `EXCLUDED` is a name Postgres gives you; you don't declare it. For
widget at 5 and a request of +3: `inventory.quantity` is 5, `EXCLUDED.quantity` is 3, and the new
value is 8. (`EXCLUDED.quantity` has the same value as `:quantity` here, and reusing `:quantity`
would also work with one `.param` call. `EXCLUDED` is the standard idiom, and it still means "the
proposed row" if the `VALUES` ever change.)

**SQL (`RETURNING`):** without it, an `INSERT` or `UPDATE` only reports how many rows changed.
`RETURNING` makes the write return the changed row, like a `SELECT`, in the same statement. That's
how the endpoint gets the new total without a second query, which could see another request's
change. It must list columns (or `*`); Robert's first version had none.

**Without `RETURNING` at all:** the statement returns only a row count, but `.query { }` expects
rows. The Postgres driver throws `No results were returned by the query`, Spring wraps it in a
`DataAccessException`, and every POST gets a `500`. The compiler can't see it, because SQL is a string.

**Contract:** the `200` body is "the updated inventory state for the SKU", which is exactly the
row `RETURNING` gives back.

### The overflow guard

```kotlin
WHERE inventory.quantity <= :maxQuantity - EXCLUDED.quantity
```

**SQL (a `WHERE` on `DO UPDATE`):** if the condition is false, the update is skipped, the row stays
as it was, and `RETURNING` returns **no row**. Nothing is written. That's what "refused" means here,
and it happens inside the same atomic statement, so no other request can slip in between the check
and the write.

**Why this form (the algebra):** the rule is "the new total must fit in an `Int`":

```text
current + new <= MAX        ← the natural way to write it
current       <= MAX - new  ← the same rule, with new moved to the right
```

Both say the same thing, but only the second is safe to *compute*. The column is a Postgres
`integer` (4 bytes, like Kotlin's `Int`), so `current + new` itself overflows when the total is too
big, and Postgres stops with `ERROR: integer out of range` before the comparison happens: a `500`
instead of a clean refusal. `MAX - new` can't overflow, because `new` is at least 1.

That last part is the precondition in the KDoc. With a **negative** quantity, `MAX - (-5)` is
`MAX + 5`, which overflows. Robert asked whether `MAX - EXCLUDED` could overflow; this is the answer:
only for `new < 0`, which the service's `require` rules out.

**Alternative rejected:** cast to a bigger type, `inventory.quantity::bigint + EXCLUDED.quantity
<= 2147483647`. It works, but the rearranged form needs no casts and reads as plain arithmetic.

**Only the update is guarded:** a *new* SKU goes through `INSERT`, which has no `WHERE`. It doesn't
need one: the quantity is a Kotlin `Int`, so it already fits.

**Contract:** the README "Contract gaps (POST)" decision: an add that would overflow is a `400`
(`Quantity would exceed the maximum stock`) with nothing changed. The "nothing changed" half is
proven by `InventoryControllerTest`'s overflow test, which checks the quantity afterwards.

### Binding the three parameters and reading the row

```kotlin
        .param("skuId", skuId)
        .param("quantity", quantity)
        .param("maxQuantity", Int.MAX_VALUE)
        .query{rs, _ ->
            InventoryItem(skuId = rs.getString("sku_id"), quantity = rs.getInt("quantity"))
        }
        .list().singleOrNull()
```

**Spring:** each `:name` in the SQL gets its value from a `.param` call, as in
[`findBySkuId`](#binding-the-parameter-and-mapping-the-row). Every placeholder must be bound, or the
query fails at run time. SQL strings are only checked when they run, which is why the repository
tests matter: the compiler can't tell a typo in the SQL from a correct query.

**Why `Int.MAX_VALUE` as a parameter:** the limit comes from the Kotlin type, so it's taken from the
Kotlin constant instead of typing `2147483647` into the SQL: the limit visibly follows the Kotlin
type instead of being a magic number.

**Kotlin (`Int.MAX_VALUE`):** a constant on `Int`'s companion object (Kotlin's place for what Java
calls static members), the same as Java's `Integer.MAX_VALUE`: 2,147,483,647.

**Spring (`.query` on a write):** `RETURNING` makes the `INSERT` produce rows, so it's read with
`.query { }` and `.list()` like a `SELECT`. The row mapper is the same lambda as in
[`findAll`](#mapping-each-row-the-lambda).

**Kotlin (`singleOrNull`):** as in [`findBySkuId`](#singleornull-zero-or-one-row): one row → the
item, no rows → `null`. Here "no rows" means the overflow guard refused the update. Two rows can't
happen: an upsert touches exactly one `sku_id`.

### `removeStock`: three outcomes from one statement

```kotlin
/**
 * Removes the requested quantity because of a purchase from the SKU's stock.
 * Quantity must be at least 1.
 * Returns [PurchaseResult.Purchased] with the remaining stock if successful, [PurchaseResult.InsufficientStock] if there is not enough quantity, [PurchaseResult.SkuNotFound] if the skuId doesn't exist.
 */
fun removeStock(skuId: String, quantity: Int): PurchaseResult = jdbcClient.sql(
    // Why: One statement instead of two (update, then select), so another request can't change the stock between them (a race condition)
```

**Kotlin (the return type):** `removeStock` returns a [`PurchaseResult`](PurchaseResult.kt.md), not
`InventoryItem?`. A purchase has three outcomes, and a `null` could only represent one of the two
failures. The result is always one of the three cases and is never `null`, so the type has no `?`.

**KDoc (`[PurchaseResult.Purchased]`):** square brackets in KDoc make a **link** to another
declaration. When you hover the function in the editor, each one is clickable, and it jumps to that case.
It's Javadoc's `{@link PurchaseResult.Purchased}`, written more briefly. The precondition
"Quantity must be at least 1" is stated for the same reason as in `addStock`: nothing in this
function checks it. The [service's `require`](InventoryService.kt.md#purchaseinventory-one-branch-per-outcome)
does. For this function it matters even more. With `-5`, `quantity >= -5` is always true and
`quantity - (-5)` *adds* 5.

**Why one statement (Robert, 2026-09-29):** three designs were compared.

| Design | Round trips | Problem |
| --- | --- | --- |
| `UPDATE ... WHERE quantity >= ?`, and then, when it updates nothing, `SELECT` to find out why | 2 | Between the two statements the SKU can be created or restocked, so the reason the second query reports might not match what the `UPDATE` saw |
| A transaction with `SELECT ... FOR UPDATE`, then decide in Kotlin, then `UPDATE` | 3 | Correct, but the row lock is held across three round trips *and* the Kotlin code in between |
| **One statement that updates and classifies (chosen)** | 1 | None: the check, the write and the classification happen together |

This is the same rule as [`addStock`'s upsert](#addstock-the-signature-and-the-two-decisions): if
Kotlin reads a value and then writes based on it, there's a gap. Here the decision "is there enough
stock?" happens inside the `UPDATE`'s `WHERE` clause.

**Naming:** `removeStock` is the data word that mirrors `addStock`. The service calls the same
action `purchaseInventory`, the business word.

### The SQL: a data-modifying CTE

```kotlin
"""
    WITH purchased AS (
        UPDATE inventory SET quantity = quantity - :requested_quantity
            WHERE sku_id = :skuId AND quantity >= :requested_quantity
                RETURNING quantity
    )
    SELECT
        (SELECT quantity FROM purchased) AS remaining_quantity,
            EXISTS (SELECT 1 FROM inventory WHERE sku_id = :skuId) AS sku_exists
"""
)
```

This is the densest part of the project, so the SQL is explained one piece at a time.

**SQL (`WITH ... AS (...)`, a CTE):** `WITH name AS (query)` defines a **common table expression**
(CTE): a named, temporary result that the rest of the statement can read like a table. It's
similar to declaring a local `val` and then using it below. Here the CTE is an `UPDATE`, not a
`SELECT`. Postgres calls that a **data-modifying CTE**. The `UPDATE` runs exactly once, and its
`RETURNING` rows become the contents of `purchased`.

**SQL (the conditional `UPDATE`):**

| Part | What it does |
| --- | --- |
| `SET quantity = quantity - :requested_quantity` | subtract the requested amount |
| `WHERE sku_id = :skuId` | only this SKU (an exact, case-sensitive match) |
| `AND quantity >= :requested_quantity` | only if there's enough. `>=`, not `>`, so selling the last unit is allowed |
| `RETURNING quantity` | send back the new quantity; if no row was updated, send back nothing |

So `purchased` has **one row** (the remaining stock) if the purchase happened, and **no rows** if
it didn't. No rows can mean either "no such SKU" or "not enough stock". The outer `SELECT` tells
the two apart.

**SQL (the outer `SELECT` has no `FROM`):** `SELECT a, b` without `FROM` returns exactly **one row**
built from the two expressions. That's always true, whatever the data. That's why the Kotlin below
can use `.single()`.

**SQL (a scalar subquery):** `(SELECT quantity FROM purchased)` in parentheses, used as a value,
is a **scalar subquery**. It yields the single value if there's one row, and SQL `NULL` if there
are none. (Two rows would be an error, but the `WHERE sku_id =` on a primary key can't update two.)
So `remaining_quantity` is the new stock, or `NULL` for "nothing was bought".

**SQL (`EXISTS` and `SELECT 1`):** `EXISTS (subquery)` is `true` if the subquery returns at least
one row, and `false` otherwise. It's never `NULL`. The `1` in `SELECT 1` is a placeholder: `EXISTS`
only checks whether a row comes back, not what's in it, so the convention is to select a constant.
`SELECT sku_id` would give the same answer.

**SQL (`AS` names the columns):** `AS remaining_quantity` and `AS sku_exists` label the two result
columns. The Kotlin reads them by those names.

**The trap: one snapshot for the whole statement.** Every part of one SQL statement reads the
database as it was when the statement *started*. The main `SELECT` does **not** see the `UPDATE`
made in the `WITH`, even though the `UPDATE` appears first. The only way to see the change is through
the CTE's `RETURNING`. Robert's first draft read the new quantity with `SELECT quantity FROM
inventory` in the outer query, and got the *old* stock back (5, not 3). Two consequences follow:

- `remaining_quantity` must come from `purchased` (the `RETURNING` row), not from `inventory`.
- `EXISTS (... FROM inventory ...)` reading the old snapshot is **fine**: an `UPDATE` never adds or
  removes rows, so "does the row exist?" has the same answer before and after it.

**Concurrency: why two buyers can't both get the last unit.** Postgres's default isolation level is
`READ COMMITTED`. When two statements try to update the same row, the second waits for the first
to commit or roll back. Then it **re-checks its `WHERE` clause against the newest version of that
row** before it writes. This is the one exception to "one snapshot for the whole statement": a row
the `UPDATE` finds already changed by a committed transaction is re-evaluated against its newest
version. The `EXISTS` and everything else in the statement still read the snapshot. With
`widget` at 1 and two purchases of 1:

| Step | Purchase A | Purchase B | Row |
| --- | --- | --- | --- |
| 1 | `UPDATE` finds the row (1 ≥ 1), locks it, and writes 0 | | 0 (uncommitted) |
| 2 | | `UPDATE` finds the row, waits for A's lock | |
| 3 | commits → `remaining_quantity = 0` → `Purchased` | | 0 |
| 4 | | re-checks `0 >= 1`: false, updates nothing | 0 |
| 5 | | `remaining_quantity = NULL`, `sku_exists = true` → `InsufficientStock` | 0 |

The check and the write happen together, on the latest committed value of the row. The
[same-SKU purchase race](../../test/inventory/InventoryConcurrencyTest.kt.md#the-same-sku-purchase-race)
tests this with many threads, and the `CHECK (quantity >= 0)` constraint is the backstop if the
SQL were ever wrong.

**Kotlin (raw string):** the SQL is a [raw string](#the-upsert). The deeper indentation on each
line is Robert's layout. Postgres ignores it.

### Binding the parameters

```kotlin
.param("skuId", skuId)
.param("requested_quantity", quantity)
```

**Spring:** `:skuId` appears **twice** in the SQL (in the `UPDATE` and in the `EXISTS`), and one
`.param("skuId", ...)` fills both. Named parameters are matched by name, not by position. With JDBC's
positional `?`, the same value would have to be bound twice. The names are chosen by the author,
and each one only has to match its `.param` call. This file uses both `camelCase` (`:skuId`) and
`snake_case` (`:requested_quantity`), which is legal. `requested_quantity` makes it clear which
quantity is meant, since the table also has a `quantity` column.

### Reading the row: `NULL` is not `0`

```kotlin
.query{rs, _ ->
        // Why: Used the javaObjectType so null stays null. JDBC getInt would turn null into 0, but 0 is a valid use case here. Meaning the last unit was just sold now the stock is 0. Null would be nothing was bought. We need to keep these distinct.
        val remainingQuantity: Int? = rs.getObject("remaining_quantity", Int::class.javaObjectType)
            val skuExists = rs.getBoolean("sku_exists")
```

**JDBC (why not `getInt`):** `rs.getInt(...)` returns a Java primitive `int`, which can't hold
null, so JDBC returns **`0` for SQL `NULL`**. Here that would be a bug. `0` means "you just bought
the last unit" (a `Purchased`), and `NULL` means "nothing was bought". With `getInt`, a refused
purchase would look like a successful one that left 0 in stock. The repository test
`removeStock can sell the last unit and keeps the row at zero` depends on keeping the two apart.
(Java's other workaround is to call `getInt` and then `rs.wasNull()`.)

**Kotlin (`Int::class.javaObjectType`):** JDBC's `getObject(column, Class<T>)` returns the value as
the requested Java class, or `null` for SQL `NULL`. The class has to be one that can hold `null`:

| Kotlin expression | Java class | Can it be `null`? |
| --- | --- | --- |
| `Int::class.java` | `int.class` (primitive) | no |
| `Int::class.javaObjectType` | `Integer.class` (boxed) | yes |

Kotlin has one `Int` type, and the compiler chooses between Java's `int` and `Integer` for you. When
a Java API asks for a `Class`, you have to choose: `.java` gives the primitive, `.javaObjectType`
gives the boxed class.

**Kotlin (declaring `Int?`):** `getObject` is a Java method without nullability annotations, so its
result is a [platform type](../../build.gradle.kts.md#compiler-options), `Int!`, which Kotlin lets
you treat as either `Int` or `Int?`. Writing `: Int?` on the `val` is the important choice. With
`: Int`, the code would compile, and Kotlin would insert a null check that throws a
`NullPointerException` on every refused purchase. The type annotation turns a runtime crash into
something the compiler tracks.

A correction, recorded honestly: while coaching, Claude first suggested `Integer::class.java`. In
Kotlin that refers to `java.lang.Integer` and gives the type `Integer!`, which the compiler
refused to assign to `Int?`. `Int::class.javaObjectType` is the idiomatic way to write it.

**JDBC (`getBoolean` is safe):** `EXISTS` is always `true` or `false`, never `NULL`, so the primitive
`getBoolean` can't lose information here.

**Kotlin (layout):** each line in the lambda is indented further than the one above it. That's only
layout: they're statements in sequence, not nested blocks. Only braces create nesting in Kotlin.

### Choosing the outcome: a `when` without a subject

```kotlin
            when {
                remainingQuantity != null -> PurchaseResult.Purchased(InventoryItem(skuId, remainingQuantity))
                skuExists -> PurchaseResult.InsufficientStock
                else -> PurchaseResult.SkuNotFound
            }
}
.single()
```

**Kotlin (`when` without a subject):** `when { cond -> value ... }` with nothing after `when` is
an `if` / `else if` chain. Each branch is a boolean condition, they're checked **top to bottom**,
and the first true one wins. Java equivalent:

```java
if (remainingQuantity != null) return new Purchased(new InventoryItem(skuId, remainingQuantity));
else if (skuExists) return InsufficientStock.INSTANCE;
else return SkuNotFound.INSTANCE;
```

`else` is required here because the `when` is used as a value: the compiler can't prove that
arbitrary boolean conditions cover every case. (A `when` used only as a statement may leave `else` out.)
Compare this with the service's `when`, which checks the *type* of a sealed value and needs no
`else` (see [exhaustive `when`](InventoryService.kt.md#purchaseinventory-one-branch-per-outcome)).

**Why the order matters:** a successful purchase *also* has `sku_exists = true`. If
`skuExists ->` came first, every successful purchase would be reported as `InsufficientStock`.
The branches go from most specific to least: "something was bought" → "the row exists, so there
wasn't enough" → "no row".

| `remaining_quantity` | `sku_exists` | Result |
| --- | --- | --- |
| `3` (or `0`) | `true` | `Purchased(InventoryItem(skuId, 3))` |
| `NULL` | `true` | `InsufficientStock` |
| `NULL` | `false` | `SkuNotFound` |

**Kotlin (smart cast):** `remainingQuantity` is `Int?`, but `InventoryItem`'s `quantity` is a
non-null `Int`. The branch compiles because once `remainingQuantity != null` is true, the compiler
treats `remainingQuantity` as `Int` inside that branch. That's a **smart cast**. It works because
`remainingQuantity` is a local variable that nothing can change between the check and the use, and
declaring it `val` guarantees that. (A property another thread could change wouldn't smart cast.)
Java has no equivalent; you'd use the value and trust that the check was right.

**Kotlin (the lambda's value):** the lambda has two statements (`val`s) and then the `when`. As
with every lambda, the [last expression is the return value](#mapping-each-row-the-lambda), so
the `when`'s result, a `PurchaseResult`, is what the row mapper produces. The `when` is used as an
expression, not a statement, so each branch provides a value.

**Why `skuId` and not a column:** `RETURNING quantity` doesn't return the SKU, so the item is
built from the function's `skuId` parameter. That's the same string, because the `WHERE` matched it
exactly.

**Spring (`.single()`):** this is `JdbcClient`'s `single()`, not Kotlin's `List.single()`. It runs
the query and returns the one mapped row, and it throws if there are zero rows or more than one. Here
that can't happen, because the outer `SELECT` has no `FROM` and always returns exactly one row. That
guarantee is what makes `.single()` the correct choice here, where `addStock` needed
`.list().singleOrNull()`.

## Check your understanding

- What does `@Repository` do that `@Import(InventoryRepository::class)` does in the test? Why does the running app need the annotation?
- Where does the `JdbcClient` passed to the constructor come from? Why is there no `@Autowired`?
- Why is `jdbcClient` a `private val` rather than a plain constructor parameter or a `var`?
- What is the difference between the KDoc above `findAll` and the `// Why:` comment? Who reads each one, and where?
- Why does `query(InventoryItem::class.java)` produce `List<InventoryItem?>`, while the lambda produces `List<InventoryItem>`?
- What would the API return if `ORDER BY sku_id` were removed? Would the test notice every time?
- Why is it `getString("sku_id")` and not `getString("skuId")`?
- The expression body has no `return`. What makes that allowed?
- If `sku_id` could be NULL in the database, what would happen at runtime, and where?
- The service calls `findBySkuId("x' OR '1'='1")`. What SQL reaches Postgres, and what comes back?
- If the primary key were dropped and two `widget` rows existed, what would `findBySkuId("widget")` return? What would the API answer?
- Why can the service call `findBySkuId(skuId).quantity` only after handling `null`? Which line of the service does that?
- `widget` has 5 units. Requests for +3 and +4 arrive at the same moment. What is the final quantity with `addStock`, and what could it be with find-then-insert?
- In `DO UPDATE`, what are `inventory.quantity` and `EXCLUDED.quantity` for widget at 5 receiving +3?
- `widget` is at `Int.MAX_VALUE - 1` and receives +2. What does the SQL return, and what does `addStock` return? What would happen with the guard written as `inventory.quantity + EXCLUDED.quantity <= :maxQuantity`?
- Why doesn't a brand-new SKU need the overflow guard?
- `removeStock` returns `PurchaseResult`, not `InventoryItem?`. Which two outcomes would a `null` lump together?
- `widget` has 5. If the outer query of the same statement ran `SELECT quantity FROM inventory WHERE sku_id = 'widget'` after `removeStock("widget", 2)`'s `UPDATE`, what would it return? Why?
- What do `remaining_quantity` and `sku_exists` contain for: `widget` at 5 buying 6, `widget` at 5 buying 5, and `missing` buying 1?
- What would a refused purchase return if the lambda used `rs.getInt("remaining_quantity")`?
- Swap the first two branches of the `when`. Which tests fail, and what does a successful purchase return?
- Why is `.single()` safe here but not in `findBySkuId`?
- Two purchases of 1 arrive at the same moment for `widget` at 1. Walk through what each `UPDATE` sees.
