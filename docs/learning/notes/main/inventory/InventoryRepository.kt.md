# InventoryRepository.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryRepository.kt](../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryRepository.kt) · Last synced with code: 2026-09-24

## Role

The data-access layer for inventory, written by Robert. It is the only class that talks to the
`inventory` table (created by [V1__create_inventory.sql](../resources/db/migration/V1__create_inventory.sql.md)).
Request flow for `GET /inventory`: controller → service → **repository** → Postgres. Each row
comes back as an `InventoryItem`. [InventoryRepositoryTest](../../test/inventory/InventoryRepositoryTest.kt.md)
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
  `TEXT`, so the order is alphabetical, not numeric. The test inserts SKUs out of order to prove it.
- Column names are the **database** names (`sku_id`, snake_case), not the Kotlin property
  names (`skuId`).

**Spring:** `jdbcClient.sql(...)` only *prepares* a statement. Nothing runs until a terminal
call such as `.list()`.

### Mapping each row: the lambda

```kotlin
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
  two arguments (the result set and the row number), so the lambda must accept two.
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
for these rows, but the compiler couldn't prove it. It's also more explicit, since you can see
which column goes into which property. That fits the reason `JdbcClient` was chosen (explicit SQL).

`(Mutable)List` is Kotlin saying it can't tell whether a Java `List` is meant to be changed. It's
harmless, because it fits a read-only `List` return type.

**Null safety, one level deeper:** `rs.getString(...)` is a Java method, so its result is a
platform type (`String!`). If it were ever null, Kotlin would throw when passing it into
`InventoryItem`'s non-null `skuId`. That can't happen: `sku_id` is the primary key, and a primary
key is never NULL. The migration's constraints back up the Kotlin types.

**Spring:** `.list()` runs the query, calls the lambda once per row, and collects the results. No
rows means an empty list, never null.

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
