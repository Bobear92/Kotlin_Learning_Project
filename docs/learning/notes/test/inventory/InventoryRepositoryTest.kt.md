# InventoryRepositoryTest.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryRepositoryTest.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryRepositoryTest.kt) · Last synced with code: 2026-09-29

## Role

Tests the repository and the schema against a real Postgres. It checks that SQL results map to
`InventoryItem`, that rows come back in order, that a single SKU can be looked up (or is `null`
when missing), that stock can be added (`addStock`) and removed (`removeStock`, whose three
outcomes are a `PurchaseResult`), and that the database's `CHECK` constraint really blocks
negative stock. That last test is the "Database backstop" item from the oversell test
plan in `.claude/TODO.md`. The `removeStock` tests are that plan's "Correctness in isolation"
item, written on 2026-09-29 before Robert's code.

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

### Looking up one SKU

```kotlin
/** One row comes back as an [InventoryItem] when the SKU exists. */
@Test
fun `findBySkuId returns the item for an existing SKU`() {
    insert("widget", 5)
    insert("gadget", 3)

    assertEquals(InventoryItem("widget", 5), inventoryRepository.findBySkuId("widget"))
}
```

**Why two rows:** with only `widget` in the table, a query that forgot its `WHERE` clause would
still return the right row. The extra `gadget` row makes sure the lookup really filters by SKU.

**Contract:** `GET /inventory/{skuId}` returns "the current quantity on hand for the specified SKU".

### A missing SKU is `null`

```kotlin
/** No row means null, not an exception: the service decides that a missing SKU is a 404. */
@Test
fun `findBySkuId returns null for a missing SKU`() {
    insert("widget", 5)

    assertNull(inventoryRepository.findBySkuId("missing"))
}
```

**Kotlin: `assertNull`.** Also from `kotlin.test`. It passes only if the value is `null`. The
test relies on `findBySkuId` being able to return `null`, which is why the plan gives it the
nullable return type `InventoryItem?` (explained in the repository's notes).

**Why `null` and not an exception here:** "no row" is a normal answer to a database question,
not an error. Turning it into "404 SKU not found" is a business decision, so it belongs in the
service. That keeps the repository reusable. Purchase also has to tell a missing SKU apart from
low stock, and `removeStock` reports which case happened (a [`PurchaseResult`](../../main/inventory/PurchaseResult.kt.md))
instead of deciding what it means.

### Zero stock is found

```kotlin
@Test
fun `findBySkuId returns a SKU with zero stock`() {
    insert("sold-out", 0)

    assertEquals(InventoryItem("sold-out", 0), inventoryRepository.findBySkuId("sold-out"))
}
```

**Why:** the same decision `findAll` pins. A sold-out SKU still exists, so it must not look like a
missing one. That difference matters to the API: `200` with `quantity: 0` versus `404`.

### Case-sensitive match

```kotlin
// Why: SKU ids are an exact, case-sensitive match for now (README "Column types" decision).
/** `WIDGET` and `widget` are different SKUs. */
@Test
fun `findBySkuId matches the SKU id case-sensitively`() {
    insert("widget", 5)

    assertNull(inventoryRepository.findBySkuId("WIDGET"))
}
```

**Why:** Postgres compares `TEXT` case-sensitively, so a plain `WHERE sku_id = ?` already
behaves this way. The test pins the current decision. If we later choose to normalize case (an
open question for `POST`), this is the test that has to change, which makes that change a
visible decision rather than an accident.

### Reading one quantity back

```kotlin
private fun quantityOf(skuId: String): Int? =
    jdbcClient.sql("SELECT quantity FROM inventory WHERE sku_id = ?")
        .param(skuId)
        .query { rs, _ -> rs.getInt("quantity") }
        .list()
        .singleOrNull()
```

**Why a separate query instead of `findBySkuId`:** the `addStock` tests check what's really in
the table with plain SQL, so a bug in the repository's read code can't hide a bug in its
write code. The `Int?` is `null` when there's no row, which lets a test assert "nothing was created".

### addStock: create, add, restock

```kotlin
/** Contract: "If the SKU does not yet exist in inventory, it is created with the given quantity." */
@Test
fun `addStock creates a new SKU with the given quantity`() {
    assertEquals(InventoryItem("widget", 5), inventoryRepository.addStock("widget", 5))
    assertEquals(5, quantityOf("widget"))
}

/** Contract: "If it already exists, the quantity is added to the current stock." */
@Test
fun `addStock adds to the quantity of an existing SKU`() {
    insert("widget", 5)

    assertEquals(InventoryItem("widget", 8), inventoryRepository.addStock("widget", 3))
    assertEquals(8, quantityOf("widget"))
}
```

**Why check both the returned value and the stored value:** `addStock` returns the row as it is
*after* the change (the contract's "updated inventory state"). The test checks that the return
value and the table agree.

**Contract:** the `post` description has two sentences, and these two tests check one each. The
zero-stock test after them (`addStock restocks a SKU with zero stock`) makes sure a sold-out
SKU counts as "already exists".

### A decoy row

```kotlin
// Why: a decoy row catches an UPDATE that forgot its WHERE / conflict target and hit every row.
/** Adding stock to one SKU leaves every other SKU alone. */
@Test
fun `addStock changes only the given SKU`() {
    insert("widget", 5)
    insert("gadget", 3)

    inventoryRepository.addStock("widget", 2)

    assertEquals(3, quantityOf("gadget"))
}
```

**Why:** with only one row in the table, "update this SKU" and "update every SKU" look the same.
The second row tells them apart. The TODO has the same idea as an optional fix for the
`findBySkuId` zero-stock test.

### The overflow boundary

```kotlin
/** The largest storable total is allowed: the overflow guard must not be off by one. */
@Test
fun `addStock can fill a SKU up to exactly Int MAX_VALUE`() {
    insert("widget", Int.MAX_VALUE - 1)

    assertEquals(InventoryItem("widget", Int.MAX_VALUE), inventoryRepository.addStock("widget", 1))
}

// Why: a naive `quantity + EXCLUDED.quantity <= max` check overflows itself, so Postgres throws
// instead of returning null; the second call catches that.
/** Overflow is refused atomically: null comes back and the stored quantity is unchanged. */
@Test
fun `addStock returns null and leaves stock unchanged when the total would overflow`() {
    insert("widget", Int.MAX_VALUE - 1)
    insert("full", Int.MAX_VALUE)

    assertNull(inventoryRepository.addStock("widget", 2))
    assertNull(inventoryRepository.addStock("full", Int.MAX_VALUE))
    assertEquals(Int.MAX_VALUE - 1, quantityOf("widget"))
    assertEquals(Int.MAX_VALUE, quantityOf("full"))
}
```

**Kotlin: `Int.MAX_VALUE`.** The largest `Int`, 2,147,483,647 (Java: `Integer.MAX_VALUE`). The
`quantity` column is a Postgres `INT`, which has the same range.

**Why two tests at the edge:** the first says the largest total is still allowed, and the second
says one more is refused. Together they pin the check to exactly the right place, so a `<`
where `<=` belongs fails one of them.

**Why the second `assertNull`:** Postgres checks every arithmetic step for overflow. In a guard
written as `inventory.quantity + EXCLUDED.quantity <= 2147483647`, the addition itself goes out
of range, so Postgres raises "integer out of range" instead of just answering "false". The test's
first call (2,147,483,646 + 2) already triggers that error. The second (`Int.MAX_VALUE` + `Int.MAX_VALUE`)
also catches a guard that does the sum in a wider type but compares it wrong. A guard that can't
overflow compares without adding: `inventory.quantity <= 2147483647 - EXCLUDED.quantity`.

**Why `null` and not an exception:** the same reasoning as `findBySkuId`. The repository reports
what happened ("no row was changed") and the service decides that it means a `400`.

### Case-sensitive add

```kotlin
// Why: SKU ids are an exact, case-sensitive match (README "Contract gaps" decision, 2026-09-28).
/** Adding to `WIDGET` creates a new SKU; it does not touch `widget`. */
@Test
fun `addStock treats SKU ids case-sensitively`() {
    insert("widget", 5)

    assertEquals(InventoryItem("WIDGET", 2), inventoryRepository.addStock("WIDGET", 2))
    assertEquals(5, quantityOf("widget"))
}
```

**Why:** the upsert's conflict check (`ON CONFLICT (sku_id)`) uses the primary key, which
compares `TEXT` exactly. So `WIDGET` doesn't conflict with `widget` and gets its own row.

### removeStock: purchased, down to the last unit

```kotlin
/** Contract: "Deducts the requested quantity"; the remaining stock comes back. */
@Test
fun `removeStock deducts the quantity and returns the remaining stock`() {
    insert("widget", 5)

    assertEquals(PurchaseResult.Purchased(InventoryItem("widget", 3)), inventoryRepository.removeStock("widget", 2))
    assertEquals(3, quantityOf("widget"))
}

// Why: the boundary of `quantity >= :quantity`; writing `>` instead would refuse the last unit.
/** Buying exactly what is on hand succeeds and leaves a sold-out row with quantity 0, not a deleted one. */
@Test
fun `removeStock can sell the last unit and keeps the row at zero`() {
    insert("widget", 5)

    assertEquals(PurchaseResult.Purchased(InventoryItem("widget", 0)), inventoryRepository.removeStock("widget", 5))
    assertEquals(0, quantityOf("widget"))
}
```

**Why a `PurchaseResult` and not `InventoryItem?`:** `addStock` has two outcomes (a row, or `null`
for overflow), so a nullable type is enough. A purchase has three: purchased, SKU not found, and
not enough stock. `null` can't say *which* failure happened, and the API needs a `404` for one and
a `400` for the other. `PurchaseResult` is a sealed type with one case per outcome (Robert's
[PurchaseResult.kt](../../main/inventory/PurchaseResult.kt.md) explains it).

**Kotlin: comparing results with `assertEquals`.** `Purchased` wraps an `InventoryItem` and is a
`data class`, so two `Purchased` values with equal items are equal. The test compares the whole
result in one assertion.

### removeStock: insufficient stock, never a partial fill

```kotlin
/** Contract: "only allowed if the current stock is sufficient to fulfill the full requested quantity": no partial fill. */
@Test
fun `removeStock refuses a purchase larger than the stock and changes nothing`() {
    insert("widget", 5)

    assertEquals(PurchaseResult.InsufficientStock, inventoryRepository.removeStock("widget", 6))
    assertEquals(5, quantityOf("widget"))
}

/** A sold-out SKU exists, so buying from it is insufficient stock (400), not a missing SKU (404). */
@Test
fun `removeStock reports insufficient stock for a sold-out SKU`() {
    insert("sold-out", 0)

    assertEquals(PurchaseResult.InsufficientStock, inventoryRepository.removeStock("sold-out", 1))
    assertEquals(0, quantityOf("sold-out"))
}
```

**Why the sold-out case matters:** a row with quantity `0` is still a SKU. A check like "no
remaining stock means not found" gets this wrong. Together with the last-unit test above, it also
separates two answers that are easy to mix up in the mapping code: "the purchase left 0" and
"nothing was bought".

### removeStock: a missing SKU, and exact matching

```kotlin
/** Contract: "Returns 404 if the SKU does not exist"; a purchase must never create the SKU. */
@Test
fun `removeStock reports a missing SKU and creates nothing`() {
    insert("widget", 5)

    assertEquals(PurchaseResult.SkuNotFound, inventoryRepository.removeStock("missing", 1))
    assertNull(quantityOf("missing"))
}

// Why: SKU ids are an exact, case-sensitive match (README "Contract gaps" decision, 2026-09-28).
/** Buying `WIDGET` does not touch `widget`; `WIDGET` does not exist. */
@Test
fun `removeStock treats SKU ids case-sensitively`() {
    insert("widget", 5)

    assertEquals(PurchaseResult.SkuNotFound, inventoryRepository.removeStock("WIDGET", 1))
    assertEquals(5, quantityOf("widget"))
}
```

**Why `widget` exists in the missing-SKU test:** with an empty table, a query that checked "does
*any* row exist" instead of "does *this* SKU exist" would still pass. The extra row makes that bug
visible.

**Kotlin: `data object` equality.** `SkuNotFound` and `InsufficientStock` carry no data, so each is
a single `data object`, and `assertEquals` compares against that one instance.

### removeStock: a decoy row

```kotlin
// Why: a decoy row catches an UPDATE that forgot `sku_id = :skuId` and hit every row.
/** Buying one SKU leaves every other SKU alone. */
@Test
fun `removeStock changes only the given SKU`() {
    insert("widget", 5)
    insert("gadget", 3)

    inventoryRepository.removeStock("widget", 2)

    assertEquals(3, quantityOf("gadget"))
}
```

The same idea as the [`addStock` decoy](#a-decoy-row): an `UPDATE` without its `WHERE` would take
2 from *every* SKU, and `gadget` would drop to 1.

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
- Why does `findBySkuId` return `null` for a missing SKU instead of throwing the exception that
  becomes the 404?
- Which `findBySkuId` test would catch a query that forgot its `WHERE` clause, and why does it
  need two rows?
- Why does the overflow test assert on the table after the `null`s, and not just on the `null`s?
- Which test fails if the overflow guard uses `<` where `<=` belongs? Which fails if it adds
  before comparing?
- What does the `gadget` row prove that a one-row test can't?
- Why can't `removeStock` return `InventoryItem?` the way `addStock` does?
- Which `removeStock` test fails if the SQL uses `quantity > :quantity`? Which fails if the code
  treats "remaining 0" as "nothing was bought"?
- Why is buying from a sold-out SKU a `400` and not a `404`?
