# InventoryService.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryService.kt](../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryService.kt) · Last synced with code: 2026-09-25

## Role

The service layer, written by Robert. It sits in the middle of the request flow: controller →
**service** → repository. The [controller](InventoryController.kt.md) calls it, and it calls the
[repository](InventoryRepository.kt.md). For `GET /inventory` there are no business rules, so it
passes the repository's list straight through.

## Walkthrough

### Imports

```kotlin
import org.springframework.stereotype.Service
```

**Kotlin:** `InventoryRepository` and `InventoryItem` are in the same package, so they need no
import (see the [repository notes](InventoryRepository.kt.md#imports)).

### A Spring bean that depends on the repository

```kotlin
/** Service class for the GET /inventory flow. Used by controller layer and consumes the repository layer. */
@Service
class InventoryService(
    private val inventoryRepository: InventoryRepository
    ){
```

**Kotlin:** a primary constructor with a `private val` property, the same pattern as the
repository's `JdbcClient` (explained in full in
[InventoryRepository.kt](InventoryRepository.kt.md#a-spring-bean-with-constructor-injection)).
There's no trailing comma after the parameter here. It's optional, so both forms compile.

**Spring:** `@Service` is a stereotype annotation. Component scanning and constructor injection
work as in the [repository](InventoryRepository.kt.md#a-spring-bean-with-constructor-injection).
Two things are new here:
- **Creation order follows the dependencies.** The repository bean has to exist before the service
  can be built, and the service before the controller.
- **`@Service` behaves exactly like `@Component`.** It only names the class's role, the same way
  `@Repository` names data access.

**Kotlin + Spring:** Kotlin classes are final by default, but the `plugin.spring` compiler plugin
opens classes annotated with `@Service` (and the other stereotypes) so Spring can proxy them later,
for example for `@Transactional` (see [final by default](../../build.gradle.kts.md#plugins)).

### `listInventory`

```kotlin
/** Returns every inventory item, sorted by SKU id. */
fun listInventory() : List<InventoryItem> = inventoryRepository.findAll()
```

**Kotlin:** an [expression body](../../test/support/TestcontainersConfiguration.kt.md#the-container-bean):
the function's result is the value of `inventoryRepository.findAll()`. The declared return type
`List<InventoryItem>` is read-only and non-null, the same type the repository returns (see
[read-only `List`](InventoryRepository.kt.md#findall-the-function-signature)). The space before
the `:` is unusual but legal. Kotlin ignores it.

**KDoc:** it leads with what the function does. The sorting isn't done here, it happens in the
repository's `ORDER BY sku_id`. The service states the order anyway, because it's part of what this
function returns: the README decision fixes `GET /inventory` to SKU id order (the contract itself sets
none), and `InventoryControllerTest` checks that order strictly.

**Why no error handling:** Robert asked whether this function needs a `try`/`catch`. It doesn't:
- **No data** is not an error. The repository returns an empty list, which becomes `[]`, and the
  contract asks for exactly that.
- **Null** can't happen. The types say `List<InventoryItem>`, not `List<InventoryItem>?`, and
  the compiler enforces it.
- **A database failure** throws Spring's `DataAccessException`, which is unchecked. Kotlin has no
  checked exceptions at all, so there's no `throws` clause to write. The exception travels up
  through the controller, and Spring Boot turns it into a `500` response. Catching it here and
  returning an empty list would be worse: the client would be told "no stock" when the truth is
  "we couldn't look".

Service-layer errors arrive with the next endpoints: `404` for a missing SKU (get-by-SKU and
purchase), `400` for insufficient stock.

**Why a service at all, when it only delegates:** the controller could call the repository
directly. The layer is kept because the next endpoints need it: purchase rules ("does the SKU
exist? is there enough stock?") and the mapping of failures to `404`/`400` belong here, not in the
controller (HTTP) or the repository (SQL). Starting the layering now means `GET /inventory` won't
need to be rewired later.

## Check your understanding

- Why is there no `try`/`catch` in `listInventory`? What does the client see if Postgres is down?
- In what order does Spring create the repository, service and controller beans, and why that order?
- What would happen at startup if `@Service` were removed?
