# InventoryRepository.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryRepository.kt](../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryRepository.kt) · Last synced with code: 2026-09-27

## Role

The data-access layer for inventory, written by Robert. It is the only class that talks to the
`inventory` table (created by [V1__create_inventory.sql](../resources/db/migration/V1__create_inventory.sql.md)).
Request flow for both reads: controller → service → **repository** → Postgres. `findAll` serves
`GET /inventory` and `findBySkuId` serves `GET /inventory/{skuId}`. Each row comes back as an
`InventoryItem`. The repository only reports what the table holds. It never decides what a missing
row *means* for the API; that's the [service's](InventoryService.kt.md) job. [InventoryRepositoryTest](../../test/inventory/InventoryRepositoryTest.kt.md)
checks it against a real Postgres.

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
/** Reads inventory rows from Postgres, used by the service layer. */
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
endpoint, nothing there means `404`. The purchase endpoint will make the same lookup and give its own
answer. The repository test `findBySkuId returns null for a missing SKU` pins this down.

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

**Why not `JdbcClient`'s own `.optional()`:** the query spec also has `.optional()`, which returns a
Java `Optional<InventoryItem>`. Kotlin code would then convert it with `.orElse(null)` or
`.getOrNull()`. `.list().singleOrNull()` gets `InventoryItem?` directly, and a nullable type is the
Kotlin way to say "maybe absent". `.single()` was ruled out because it throws on no rows, and no rows
is a normal answer here.

**Kotlin (why the return type works):** `.list()` returns `List<InventoryItem>` (non-null items,
thanks to the lambda), and `singleOrNull()` on a `List<T>` returns `T?`. That gives
`InventoryItem?`, which matches the declared return type.

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
