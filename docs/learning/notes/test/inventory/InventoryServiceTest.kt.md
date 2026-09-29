# InventoryServiceTest.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryServiceTest.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryServiceTest.kt) · Last synced with code: 2026-09-28

## Role

Tests the service's own preconditions, the rules that must hold even when nobody goes through the
controller. `@Valid` only protects HTTP requests; a Kotlin caller (a test, a job, the future
purchase code) calls `InventoryService` directly and skips it. These tests call the service
directly, the way such a caller would. Claude wrote them during review (2026-09-28), before Robert
added the service's `require`.

## Walkthrough

### Imports

```kotlin
import com.robertwoolbright.inventory.support.TestcontainersConfiguration
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
```

The same set as [InventoryRepositoryTest.kt](InventoryRepositoryTest.kt.md), plus JUnit's
parameterized-test annotations (see [InventoryControllerTest.kt](InventoryControllerTest.kt.md)).
There's no `@Test` import: every test here is a `@ParameterizedTest`.

### A JDBC slice with the service added

```kotlin
/**
 * [InventoryService] preconditions that hold even when the controller's `@Valid` check is bypassed
 * (a direct Kotlin caller). Runs against a real Postgres; each test is rolled back.
 */
@JdbcTest
@Import(TestcontainersConfiguration::class, InventoryRepository::class, InventoryService::class)
class InventoryServiceTest @Autowired constructor(
    private val inventoryService: InventoryService,
    private val jdbcClient: JdbcClient,
) {
```

**Spring:** the same `@JdbcTest` slice as the repository tests (explained in full in
[InventoryRepositoryTest.kt](InventoryRepositoryTest.kt.md#a-jdbc-test-slice)): real Postgres, no
web layer, no component scan, every test rolled back. Because component scanning is off,
`InventoryService` has to be listed in `@Import` too, next to the repository it depends on. Spring
then builds the service with the repository injected, exactly as in the running app.
`@Autowired constructor` is needed because JUnit, not Spring, creates the test class (see
[InventoryRepositoryTest.kt](InventoryRepositoryTest.kt.md)).

**Why a real database instead of a mock repository:** a mock could show that `addStock` was never
called, but only the real table shows that the stored stock is unchanged, whatever path the code
took. It also showed what really happened *before* the fix (the table below), which a mock would
have hidden.

### The helpers

```kotlin
private fun insert(skuId: String, quantity: Int) {
    jdbcClient.sql("INSERT INTO inventory (sku_id, quantity) VALUES (?, ?)")
        .params(skuId, quantity)
        .update()
}

private fun quantityOf(skuId: String): Int? =
    jdbcClient.sql("SELECT quantity FROM inventory WHERE sku_id = ?")
        .param(skuId)
        .query { rs, _ -> rs.getInt("quantity") }
        .list()
        .singleOrNull()
```

The same two helpers as in [InventoryRepositoryTest.kt](InventoryRepositoryTest.kt.md), explained
there, including the positional `?` placeholders filled by `.params(...)`. They set up and read rows
with plain SQL, so the test doesn't use the code under test to check itself.

### Rejecting a quantity below 1

```kotlin
// Why: a programming error, not a client error, so IllegalArgumentException (a 500), not a 400.
/** A quantity below 1 is refused before the database is touched, and an existing SKU is unchanged. */
@ParameterizedTest(name = "quantity {0}")
@ValueSource(ints = [0, -5])
fun `receiveInventory rejects a quantity below 1 for an existing SKU`(quantity: Int) {
    insert("widget", 5)

    assertFailsWith<IllegalArgumentException> { inventoryService.receiveInventory("widget", quantity) }
    assertEquals(5, quantityOf("widget"))
}
```

**Kotlin:** `assertFailsWith<T> { ... }` runs the lambda and passes only if it throws a `T`
(see [InventoryRepositoryTest.kt](InventoryRepositoryTest.kt.md)). `@ParameterizedTest` with
`@ValueSource` runs the method once per value (see
[InventoryControllerTest.kt](InventoryControllerTest.kt.md)).

**Why `IllegalArgumentException`:** that is what Kotlin's `require(...)` throws. Checks happen at
three layers, each for a different audience:

| Layer | Catches | Result |
| --- | --- | --- |
| `@Valid` + `@Min` (controller) | a bad client request | 400 with a message |
| `require` (service) | a bad call from our own code | 500: we have a bug |
| `CHECK (quantity >= 0)` (database) | anything that got past both | 500 |

So there is deliberately no handler mapping `IllegalArgumentException` to 400.

**Why both 0 and -5:** they failed in different ways before `require` existed. `0` *succeeded*:
it created a new SKU with zero stock, or "added" nothing to an existing one, because the database
`CHECK` allows 0. `-5` failed with a Postgres error (the overflow guard's `MAX - (-5)` for an
existing SKU, the `CHECK` for a new one), which Spring surfaced as
`DataIntegrityViolationException`, far from the real cause.

### A new SKU must not be created

```kotlin
/** A quantity below 1 for an unknown SKU must not create it. */
@ParameterizedTest(name = "quantity {0}")
@ValueSource(ints = [0, -5])
fun `receiveInventory rejects a quantity below 1 for a new SKU`(quantity: Int) {
    assertFailsWith<IllegalArgumentException> { inventoryService.receiveInventory("new-sku", quantity) }
    assertNull(quantityOf("new-sku"))
}
```

`assertNull` proves the row doesn't exist, which catches the phantom zero-stock SKU that `0`
used to create.

## Check your understanding

1. The controller already rejects `quantity: 0` with a 400. Why can the service still receive a 0?
2. Before `require` was added, what did `receiveInventory("new-sku", 0)` do, and why didn't the
   database stop it?
3. Why is a 500, not a 400, the right response if `require` ever fails in production?
4. Why does this test class need `InventoryService::class` in `@Import`, when a `@SpringBootTest`
   wouldn't?
