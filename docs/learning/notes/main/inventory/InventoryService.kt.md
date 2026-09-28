# InventoryService.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryService.kt](../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryService.kt) · Last synced with code: 2026-09-27

## Role

The service layer, written by Robert. It sits in the middle of the request flow: controller →
**service** → repository. The [controller](InventoryController.kt.md) calls it, and it calls the
[repository](InventoryRepository.kt.md). For `GET /inventory` there are no business rules, so it
passes the repository's list straight through. For `GET /inventory/{skuId}` it makes the first
business decision in the codebase: a SKU the repository can't find is an error,
[`SkuNotFoundException`](error/SkuNotFoundException.kt.md), which
[`InventoryExceptionHandler`](error/InventoryExceptionHandler.kt.md) turns into a `404`.

## Walkthrough

### Imports

```kotlin
import com.robertwoolbright.inventory.inventory.error.SkuNotFoundException
import org.springframework.stereotype.Service
```

**Kotlin:** `InventoryRepository` and `InventoryItem` are in the same package, so they need no
import (see the [repository notes](InventoryRepository.kt.md#imports)). `SkuNotFoundException`
lives in the `error` subpackage, and a subpackage counts as a *different* package, so it needs a
full import. Packages in Kotlin (and Java) aren't nested for visibility purposes: `inventory.error`
is no closer to `inventory` than any other package is.

### A Spring bean that depends on the repository

```kotlin
/** Service class for the various inventory flows. Used by controller layer and consumes the repository layer. */
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

**Why no error handling in `listInventory`:** Robert asked whether this function needs a `try`/`catch`. It doesn't:
- **No data** is not an error. The repository returns an empty list, which becomes `[]`, and the
  contract asks for exactly that.
- **Null** can't happen. The types say `List<InventoryItem>`, not `List<InventoryItem>?`, and
  the compiler enforces it.
- **A database failure** throws Spring's `DataAccessException`, which is unchecked. Kotlin has no
  checked exceptions at all, so there's no `throws` clause to write. The exception travels up
  through the controller, and Spring Boot turns it into a `500` response. Catching it here and
  returning an empty list would be worse: the client would be told "no stock" when the truth is
  "we couldn't look".

Errors the *service* decides on are different: a missing SKU in `getInventory` (below) is a rule
of the API, so the service throws on purpose. Purchase will add a second one, insufficient
stock (`400`).

**Why a service at all, when it only delegates:** the controller could call the repository
directly. The layer is kept because the next endpoints need it: purchase rules ("does the SKU
exist? is there enough stock?") belong here, not in the controller (HTTP) or the repository (SQL).
`getInventory` is the first of them: the service decides that a missing SKU is an error, and a
separate handler maps that error to a status code. Starting the layering now means `GET /inventory` won't
need to be rewired later.

### `getInventory`: turning "no row" into an error

```kotlin
/**
 * Returns an inventory item by SKU.
 * @throws SkuNotFoundException if no sku is found.
 */
fun getInventory(skuId : String) : InventoryItem = inventoryRepository.findBySkuId(skuId) ?: throw SkuNotFoundException(skuId)
```

**Kotlin (the Elvis operator `?:`):** `a ?: b` means "`a`, unless `a` is `null`, then `b`".
`findBySkuId` returns `InventoryItem?`. If it's an item, that item is the result. If it's `null`,
the right-hand side runs. (The name comes from the operator turned sideways, which looks like
Elvis's quiff.) It is **not a ternary**, though it looks like half of one (Robert called it that in the
2026-09-28 walkthrough). Kotlin has no ternary at all: `if` is an expression, so Java's
`cond ? a : b` is written `if (cond) a else b`. Elvis only ever tests for `null`. The Java equivalent is:

```java
InventoryItem item = inventoryRepository.findBySkuId(skuId);
if (item == null) throw new SkuNotFoundException(skuId);
return item;
```

**Kotlin (`throw` is an expression):** in Java, `throw` is a statement, so it can't appear where a
value is expected. In Kotlin, `throw` is an expression of type **`Nothing`**, a type with no values,
meaning "this never finishes normally". `Nothing` fits anywhere any type is expected, so
`item ?: throw ...` has type `InventoryItem`, not `InventoryItem?`. That's why the declared
return type can be non-null. This function never returns `null`: it returns an item, or it throws.
(Robert asked about exactly this on 2026-09-27: the `null` never escapes, it becomes an exception
inside.)

The same pattern works with any fallback: `?: 0`, `?: "unknown"`, `?: return`. `Throwable.message`
in [the exception handler](error/InventoryExceptionHandler.kt.md) uses the value form.

**Kotlin (KDoc `@throws`):** a block tag, so it must start its own line inside the `/** */`.
Kotlin has no checked exceptions and no `throws` clause (see
[`listInventory`](#listinventory)), so the KDoc tag is the only place the signature
tells a caller this can throw. (Robert first wrote `@throw` mid-sentence; the tag is `@throws`, and
inline it isn't recognized.)

**Why throw here, and not in the repository or the controller:**
- The **repository** answers "is there a row?" and returns `null` for none. Whether that is an
  error depends on the caller, so it doesn't throw.
- The **service** knows the rule: for this operation a missing SKU is a failure. It says so with
  an exception named after the domain problem, not after HTTP.
- The **controller** stays a one-liner. It never sees the `null` or the exception. The exception
  passes through it to Spring, and [`InventoryExceptionHandler`](error/InventoryExceptionHandler.kt.md)
  maps it to `404`. So the service knows nothing about status codes.

**What if the `null` were passed up instead?** (walkthrough, 2026-09-28) Declaring
`getInventory(...): InventoryItem? = inventoryRepository.findBySkuId(skuId)` compiles: Kotlin only
objects when a nullable value is *used* as non-null (`item.quantity`, or returning it as
`InventoryItem`). Passing a "maybe" out as a "maybe" claims nothing false. If the controller then
returned `InventoryItem?` too, Spring would get `null` and write no body: `200 OK`, empty, for a SKU
that doesn't exist, with no NPE and no error. The controller test's `isNotFound()` check would fail.
The non-null return type is what makes the compiler demand the Elvis, and it keeps `null` from
reaching Spring, which is outside the compiler's reach.

**Kotlin:** the spaces in `skuId : String` and `) : InventoryItem` are legal and change nothing.

## Check your understanding

- Why is there no `try`/`catch` in `listInventory`? What does the client see if Postgres is down?
- In what order does Spring create the repository, service and controller beans, and why that order?
- What would happen at startup if `@Service` were removed?
- What is the type of `inventoryRepository.findBySkuId(skuId) ?: throw SkuNotFoundException(skuId)`, and why isn't it `InventoryItem?`?
- If `getInventory` returned `InventoryItem?` and passed the `null` up, what would the controller have to do differently, and which test would catch it if it did nothing?
