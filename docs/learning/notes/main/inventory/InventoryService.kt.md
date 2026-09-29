# InventoryService.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryService.kt](../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryService.kt) · Last synced with code: 2026-09-29

## Role

The service layer, written by Robert. It sits in the middle of the request flow: controller →
**service** → repository. The [controller](InventoryController.kt.md) calls it, and it calls the
[repository](InventoryRepository.kt.md). For `GET /inventory` there are no business rules, so it
passes the repository's list straight through. For `GET /inventory/{skuId}` it makes the first
business decision in the codebase: a SKU the repository can't find is an error,
[`SkuNotFoundException`](error/SkuNotFoundException.kt.md), which
[`InventoryExceptionHandler`](error/InventoryExceptionHandler.kt.md) turns into a `404`. For
`POST /inventory/{skuId}` it enforces the rule "at least 1 unit", and turns a refused add into
[`AddStockOverflowException`](error/AddStockOverflowException.kt.md) (a `400`). For
`POST /inventory/{skuId}/purchase` it applies the same rule, and translates each of the repository's
three [`PurchaseResult`](PurchaseResult.kt.md)s: return the item, or throw `SkuNotFoundException`
(`404`) or [`InsufficientInventoryException`](error/InsufficientInventoryException.kt.md) (`400`).
[InventoryServiceTest](../../test/inventory/InventoryServiceTest.kt.md) calls it directly, the way
a non-HTTP caller would.

## Walkthrough

### Imports

```kotlin
import com.robertwoolbright.inventory.inventory.error.AddStockOverflowException
import com.robertwoolbright.inventory.inventory.error.SkuNotFoundException
import com.robertwoolbright.inventory.inventory.error.InsufficientInventoryException
import org.springframework.stereotype.Service
```

**Kotlin:** `InventoryRepository`, `InventoryItem` and `PurchaseResult` are in the same package, so they need no
import (see the [repository notes](InventoryRepository.kt.md#imports)). The three exceptions
live in the `error` subpackage, and a subpackage counts as a *different* package, so each needs a
full import. (The imports aren't in alphabetical order. Kotlin doesn't care, and the IDE's
"Optimize Imports" would sort them.) Packages in Kotlin (and Java) aren't nested for visibility purposes: `inventory.error`
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
of the API, so the service throws on purpose. `purchaseInventory` adds a second one, insufficient
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

### `receiveInventory`: a precondition and a block body

```kotlin
/**
 * Adds quantity to the SKU's stock, creating the SKU if it's new, and returns the new total.
 * @throws AddStockOverflowException if the add would push the stock past Int.MAX_VALUE.
 * @throws IllegalArgumentException if quantity is less than 1 (a caller bug, so it surfaces as a 500).
 */
fun receiveInventory(skuId : String, quantity: Int) : InventoryItem {
    // Why: @Valid only protects HTTP requests; this also stops direct callers (0 would create an empty SKU).
    require(quantity >= 1) {"quantity must be at least 1, was $quantity"}
    return inventoryRepository.addStock(skuId, quantity) ?: throw AddStockOverflowException()
}
```

**Kotlin (block body):** unlike the two functions above, this one has `{ }` and an explicit
`return`. An [expression body](../../test/support/TestcontainersConfiguration.kt.md#the-container-bean)
(`= ...`) holds one expression, and there are two steps here: check, then add. Two steps are
clearer as a block body (squeezing them into one expression with `run { }` would compile, but reads
worse). Robert first
wrote it as an expression body; adding `require` meant converting it: `=` became `{`, the `require`
went first, and the old expression got `return` in front of it. Forgetting that `return` is a
compile error, because a block body with a return type must return on every path.

**Watch the opposite rule in lambdas** (walkthrough, 2026-09-29): a function's `{ }` block needs
`return`, but a lambda's `{ }` returns its last expression automatically. The repository's row mapper,
`.query { rs, _ -> InventoryItem(...) }`, has no `return`, and adding one there would try to return
from the enclosing function instead.

**Kotlin (`require`):** a standard-library function. `require(condition) { message }` does nothing
if the condition is true, and throws `IllegalArgumentException` with the message if it's false. The
message is a [lambda](InventoryRepository.kt.md#mapping-each-row-the-lambda), so the string is only
built when the check fails. `$quantity` inside it is a
[string template](error/SkuNotFoundException.kt.md#the-whole-class-in-one-line). Java equivalent:

```java
if (!(quantity >= 1)) {
    throw new IllegalArgumentException("quantity must be at least 1, was " + quantity);
}
```

Its siblings: `check(condition)` throws `IllegalStateException` (for "this object is in the wrong
state", not "you passed a bad argument"), and `requireNotNull(x)` returns `x` as non-null or throws.

**Why a second check, when `@Valid` already rejects 0:** `@Valid` + `@Min` only run on an HTTP
request body. The service is a public function that any Kotlin code can call directly: a test, a
scheduled job, the purchase code. Before this line, a direct call behaved like this
(`InventoryServiceTest` showed it on 2026-09-28):

| Call | Without `require` | With `require` |
| --- | --- | --- |
| `receiveInventory("new-sku", 0)` | **succeeds**, creating an empty SKU (the `CHECK` allows 0) | `IllegalArgumentException` |
| `receiveInventory("widget", 0)` | succeeds, adds nothing | `IllegalArgumentException` |
| `receiveInventory("widget", -5)` | Postgres `integer out of range` (`MAX - (-5)` overflows), surfaced by Spring as `DataIntegrityViolationException` | `IllegalArgumentException` |

Each check has a different audience:

| Check | Catches | Client sees |
| --- | --- | --- |
| `@Min` + `@Valid` (controller) | a bad **client** request | `400 quantity must be at least 1` |
| `require` (service) | a bad call from **our own code** | `500`: we have a bug |
| `CHECK (quantity >= 0)` (database) | anything that got past both | `500` |

That's why no handler maps `IllegalArgumentException` to `400`: if this ever fires, the mistake is
ours, not the client's. In normal operation it never fires, because the controller rejects bad
input first.

**Why in the service, not the repository:** "you can't receive fewer than 1 unit" is a business
rule, and the service is where rules live. The repository's KDoc states the same thing as a
precondition, because its SQL guard depends on it. [`purchaseInventory`](#purchaseinventory-one-branch-per-outcome)
has the same check.

**Why the message includes the value:** it only ever appears in a log or a stack trace when
something has gone wrong. `was -5` tells whoever reads it what the caller actually passed.

**Kotlin (Elvis + `throw` again):** the `return` line is the same pattern as
[`getInventory`](#getinventory-turning-no-row-into-an-error): `addStock` returns `InventoryItem?`,
where `null` means "refused by the overflow guard", and `?: throw` turns it into a domain exception.

**KDoc (`@throws` for each exception):** one tag per exception a caller can see, each saying *when*.
Robert first put the `// Why:` reason inside the KDoc, after the first `@throws`. A tag's text runs
until the next tag, so it read as part of the `AddStockOverflowException` description. KDoc is for
callers (what it does, what it throws); the reason for the check is for maintainers, so it moved to
the `// Why:` line.

**Why `String` + `Int`, not `InventoryQuantity`:** the request body class is an HTTP detail (it
exists because of the JSON shape). Taking plain values keeps the service independent of the web
layer, and lets the purchase flow or a test call it without building a request object.

**Naming:** `receiveInventory`, a business word from the brief ("receiving new stock by SKU"), while
the repository function is `addStock`, a data word. Robert first named it `addStock` too. Each
layer names the action in its own vocabulary.

### `purchaseInventory`: one branch per outcome

```kotlin
/**
 * Removes quantity from a SKU's stock during a purchase. Returns the remaining InventoryItem on a success.
 * @throws SkuNotFoundException when no sku is found.
 * @throws InsufficientInventoryException if not enough quantity for the purchase.
 * @throws IllegalArgumentException if quantity is less than 1 (a caller bug, so it surfaces as a 500).
 */
fun purchaseInventory(skuId: String, quantity: Int): InventoryItem {
    // Why: @Valid only protects HTTP requests; this also stops direct callers (Can't have a 0 quantity in a purchase and a negative value would add stock.).
    require(quantity >= 1) {"quantity must be at least 1, was $quantity"}
    return when (val purchaseOutcome = inventoryRepository.removeStock(skuId,quantity)){
            is PurchaseResult.Purchased -> purchaseOutcome.item
            PurchaseResult.SkuNotFound -> throw SkuNotFoundException(skuId)
            PurchaseResult.InsufficientStock -> throw InsufficientInventoryException(skuId)
        }
}
```

**What it does:** it checks the argument, asks the repository to buy, and turns the repository's
[`PurchaseResult`](PurchaseResult.kt.md) into what the API needs. On success it returns the item;
for each failure it throws the matching domain exception.

**The `require`, again:** this is the same precondition as
[`receiveInventory`](#receiveinventory-a-precondition-and-a-block-body), and it matters more here.
With `-5`, the repository's `quantity >= -5` is always true and `quantity - (-5)` *adds* 5. The
database `CHECK (quantity >= 0)` wouldn't catch it, because the stock goes up, not down. Only this
line stops a purchase that adds stock. `InventoryServiceTest` checks `0` and `-5`.

**Kotlin (`when` with a subject):** `when (x) { ... }` compares one value, the **subject**, against
each branch in order. It's a much more capable version of Java's `switch`. The branches here use two
kinds of check:

| Branch | Kind | Means |
| --- | --- | --- |
| `is PurchaseResult.Purchased ->` | type check | "is the subject a `Purchased`?" (Java's `instanceof`) |
| `PurchaseResult.SkuNotFound ->` | equality | "is the subject equal to the `SkuNotFound` object?" |

The two `data object`s have exactly one instance each, so an equality check is enough.
`is PurchaseResult.SkuNotFound` would also work. `Purchased` needs `is`, because every successful
purchase creates a new `Purchased`, and there's no single instance to compare against.

**Kotlin (`val` in the subject):** `when (val purchaseOutcome = ...)` calls the repository once,
names the result, and limits that name to the `when`. Outside the braces, `purchaseOutcome` doesn't
exist. The branches need the name, because `.item` is read from it.

**Kotlin (smart cast, with a type):** `purchaseOutcome` has the declared type `PurchaseResult`, and
that interface has no `item`. In the `is PurchaseResult.Purchased` branch, the compiler knows it's a
`Purchased`, so `purchaseOutcome.item` compiles. That's a
[smart cast](InventoryRepository.kt.md#choosing-the-outcome-a-when-without-a-subject), this time
from an `is` check instead of a null check. Think of `Purchased` as a box and `item` as what's inside
it: the service returns what's inside (`InventoryItem`), not the box. Java 16+ equivalent:

```java
if (outcome instanceof PurchaseResult.Purchased p) return p.item();
```

**Kotlin (exhaustive, with no `else`):** there are three branches and no `else`, and it compiles
because `PurchaseResult` is [sealed](PurchaseResult.kt.md#a-sealed-interface). The compiler knows
these are the only three cases, and it checks that each one is handled. That's better than adding an
`else`. If a fourth case were added to `PurchaseResult`, this `when` would stop compiling until
someone decided how the service handles it. With an `else`, the new case would silently fall into
whatever the `else` did.

**Kotlin (`when` is an expression):** the `when` produces a value, and `return` returns it. The
branches have the types `InventoryItem`, `Nothing` and `Nothing`
([`throw` is an expression of type `Nothing`](#getinventory-turning-no-row-into-an-error)), so the
whole `when` has type `InventoryItem`, which matches the return type. The same `Nothing` rule is
what lets `?: throw` produce a non-null result in `getInventory`.

**Watch out: `return` and a line break.** `return` and the start of the `when` have to be on the
same line. In Kotlin, a line break after a bare `return` ends the statement, so this:

```kotlin
return
    when (val purchaseOutcome = ...) { ... }
```

is parsed as `return` with no value, followed by an unreachable `when`. The compiler rejects it,
because the function has to return an `InventoryItem`. Robert ran into this while writing the
function. Java doesn't have this trap, because semicolons, not line breaks, end statements.

**Why the exceptions are thrown here:** the repository reports *what happened*, and the service
decides *what that means*. This follows the same split as
[`getInventory`](#getinventory-turning-no-row-into-an-error). `SkuNotFoundException` is the same
exception `GET /inventory/{skuId}` uses, so the purchase endpoint's `404` comes from the same
handler with the same body.

**KDoc:** one `@throws` for each exception a caller can see, each saying *when*. "Returns the
remaining InventoryItem" says what the `200` body contains: the stock after the purchase, not the
amount bought. Robert changed it from "the new InventoryItem" in review, to match the controller's
KDoc and the contract's "remaining inventory for the SKU".

**Naming:** `purchaseInventory` is the business word. The repository's `removeStock` is the data
word for the same action, the same pairing as `receiveInventory` and `addStock`.

**Kotlin:** `removeStock(skuId,quantity)` with no space after the comma, and `){` with no space,
are both legal.

## Check your understanding

- Why is there no `try`/`catch` in `listInventory`? What does the client see if Postgres is down?
- In what order does Spring create the repository, service and controller beans, and why that order?
- What would happen at startup if `@Service` were removed?
- What is the type of `inventoryRepository.findBySkuId(skuId) ?: throw SkuNotFoundException(skuId)`, and why isn't it `InventoryItem?`?
- If `getInventory` returned `InventoryItem?` and passed the `null` up, what would the controller have to do differently, and which test would catch it if it did nothing?
- Why can't `receiveInventory` stay an expression body once `require` is added?
- A client posts `{"quantity": 0}`. Does the service's `require` run? What about a scheduled job calling `receiveInventory("x", 0)`?
- Why is the `require` failure a `500` and not a `400`?
- Why can `purchaseOutcome.item` be read in the first branch but not in the other two?
- A teammate adds `else -> throw IllegalStateException()` to the `when`. It still compiles. What protection is lost?
- What is the type of the whole `when`, and why isn't it `Any`?
- `purchaseInventory("widget", -5)` is called without the `require`. What does the SQL do to `widget`'s stock, and would the database `CHECK` stop it?
