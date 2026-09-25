# InventoryRepositoryTest.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryRepositoryTest.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryRepositoryTest.kt) · Last synced with code: 2026-09-25

## Role

Tests the repository and the schema against a real Postgres. It checks that SQL results map to
`InventoryItem`, that rows come back in order, and that the database's `CHECK` constraint really
blocks negative stock. That last test is the "Database backstop" item from the oversell test
plan in `.claude/TODO.md`.

## Walkthrough

### A JDBC test slice

```kotlin
@JdbcTest
@Import(TestcontainersConfiguration::class, InventoryRepository::class)
class InventoryRepositoryTest @Autowired constructor(
    private val inventoryRepository: InventoryRepository,
    private val jdbcClient: JdbcClient,
) {
```

**Spring: `@JdbcTest`, a test *slice*.** `@SpringBootTest` starts the whole application.
`@JdbcTest` starts only the database part: the `DataSource`, `JdbcTemplate`/`JdbcClient`,
transactions, and Flyway, which creates the table. There are no web beans, and **component
scanning is off**, so our own `@Repository` isn't created automatically. That's why
`InventoryRepository::class` is listed in `@Import`. The smaller context starts faster, and a
failure points straight at the data layer.

**Spring: every test is rolled back.** `@JdbcTest` is `@Transactional`: each test method runs
inside a transaction that Spring rolls back when the method ends. Rows a test inserts disappear
afterward, so tests can't affect each other and no cleanup code is needed.

**Spring: which database?** `@JdbcTest` can replace the application's `DataSource` with an
embedded in-memory database. By default (Spring Boot 3.4+) it doesn't replace one that was set
up for tests. Ours comes from `@ServiceConnection` in `TestcontainersConfiguration`, so the tests
run against real Postgres. Different test annotations mean a different cached context, so this
class gets its own container (see
[TestcontainersConfiguration.kt](../support/TestcontainersConfiguration.kt.md)).

**Kotlin: `@Autowired constructor(...)`.** Annotating the primary constructor requires writing
the `constructor` keyword. Normally it's left out. In the main code, Spring creates beans and
calls their single constructor automatically. Test classes are created by **JUnit**, and
by default Spring's JUnit extension only fills constructor parameters when the constructor is
marked `@Autowired`. (`@TestConstructor(autowireMode = ALL)`, or the property
`spring.test.constructor.autowire.mode=all`, would make it fill them without the annotation.)
The `val` parameters make the injected objects read-only properties, the same as in
[InventoryRepository.kt](../../main/inventory/InventoryRepository.kt.md). The trailing comma is
the same as in [InventoryItem.kt](../../main/inventory/InventoryItem.kt.md).

**Kotlin: `@Import(A::class, B::class)`.** `@Import`'s parameter is an array, and Kotlin
passes several values as varargs.

### A test helper

```kotlin
private fun insert(skuId: String, quantity: Int) {
    jdbcClient.sql("INSERT INTO inventory (sku_id, quantity) VALUES (?, ?)")
        .params(skuId, quantity)
        .update()
}
```

**Why insert with SQL instead of going through the app:** there's no "add stock" endpoint yet.
Even once there is, a test that controls exactly which rows exist is clearer, and it doesn't
fail because of a bug in some other feature. This is the test *seeding* we talked about.

**Spring:** `?` are positional parameters. `.params(...)` binds them in order. The values are
sent to Postgres separately from the SQL text, so there's no SQL injection risk. `.update()` runs
the statement and returns the number of rows affected (ignored here).

**Kotlin: public by default.** A Kotlin declaration with no modifier is `public`. Java's default
is package-private, and Kotlin has none (see the README's "Project layout"). So a helper that should stay
inside the class must say `private`. That's also why the project's KDoc rule exempts it: KDoc is
required on non-private declarations. JUnit ignores the function because it has no `@Test`.

### Empty table

```kotlin
/** No rows means an empty list, never null. */
@Test
fun `findAll returns an empty list when there are no SKUs`() {
    assertTrue(inventoryRepository.findAll().isEmpty())
}
```

**Kotlin: backtick names.** A function name in backticks can contain spaces, so a test reads
as a sentence, and JUnit reports it that way. This is only for tests. The JVM still forbids some
characters in names, such as `.`, `:`, `/` and `<`.

**Kotlin: `kotlin.test` assertions.** `assertTrue`, `assertEquals` and `assertFailsWith` come
from `kotlin-test` (the `kotlin-test-junit5` dependency), a thin Kotlin layer over JUnit's
assertions.

**Contract:** "If no SKUs exist, returns an empty array."

### Mapping and order

```kotlin
@Test
fun `findAll returns every SKU ordered by SKU id`() {
    insert("widget", 5)
    insert("CW-XYCS-BM-01", 10)
    insert("gadget", 3)

    assertEquals(
        listOf(
            InventoryItem("CW-XYCS-BM-01", 10),
            InventoryItem("gadget", 3),
            InventoryItem("widget", 5),
        ),
        inventoryRepository.findAll(),
    )
}
```

**Why the rows are inserted out of order:** if they were inserted in the expected order, the
test could pass even without `ORDER BY`, because Postgres often returns rows in insertion order.

**Kotlin: `listOf(...)`** creates a read-only `List`. `assertEquals(expected, actual)` (expected
first) compares the lists element by element with `equals`. That only works because
`InventoryItem` is a `data class`. With a plain class, two items with the same values would not
be equal (see [InventoryItem.kt](../../main/inventory/InventoryItem.kt.md)).

### Zero stock is listed

```kotlin
@Test
fun `findAll includes SKUs with zero stock`() {
    insert("sold-out", 0)

    assertEquals(listOf(InventoryItem("sold-out", 0)), inventoryRepository.findAll())
}
```

**Why:** it pins the 2026-09-24 decision. A sold-out SKU still exists and is listed. If
someone later adds `WHERE quantity > 0`, this test fails.

**Predicting which tests a change breaks:** first ask *which code does each test actually call?*
With `WHERE quantity > 0` added to `findAll`, only this test fails. The empty-table test still gets
`[]` (a filter can't turn nothing into something), the ordering test has no zero rows, and the
backstop test never calls `findAll` at all: it runs its own `UPDATE`, and a `WHERE` in a `SELECT`
only filters what is read, never what is written.

### The database backstop

```kotlin
@Test
fun `database rejects an update that would make quantity negative`() {
    insert("widget", 1)

    assertFailsWith<DataIntegrityViolationException> {
        jdbcClient.sql("UPDATE inventory SET quantity = quantity - 2 WHERE sku_id = ?")
            .param("widget")
            .update()
    }
}
```

**Why this bypasses the repository:** the point is to show what happens if application code
is wrong. The `UPDATE` deliberately has no `quantity >= ?` guard, so the only thing stopping it
is the `CHECK (quantity >= 0)` constraint from
[V1__create_inventory.sql](../../main/resources/db/migration/V1__create_inventory.sql.md).

**Kotlin: `assertFailsWith<T> { ... }`.** It runs the lambda and passes only if the lambda throws
a `T`. It returns the exception if you want to inspect it. `T` is a reified type parameter (see
[InventoryApplication.kt](../../main/InventoryApplication.kt.md)), which is how the function can
check the exception type at runtime. JUnit's version is `assertThrows(X.class, () -> ...)`.

**Spring:** Postgres answers with SQLSTATE `23514` (check violation). Spring's exception
translation maps it to `DataIntegrityViolationException`, a database-independent exception, so
the test doesn't depend on Postgres error codes.

The failed statement aborts the test's transaction in Postgres, and the rollback at the end of
the test discards everything anyway.

## Check your understanding

- Why does `InventoryRepository::class` have to be in `@Import` here, but not in
  `InventoryControllerTest`?
- If `@JdbcTest` didn't roll back, which test here would start failing, and when?
- What would the ordering test prove if the rows were inserted already sorted?
