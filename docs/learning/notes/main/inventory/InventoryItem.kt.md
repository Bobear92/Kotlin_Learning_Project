# InventoryItem.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryItem.kt](../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryItem.kt) · Last synced with code: 2026-09-25

## Role

The model: one SKU and how many units of it are in stock. Robert wrote it. It passes through every
layer of `GET /inventory`. The [repository](InventoryRepository.kt.md) builds one per database row,
the [service](InventoryService.kt.md) passes the list through, and the
[controller](InventoryController.kt.md) returns it, where Jackson turns each item into a JSON object.
It is also the shape of the contract's `InventoryItem` schema.

## Walkthrough

### The KDoc and the class

```kotlin
/** One SKU and its stock quantity; returned by the repository and serialized as JSON by GET /inventory. */
data class InventoryItem(val skuId: String, val quantity: Int)
```

**Kotlin (`data class`):** a `data class` is a class whose job is to hold values. From the
properties in its primary constructor, the compiler generates:

| Generated | What it does | Java equivalent |
| --- | --- | --- |
| `equals()` / `hashCode()` | Two items are equal when all their properties are equal | Hand-written, or a Java `record` |
| `toString()` | `InventoryItem(skuId=widget, quantity=5)` | Hand-written, or a `record` |
| `copy(...)` | A new item with some properties changed: `item.copy(quantity = 0)` | No built-in equivalent |
| `component1()`, `component2()` | Destructuring: `val (sku, qty) = item` | Record patterns (Java 21) |

The closest Java equivalent is a `record InventoryItem(String skuId, int quantity)`. Only properties
declared in the primary constructor count toward `equals` and the rest. Value equality is what makes the
repository test work: `assertEquals(listOf(InventoryItem("sold-out", 0)), ...)` compares a list
built in the test with a list built from database rows (see
[InventoryRepositoryTest.kt](../../test/inventory/InventoryRepositoryTest.kt.md)).

**Kotlin (`val` in the constructor):** each `val` declares a read-only property and sets it from the
constructor argument, the same way as the
[primary constructor properties](InventoryRepository.kt.md#a-spring-bean-with-constructor-injection)
in the repository. These two are public ([no modifier means public](../../test/inventory/InventoryRepositoryTest.kt.md)), because callers and Jackson need to read them.
Robert's first version used `var`. It became `val` because an `InventoryItem` is a **snapshot**
of one row at the moment it was read. Changing `quantity` on the object would change nothing
in the database, and would make the snapshot lie. Stock changes go through a single atomic SQL
statement (see the README decision record), never through a changed object.

**Kotlin (types):** `String` and `Int` have no `?`, so neither can be null. `Int` compiles to
Java's primitive `int`. It becomes the boxed `Integer` when it has to be an object: for example
when it's nullable (`Int?`), a generic type argument (`List<Int>`), or passed as `Any`.

**Kotlin (no body):** the class has no `{ }`, because a data class needs nothing beyond its
constructor. See [class with no body](../InventoryApplication.kt.md#springbootapplication-and-a-class-with-no-body).

**Spring (Jackson):** when the controller returns this class, Jackson writes one JSON object per
item, using the property names as keys: `{"skuId": "widget", "quantity": 5}`. So the Kotlin
property names **are** the JSON field names. Renaming `skuId` to `sku` would break the contract.
Writing this class as JSON only needs its generated getters (`getSkuId()`, `getQuantity()`).
Reading JSON *into* it, which the upcoming `POST` bodies will need, is where `jackson-module-kotlin`
comes in: the class has no no-arg constructor or setters, so the module calls the primary constructor
(see [build.gradle.kts](../../build.gradle.kts.md#app-dependencies)).

**Contract:** matches `components.schemas.InventoryItem` in `inventory.yaml`: `skuId` (string) and
`quantity` (integer, `minimum: 0`). The Kotlin type doesn't enforce `minimum: 0`; the database does,
with `CHECK (quantity >= 0)` in [V1__create_inventory.sql](../resources/db/migration/V1__create_inventory.sql.md).

**KDoc placement:** the KDoc sits directly on top of `data class`. An earlier version had a blank
line between them, and the project's [check_kdoc.py](../../../../../scripts/check_kdoc.py) treats a
blank line as breaking the KDoc's attachment, so it reported the class as missing KDoc. Robert
removed the blank line on 2026-09-25.

## Check your understanding

- What would `InventoryItem("widget", 5) == InventoryItem("widget", 5)` return? What would it return if `data` were removed?
- Why `val` and not `var`, given that stock quantities change all the time?
- If `skuId` were renamed to `sku`, which test would fail first, and why?
