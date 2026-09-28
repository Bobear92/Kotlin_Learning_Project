# InventoryServiceTest.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryServiceTest.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryServiceTest.kt) · Last synced with code: 2026-09-28

## Role

Tests the service's own preconditions, the rules that must hold even when nobody goes through the
controller. `@Valid` only protects HTTP requests; a Kotlin caller (a test, a job, the future
purchase code) calls `InventoryService` directly and skips it. These tests call the service
directly, the way such a caller would.

## Walkthrough

### A JDBC slice with the service added

```kotlin
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

**Why a real database instead of a mock repository:** the tests check that *nothing was written*
(`quantityOf` after the call). A mock can't prove that; a real table can.

### The helpers

```kotlin
private fun insert(skuId: String, quantity: Int) { ... }
private fun quantityOf(skuId: String): Int? = ...
```

The same two helpers as in [InventoryRepositoryTest.kt](InventoryRepositoryTest.kt.md): they set
up and read rows with plain SQL, so the test doesn't use the code under test to check itself.

### Rejecting a quantity below 1

```kotlin
// Why: a programming error, not a client error, so IllegalArgumentException (a 500), not a 400.
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
|---|---|---|
| `@Valid` + `@Min` (controller) | a bad client request | 400 with a message |
| `require` (service) | a bad call from our own code | 500: we have a bug |
| `CHECK (quantity >= 0)` (database) | anything that got past both | 500 |

So there is deliberately no handler mapping `IllegalArgumentException` to 400.

**Why both 0 and -5:** they failed in different ways before `require` existed. `0` *succeeded*:
it created a new SKU with zero stock, or "added" nothing to an existing one, because the database
`CHECK` allows 0. `-5` failed with a Postgres `DataIntegrityViolationException`, far from the
real cause.

### A new SKU must not be created

```kotlin
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
