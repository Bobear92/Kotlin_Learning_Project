# Design FAQ

Questions about how the inventory service works and why it's built the way it is, each with a short
answer. The answers link to the [study notes](learning/notes/README.md), which walk through the code
line by line.

**New to the service? Start with these four:**

1. [Why one upsert instead of "look up the row, then insert or update"?](#why-one-upsert-instead-of-look-up-the-row-then-insert-or-update): how stock is added safely.
2. [Your table has `CHECK (quantity >= 0)`. Isn't that enough to prevent overselling?](#your-table-has-check-quantity--0-isnt-that-enough-to-prevent-overselling): how a purchase never oversells.
3. [How does your API validate input?](#how-does-your-api-validate-input): what gets a `400`, and why.
4. [Why does the purchase stay correct with several app instances?](#why-does-the-purchase-stay-correct-with-several-app-instances): why it holds when the service is scaled out.

## Contents

**Design and failure modes**

- [What happens if two rows ever share the same SKU?](#what-happens-if-two-rows-ever-share-the-same-sku)
- [Why does the repository return `null` for a missing SKU instead of throwing?](#why-does-the-repository-return-null-for-a-missing-sku-instead-of-throwing)
- [How do you prevent SQL injection?](#how-do-you-prevent-sql-injection)
- [Why a custom `SkuNotFoundException` instead of Spring's `ResponseStatusException`?](#why-a-custom-skunotfoundexception-instead-of-springs-responsestatusexception)
- [What happens if the database is down during `GET /inventory`?](#what-happens-if-the-database-is-down-during-get-inventory)
- [Why one upsert instead of "look up the row, then insert or update"?](#why-one-upsert-instead-of-look-up-the-row-then-insert-or-update)
- [Doesn't that row lock slow the service down?](#doesnt-that-row-lock-slow-the-service-down)
- [Why is the overflow guard written as `current <= MAX - new`?](#why-is-the-overflow-guard-written-as-current--max---new)
- [Your table has `CHECK (quantity >= 0)`. Isn't that enough to prevent overselling?](#your-table-has-check-quantity--0-isnt-that-enough-to-prevent-overselling)

**API design and validation**

- [How does your API validate input?](#how-does-your-api-validate-input)
- [Stock can be 0. Why does the request require a quantity of at least 1?](#stock-can-be-0-why-does-the-request-require-a-quantity-of-at-least-1)
- [How would you add a new product that has no stock yet?](#how-would-you-add-a-new-product-that-has-no-stock-yet)
- [`@Valid` already rejects 0. Why check again with `require` in the service?](#valid-already-rejects-0-why-check-again-with-require-in-the-service)
- [Creating a SKU returns 200, not 201, and a wrong Content-Type is 400, not 415. Why?](#creating-a-sku-returns-200-not-201-and-a-wrong-content-type-is-400-not-415-why)
- [A purchase has an invalid quantity *and* a SKU that doesn't exist. `400` or `404`?](#a-purchase-has-an-invalid-quantity-and-a-sku-that-doesnt-exist-400-or-404)

**Kotlin**

- [What does the `?` in `InventoryItem?` mean, and how is it different from Java's `Optional`?](#what-does-the--in-inventoryitem-mean-and-how-is-it-different-from-javas-optional)
- [Walk me through `class SkuNotFoundException(skuId: String) : RuntimeException("SKU not found: $skuId")`](#walk-me-through-class-skunotfoundexceptionskuid-string--runtimeexceptionsku-not-found-skuid)
- [`private val`, `val`, or a plain parameter: what's the difference, and why are the layers `private val`?](#private-val-val-or-a-plain-parameter-whats-the-difference-and-why-are-the-layers-private-val)
- [What is `?:`, and how does `getInventory` return a non-null `InventoryItem` from a nullable lookup?](#what-is--and-how-does-getinventory-return-a-non-null-inventoryitem-from-a-nullable-lookup)
- [What if the service just passed the `null` up and the controller returned `InventoryItem?`?](#what-if-the-service-just-passed-the-null-up-and-the-controller-returned-inventoryitem)
- [Why is the row mapper a lambda and not `query(InventoryItem::class.java)`?](#why-is-the-row-mapper-a-lambda-and-not-queryinventoryitemclassjava)
- [What does `data class` give you, and does it make the class immutable?](#what-does-data-class-give-you-and-does-it-make-the-class-immutable)
- [Why does `removeStock` return a sealed `PurchaseResult` instead of `InventoryItem?` or throwing?](#why-does-removestock-return-a-sealed-purchaseresult-instead-of-inventoryitem-or-throwing)

**Spring**

- [Two requests arrive at the same time. How many controller objects are there?](#two-requests-arrive-at-the-same-time-how-many-controller-objects-are-there)
- [What happens if you remove `@Service` from `InventoryService`?](#what-happens-if-you-remove-service-from-inventoryservice)
- [How does a `SkuNotFoundException` thrown in the service become a 404?](#how-does-a-skunotfoundexception-thrown-in-the-service-become-a-404)
- [Why don't your handlers send Spring's `ex.message` to the client?](#why-dont-your-handlers-send-springs-exmessage-to-the-client)
- [When does each kind of mistake surface: compile time, startup, or the first request?](#when-does-each-kind-of-mistake-surface-compile-time-startup-or-the-first-request)
- [How would you deploy to a different database without changing any files?](#how-would-you-deploy-to-a-different-database-without-changing-any-files)

**Database and migrations**

- [You need a new `created_at` column. What do you do?](#you-need-a-new-created_at-column-what-do-you-do)

**Testing**

- [How do you know `GET /inventory/{skuId}` works? What do the tests actually cover?](#how-do-you-know-get-inventoryskuid-works-what-do-the-tests-actually-cover)
- [If `findBySkuId` lost its `WHERE` clause, which repository tests would fail?](#if-findbyskuid-lost-its-where-clause-which-repository-tests-would-fail)
- [How do you know your race tests would actually catch an oversell?](#how-do-you-know-your-race-tests-would-actually-catch-an-oversell)
- [Why does the purchase stay correct with several app instances?](#why-does-the-purchase-stay-correct-with-several-app-instances)

**Architecture and production**

- [How would you host this on AWS?](#how-would-you-host-this-on-aws)
- [Fulfillment receives 500 units. How does that reach your database, and what if inventory is down?](#fulfillment-receives-500-units-how-does-that-reach-your-database-and-what-if-inventory-is-down)
- [Would you put a Redis cache in front of the inventory?](#would-you-put-a-redis-cache-in-front-of-the-inventory)
- [Nuuly rents clothes. What does `quantity` mean for a garment that's out with a customer?](#nuuly-rents-clothes-what-does-quantity-mean-for-a-garment-thats-out-with-a-customer)

## Design and failure modes

### What happens if two rows ever share the same SKU?

`findBySkuId` ends in `.list().singleOrNull()`. With two rows, `singleOrNull()` returns `null`
without throwing. The service then throws `SkuNotFoundException`, and the client gets
**404 "SKU not found: widget"** for a SKU that actually exists twice. That's a silent bug: the API
gives a wrong answer and nothing is logged.

It can't happen today because `sku_id` is the **primary key**, so Postgres rejects a second
`widget` row. The Kotlin code is correct *because of* the database constraint. Keeping the primary
key is the real fix.

If `sku_id` ever stopped being unique, the lookup would need reworking:
- **`.single()`** throws on two rows, so the bug would surface loudly as a 500 instead of a false 404.
  But it also throws on **zero** rows, which is the normal "not found" case, so on its own it would
  turn every missing SKU into a 500.
- A version that handles both cases: `val rows = ...list()`, then `null` when `rows` is empty,
  the item when there's one, and an error (such as `IllegalStateException`) when there are more.
- Which row is "right" is a data question, not a code question, so a duplicate should fail, not be
  picked arbitrarily with `firstOrNull()`.

| List | `singleOrNull()` | `single()` | `firstOrNull()` |
| --- | --- | --- | --- |
| `[]` | `null` | throws | `null` |
| `[a]` | `a` | `a` | `a` |
| `[a, b]` | **`null`**, silently | throws | `a`, silently |

More detail: [InventoryRepository.kt, `singleOrNull`](learning/notes/main/inventory/InventoryRepository.kt.md#singleornull-zero-or-one-row)

### Why does the repository return `null` for a missing SKU instead of throwing?

"No row" is a normal answer to a lookup, not a failure. The repository only reports what the table
holds. The **service** decides what a missing row means for each endpoint: `GET /inventory/{skuId}`
turns it into a 404. A purchase also has to tell "no such SKU" apart from "not enough stock", so its
repository call returns a sealed `PurchaseResult` instead (see the Kotlin section). The `?` in
`InventoryItem?` forces every caller to handle the missing case, because the compiler won't let them
use `.quantity` until they have.

More detail: [InventoryRepository.kt, nullable return type](learning/notes/main/inventory/InventoryRepository.kt.md#findbyskuid-a-nullable-return-type)

### How do you prevent SQL injection?

The SKU comes straight from the URL, so it's untrusted. The query uses a named parameter,
`WHERE sku_id = :skuId`, bound with `.param("skuId", skuId)`. `JdbcClient` runs it as a JDBC
`PreparedStatement`: Postgres parses the SQL first and receives the value separately, so the value
can never change the query's structure. `x' OR '1'='1` is just a string that matches no SKU.

Building the SQL with a string template (`"... WHERE sku_id = '$skuId'"`) would paste the value
into the SQL text, and the attacker's quote would close ours.

More detail: [InventoryRepository.kt, binding the parameter](learning/notes/main/inventory/InventoryRepository.kt.md#binding-the-parameter-and-mapping-the-row)

### Why a custom `SkuNotFoundException` instead of Spring's `ResponseStatusException`?

Not for a more detailed message: `ResponseStatusException(HttpStatus.NOT_FOUND, "SKU not found: $skuId")`
takes a message too. The real reasons are about which layer knows about HTTP:

1. **The service shouldn't speak HTTP.** `ResponseStatusException` puts a status code in the
   service. `SkuNotFoundException` only names the problem; `InventoryExceptionHandler` decides it's
   a 404. Same split as the repository returning `null` and the service deciding what that means.
2. **Reuse.** The purchase endpoint throws the same exception, and the same handler maps it.
   One place to change if the 404 format changes.
3. **Contract.** `ResponseStatusException` gives a JSON error body by default; inventory.yaml
   requires `text/plain`.

One line: *the exception names the problem, the handler picks the response.*

More detail: [SkuNotFoundException.kt](learning/notes/main/inventory/error/SkuNotFoundException.kt.md#the-whole-class-in-one-line)

### What happens if the database is down during `GET /inventory`?

`JdbcClient` throws a `DataAccessException`. Nothing catches it, so Spring returns a 500. That's
the honest answer: catching it and returning `[]` would tell the client "you have no stock" when
the truth is "we don't know".

More detail: [InventoryService.kt](learning/notes/main/inventory/InventoryService.kt.md)

### Why one upsert instead of "look up the row, then insert or update"?

Find-then-write makes the decision in Kotlin, between two trips to the database, and another
request can change the row in that gap. With `widget` at 5 and two requests for +3 and +4 arriving
together:

| Time | Request A (+3) | Request B (+4) | Row |
| --- | --- | --- | --- |
| 1 | reads 5 | | 5 |
| 2 | | reads 5 | 5 |
| 3 | writes 8 | | 8 |
| 4 | | writes 9 | **9**, should be 12 |

That's a **lost update**, and it's silent. For a new SKU, both see "no row", both `INSERT`, and one
fails with a duplicate-key error. `INSERT ... ON CONFLICT DO UPDATE` puts the decision inside one
statement: Postgres runs it atomically and locks the row while it changes it. `RETURNING` gives back
the new total from the same statement, so there's no second `SELECT` to race either.

`InventoryConcurrencyTest` proves it: 50 threads released together through a `CountDownLatch`
start gate, repeated 5 times. With find-then-write, the total would come out **too low and
different on every run**, and the new-SKU test would collect duplicate-key exceptions.

More detail: [InventoryRepository.kt, `addStock`](learning/notes/main/inventory/InventoryRepository.kt.md#addstock-the-signature-and-the-two-decisions)

### Doesn't that row lock slow the service down?

No. It's the cheaper option:
- The lock is on **one row**, so different SKUs never wait for each other.
- It's held for one statement, well under a millisecond.
- Find-then-write needs two round trips, and making it *correct* (a transaction plus
  `SELECT ... FOR UPDATE`) holds a lock across both trips and the Kotlin in between, which is longer.

Where it would hurt: one extremely hot SKU taking thousands of writes a second queues on one row.
That's a scale problem with its own fixes (batching writes, splitting the counter across rows), far
beyond this app.

More detail: [InventoryRepository.kt](learning/notes/main/inventory/InventoryRepository.kt.md#addstock-the-signature-and-the-two-decisions)

### Why is the overflow guard written as `current <= MAX - new`?

The column is a 4-byte Postgres `integer`, like Kotlin's `Int`. The natural form,
`current + new <= MAX`, has to compute `current + new` first, and when the total is too big
Postgres stops with `ERROR: integer out of range`: a `500` instead of a refusal. It can answer
"true" or crash, never "false". Moving `new` to the other side keeps every value in range, so the
comparison completes. When it's false, the update is skipped, `RETURNING` returns no row,
`singleOrNull()` gives `null`, and the service throws `AddStockOverflowException` → `400`, with the
stock unchanged.

It relies on `new >= 1`: with `-5`, `MAX - (-5)` is `MAX + 5`, which overflows. The repository's
KDoc states that precondition, and the service's `require` enforces it.

More detail: [InventoryRepository.kt, the overflow guard](learning/notes/main/inventory/InventoryRepository.kt.md#the-overflow-guard)

### Your table has `CHECK (quantity >= 0)`. Isn't that enough to prevent overselling?

No. The constraint protects one invariant, "stock is never negative". It doesn't validate intent,
and two real bugs pass it:

- **A negative purchase.** Without the service's `require`, `purchaseInventory("widget", -5)` on
  a stock of 10 runs `WHERE 10 >= -5` (true) and `SET quantity = 10 - (-5)`, so the stock goes
  *up* to 15. `15 >= 0`, so the `CHECK` passes. Only the `require` stops it.
- **A lost update.** The naive purchase reads the stock, checks it in Kotlin, then writes an
  absolute value. Twelve buyers who all read 10 all write `SET quantity = 9`: twelve sales, one
  unit gone. Every value written is `>= 0`, so the `CHECK` never fires.

What actually prevents overselling is putting the check *inside* the write:
`UPDATE ... SET quantity = quantity - :n WHERE quantity >= :n`. Postgres locks the row, and a
waiting `UPDATE` re-checks its `WHERE` against the newly committed value. The `CHECK` is the last
line of defense if that SQL were ever wrong. Input validation still belongs in the application.

More detail: [InventoryService.kt, `purchaseInventory`](learning/notes/main/inventory/InventoryService.kt.md#purchaseinventory-one-branch-per-outcome),
[InventoryConcurrencyTest.kt, purchase race](learning/notes/test/inventory/InventoryConcurrencyTest.kt.md#the-same-sku-purchase-race)

## API design and validation

### How does your API validate input?

Two checks, in order, each with its own 400 message:

| Check | Runs in | Catches | Response |
| --- | --- | --- | --- |
| **1. Is it an `Int` at all?** | Jackson, while reading the body | malformed JSON, missing or `null` `quantity`, `"5"`, `1.5`, a number too big for `Int` | 400 `Invalid request body` |
| **2. Does the value follow the rules?** | Bean Validation (`@Min(1)` + `@Valid`), after Jackson succeeds | `0`, `-5` | 400 `quantity must be at least 1` |

Jackson has to build the object before validation can check it, so `{"quantity": 0}` passes check 1
and fails check 2, and `{"quantity": 1.5}` never reaches check 2. The messages tell the client
different things: "you didn't send valid input" vs "your input broke a rule".

Jackson's defaults are lenient: they'd turn `"5"` into 5 and silently truncate `1.5` to 1. Two
settings in `application.yml` turn that off (`accept-float-as-int`, `allow-coercion-of-scalars`),
because a stock count must be exactly what the client sent, never a guess. A wrong `Content-Type`
is a third, earlier check (400 instead of Spring's 415, which the contract doesn't list).

More detail: [application.yml, strict JSON](learning/notes/main/resources/application.yml.md#strict-json-the-jackson-block)

### Stock can be 0. Why does the request require a quantity of at least 1?

They're two different quantities with two schemas in the contract:

| Schema | `quantity` means | Rule |
| --- | --- | --- |
| `InventoryQuantity` (request body) | how many units to add or remove **in this operation** | `minimum: 1` |
| `InventoryItem` (response, what's stored) | how many units are **on hand** | `minimum: 0` |

A stock level of 0 is normal: sold out. `CHECK (quantity >= 0)` allows it, and `GET` returns
`{"quantity": 0}` for it. A *delivery* of 0 units is a no-op or a client bug, so it's rejected.
The rule also closes a side door: without it, receiving 0 would create a SKU without receiving any
stock. Negatives matter more: receiving -5 would be an untracked purchase that skips the
insufficient-stock check, and the overflow guard in `addStock` assumes quantity >= 1.

More detail: [application.yml, strict JSON](learning/notes/main/resources/application.yml.md#strict-json-the-jackson-block)

### How would you add a new product that has no stock yet?

Not through this API, by design. The contract creates a SKU only as a side effect of receiving
stock ("If the SKU does not yet exist in inventory, it is created"), and `minimum: 1` rules out a
zero delivery.

"We sell this product" is a **catalog** fact; "we have N on hand" is an **inventory** fact. The
catalog would be a `product` table, with `inventory.sku_id` as a foreign key to it, or at a company
Nuuly's size often its own service, sharing only the SKU id. "In the catalog, no inventory row yet"
is exactly the new, never-stocked product.

If this service had to register SKUs, it would get a separate endpoint. Relaxing `minimum: 1`
would make "receive" mean two things and break the contract. One consequence to know: a `404`
from `GET` can't tell "unknown product" from "never stocked", which is fine for inventory, because
you can't sell either.

### `@Valid` already rejects 0. Why check again with `require` in the service?

`@Valid` only protects the HTTP entry point. The service is a public function that a test, a
scheduled job or the purchase code can call directly, and before the `require`,
`receiveInventory("new-sku", 0)` created an empty SKU. Each check has a different audience:

| Check | Catches | Status |
| --- | --- | --- |
| `@Valid` + `@Min` | a bad client request | 400: the client's fault |
| `require` | a bad call from our own code | 500: our bug |
| DB `CHECK (quantity >= 0)` | anything past both | 500 |

That's why nothing maps `IllegalArgumentException` to 400: if it fires, the mistake is ours.
`InventoryServiceTest` calls the service directly to prove it.

More detail: [InventoryService.kt](learning/notes/main/inventory/InventoryService.kt.md#receiveinventory-a-precondition-and-a-block-body)

### Creating a SKU returns 200, not 201, and a wrong Content-Type is 400, not 415. Why?

REST convention says `201 Created` and `415 Unsupported Media Type`, but the contract lists only
`200` and `400` for this endpoint, and clients are written against the contract. **The contract
wins**; the README's "Contract gaps (POST)" records the decision. The `200` is Spring's default for a
plain return value, pinned by `status { isOk() }` in the controller test. The `400` needs our own
handler, because without it Spring's built-in resolver answers `HttpMediaTypeNotSupportedException`
with `415` and a JSON body.

More detail: [InventoryController.kt](learning/notes/main/inventory/InventoryController.kt.md#post-inventoryskuid)

### A purchase has an invalid quantity *and* a SKU that doesn't exist. `400` or `404`?

`400`. Spring handles a request in a fixed order, and everything before the handler function runs
first:

```
1. Routing          /inventory/missing/purchase → purchaseInventory  ({skuId} matches any text)
2. @RequestBody     Content-Type, then Jackson reads the JSON          → 400
3. @Valid           @Min(1) on quantity                                 → 400  ← {"quantity": 0} stops here
4. handler → service → repository → SQL                                 → 404  ← "missing" is found here
```

Routing has no idea which SKUs exist, so `missing` is a valid URL. Only the SQL in step 4 finds
out, and a bad body never gets there. It's also the cheaper order: a malformed request never costs
a database round trip. `InventoryControllerTest` pins it.

More detail: [InventoryController.kt, purchase](learning/notes/main/inventory/InventoryController.kt.md#post-inventoryskuidpurchase)

## Kotlin

### What does the `?` in `InventoryItem?` mean, and how is it different from Java's `Optional`?

`InventoryItem?` is its own type, "an item or `null`", and the compiler keeps it apart from
`InventoryItem`. You can't call `.quantity` on it, return it where an `InventoryItem` is expected,
or pass it along, until you've handled `null` (`?.`, `?:`, an `if` check, or `!!`).

In Java any reference can be null and nothing warns you; `Optional` is an opt-in wrapper object.
Kotlin puts the information in the type at no runtime cost: an `InventoryItem?` is a normal
reference with no wrapper.

More detail: [InventoryRepository.kt](learning/notes/main/inventory/InventoryRepository.kt.md#findbyskuid-a-nullable-return-type)

### Walk me through `class SkuNotFoundException(skuId: String) : RuntimeException("SKU not found: $skuId")`

- **`: RuntimeException("...")`** is `extends RuntimeException` plus the `super(...)` call in one
  step. The parentheses are required: Kotlin always makes you say which superclass constructor runs.
  Java equivalent:

  ```java
  public class SkuNotFoundException extends RuntimeException {
      public SkuNotFoundException(String skuId) {
          super("SKU not found: " + skuId);
      }
  }
  ```

- **`RuntimeException`, not `Exception`:** Kotlin has no checked exceptions, so neither forces a
  `try/catch`. But Spring's `@Transactional` rolls back by default only on unchecked exceptions,
  which would matter if a multi-statement transaction were ever added. Today every stock change is a
  single SQL statement, so the code has no `@Transactional`.
- **`skuId: String` with no `val`** is a plain constructor parameter. It exists while the object is
  being built (it's used in the message) and is then gone, so `ex.skuId` doesn't compile. Adding
  `val` would make it a property stored on the object, like `private val jdbcClient` in the
  repository. Here the SKU only needs to live inside the message text.
- **`"SKU not found: $skuId"`** is a string template, Java's `"SKU not found: " + skuId`. Anything
  more than a bare name needs braces: `"${item.skuId}"`. `"$item.skuId"` inserts `item` followed by
  the literal text `.skuId`.

More detail: [SkuNotFoundException.kt](learning/notes/main/inventory/error/SkuNotFoundException.kt.md#the-whole-class-in-one-line)

### `private val`, `val`, or a plain parameter: what's the difference, and why are the layers `private val`?

Two separate settings. **`val`** decides *storage*: is the value kept on the object after the
constructor? **`private`** decides *visibility*: who can read it once it's kept?

| Declaration | Stored? | Who can read it |
| --- | --- | --- |
| `(skuId: String)` | no | nobody after construction |
| `(val skuId: String)` | yes | anyone (public is the default) |
| `(private val skuId: String)` | yes | only this class |

The layers use `private val` because each one needs its dependency later (stored), and nobody else
should reach it (private). That enforces the architecture: the controller can't call
`inventoryService.inventoryRepository.findBySkuId(...)` and skip the service's
`null` → `SkuNotFoundException` logic. It's a compile error, not a code-review rule.

More detail: [SkuNotFoundException.kt, storage vs visibility](learning/notes/main/inventory/error/SkuNotFoundException.kt.md#the-whole-class-in-one-line)

### What is `?:`, and how does `getInventory` return a non-null `InventoryItem` from a nullable lookup?

`?:` is the **Elvis operator**, not a ternary. Kotlin has no ternary: `if` is an expression, so
`if (cond) a else b` does that job. Elvis is narrower: `a ?: b` means "`a`, unless it's `null`, then `b`".

```kotlin
fun getInventory(skuId: String): InventoryItem =
    inventoryRepository.findBySkuId(skuId) ?: throw SkuNotFoundException(skuId)
```

Why the non-null return type compiles: in Kotlin, `throw` is an **expression** of type `Nothing`
("never finishes normally"), and `Nothing` fits in as any type. So the whole expression is
`InventoryItem`: the function returns a real item or throws, and `null` never escapes. In Java,
`throw` is a statement, so you'd need an `if`, or `Optional.orElseThrow(...)`.

`@throws` in the KDoc matters more than in Java: Kotlin has no `throws` clause, so the KDoc is the
only place a caller learns the function can throw.

More detail: [InventoryService.kt, `getInventory`](learning/notes/main/inventory/InventoryService.kt.md#getinventory-turning-no-row-into-an-error)

### What if the service just passed the `null` up and the controller returned `InventoryItem?`?

It would compile. Kotlin doesn't force you to handle `null` immediately, only to be honest about it:
passing an `InventoryItem?` out through an `InventoryItem?` return type claims nothing false. The
compiler only objects when a "maybe" is used as a "definitely" (`item.quantity`, or returning it as
`InventoryItem`). Declaring `getInventory` as returning non-null `InventoryItem` is what forces the Elvis.

At runtime there's no NPE, because nothing dereferences the `null`. Spring gets `null` from a
`@RestController` method and writes **no body**: the client gets **`200 OK` with an empty body** for
a SKU that doesn't exist. The contract requires `404` `text/plain`. The controller test for an
unknown SKU (`isNotFound()`) would catch it.

The point: the compiler protects every step we write, but the last hand-off to Spring is outside
its reach. Making the service's return type non-null keeps `null` from ever getting there.

More detail: [InventoryService.kt, `getInventory`](learning/notes/main/inventory/InventoryService.kt.md#getinventory-turning-no-row-into-an-error)

### Why is the row mapper a lambda and not `query(InventoryItem::class.java)`?

The class-based version didn't compile: Spring 7 declares it as "each row might map to null", so
Kotlin saw `List<InventoryItem?>`. The lambda keeps its own return type, and it always builds a
real `InventoryItem`, so the list is `List<InventoryItem>`. It's a compile-time fix; the data was
never actually null. It's also more explicit about which column goes into which property.

More detail: [InventoryRepository.kt, the lambda](learning/notes/main/inventory/InventoryRepository.kt.md#mapping-each-row-the-lambda)

### What does `data class` give you, and does it make the class immutable?

It generates `equals`, `hashCode`, `toString`, `copy` and `componentN` from the constructor
properties. So `InventoryItem("widget", 5) == InventoryItem("widget", 5)` is `true` (value
equality, because `==` calls `equals`). It does **not** make the class immutable: `val` does.
`copy` returns a new object and leaves the original unchanged.

More detail: [InventoryItem.kt](learning/notes/main/inventory/InventoryItem.kt.md)

### Why does `removeStock` return a sealed `PurchaseResult` instead of `InventoryItem?` or throwing?

A purchase has three outcomes: bought, not enough stock (`400`), and no such SKU (`404`). A `null`
can only carry one failure, so the service would need a second query to learn which, and the stock
could change in between. Throwing from the repository works, but then the repository decides what
counts as an error, which is the service's job.

`sealed interface PurchaseResult` with `Purchased(item)`, `SkuNotFound` and `InsufficientStock`
carries each outcome separately. Because it's sealed, the service's `when` needs no `else`: if
someone adds a fourth case, that `when` stops compiling until it's handled, instead of falling
silently into an `else`. `Purchased` is a `data class` because it carries data. The failures are
`data object`s, one instance each.

More detail: [PurchaseResult.kt](learning/notes/main/inventory/PurchaseResult.kt.md)

## Spring

### Two requests arrive at the same time. How many controller objects are there?

One. Beans are singletons, built once at startup through constructor injection, bottom-up
(repository → service → controller). Every request thread shares the same object. That's safe
because the beans are **stateless**: they only hold `val` references to other beans, never
per-request data.

More detail: [InventoryController.kt](learning/notes/main/inventory/InventoryController.kt.md)

### What happens if you remove `@Service` from `InventoryService`?

The app fails at **startup**, not on the first request: the controller's constructor needs an
`InventoryService` bean and none exists ("required a bean of type ... that could not be found").
Beans are created at startup, so missing dependencies fail fast.

More detail: [InventoryService.kt](learning/notes/main/inventory/InventoryService.kt.md)

### How does a `SkuNotFoundException` thrown in the service become a 404?

The exception passes untouched through the controller. Spring catches **every** exception that
escapes a controller and asks its resolvers, in order:

1. `@ExceptionHandler` methods: in the controller itself, then in `@ControllerAdvice` /
   `@RestControllerAdvice` beans. `InventoryExceptionHandler.handleSkuNotFound` matches here.
2. `@ResponseStatus` on the exception class, and `ResponseStatusException`.
3. Spring's built-in mappings (e.g. malformed request → 400).
4. Nothing matched → 500 with Spring Boot's default JSON error body.

Nothing in our code calls the handler: the advice is a bean found by component scanning, and
Spring builds an exception-type → method table from its `@ExceptionHandler` methods at startup.
Matching includes subclasses; the most specific handler wins. `@RestControllerAdvice` =
`@ControllerAdvice` + `@ResponseBody`.

The handler returns `ResponseEntity<String>`, not `String`, because a plain return only sets the body
(Spring would send 200). `ResponseEntity` sets status (404), content type (`text/plain`) and body.
`ex.message ?: "..."` is needed because `Throwable.message` is declared `String?`: the compiler
can't know our subclass always sets it.

More detail: [InventoryExceptionHandler.kt](learning/notes/main/inventory/error/InventoryExceptionHandler.kt.md)

### Why don't your handlers send Spring's `ex.message` to the client?

Spring's messages are written for developers. A validation failure's `ex.message` includes the full
controller method signature, package and class names: an **information leak** to anyone who sends a
bad request. So the validation handler sends only the `@Min` message we wrote
(`ex.bindingResult.fieldError?.defaultMessage`: "default" is misleading, it's ours), and the other two
handlers send fixed strings. `handleInvalidRequestBody` doesn't even take the exception as a
parameter, because it never reads it.

More detail: [InventoryExceptionHandler.kt](learning/notes/main/inventory/error/InventoryExceptionHandler.kt.md)

### When does each kind of mistake surface: compile time, startup, or the first request?

| When | Example from this project | Who catches it |
| --- | --- | --- |
| **Compile time** | returning `InventoryItem?` as `InventoryItem`; `ex.skuId` on a plain constructor parameter | the compiler |
| **Startup** | missing `@Service`: the controller needs the bean, so the context fails to start | Spring, before any request |
| **First request** | `@GetMapping("/{skuId}")` with `@PathVariable sku: String`; missing `@RestControllerAdvice`; a column typo in SQL | only a test that makes the request, or a user |

The path-variable mismatch compiles (`"/{skuId}"` is just a string to the compiler) and the app
starts (Spring doesn't compare placeholder names to parameters). On a request, Spring throws
`MissingPathVariableException` and answers **500**: it's a server bug, not a client mistake. Fix it
with `@PathVariable("skuId") sku: String`, or keep the names equal.

This is why the controller tests go through MockMvc (real path matching, real exception handling)
instead of calling `controller.getInventory("widget")` directly, which would pass with the bug.

More detail: [InventoryController.kt](learning/notes/main/inventory/InventoryController.kt.md)

### How would you deploy to a different database without changing any files?

Set environment variables such as `SPRING_DATASOURCE_URL`. Spring layers property sources, and
environment variables override `application.yml`, so the repo never holds per-environment values
or passwords. Whatever launches the process (Kubernetes, ECS, `docker run -e`) sets them.
Command-line arguments beat environment variables, which beat the yml.

More detail: [application.yml](learning/notes/main/resources/application.yml.md)

## Database and migrations

### You need a new `created_at` column. What do you do?

Add a new migration, `V2__add_created_at.sql`, with `ALTER TABLE`. Migrations are a chain of
changes, like commits; the schema is the whole chain. Editing `V1` after it has run makes Flyway
fail at startup with a checksum mismatch.

More detail: [V1__create_inventory.sql](learning/notes/main/resources/db/migration/V1__create_inventory.sql.md)

## Testing

### How do you know `GET /inventory/{skuId}` works? What do the tests actually cover?

Two levels, each against a real Postgres (Testcontainers):

- **Repository tests** (`@JdbcTest`, each test rolled back): call `findBySkuId` directly. Found,
  missing → `null`, zero stock is still found, and `WIDGET` ≠ `widget` (case-sensitive).
- **Controller tests** (`@SpringBootTest` + MockMvc): a real request through path matching,
  controller, service, repository and the exception handler. Exact JSON with
  `JsonCompareMode.STRICT` on 200; status, `text/plain` and the exact message on 404.

The zero-stock and case-sensitivity cases are tested at both levels on purpose: the repository
tests prove the SQL, and the controller tests prove nothing above the SQL changes the answer (for
example, a service that treated `quantity == 0` as "not found").

More detail: [InventoryControllerTest.kt](learning/notes/test/inventory/InventoryControllerTest.kt.md),
[InventoryRepositoryTest.kt](learning/notes/test/inventory/InventoryRepositoryTest.kt.md)

### If `findBySkuId` lost its `WHERE` clause, which repository tests would fail?

Trace each test: rows in the table → what the query returns → `singleOrNull()` → compare. Each test
starts with an empty table, so "the whole database" is only that test's own inserts.

| Test | Rows | Query returns | `singleOrNull()` | Result |
| --- | --- | --- | --- | --- |
| returns the item | `widget`, `gadget` | 2 rows | `null` | fail |
| null for a missing SKU | `widget` | 1 row | `widget` | fail (wrong item, not `null`) |
| zero stock | `sold-out` | 1 row | `sold-out` | **pass, by coincidence** |
| case-sensitive | `widget` | 1 row | `widget` | fail (wrong item, not `null`) |

Lesson: a table with one row can't tell "found the right row" apart from "returned whatever was
there". That's why the first test inserts `gadget` as a **decoy**. The function can never return
"everything": its type is `InventoryItem?`, and `singleOrNull()` squashes the list to one item or `null`.

More detail: [InventoryRepositoryTest.kt](learning/notes/test/inventory/InventoryRepositoryTest.kt.md)

### How do you know your race tests would actually catch an oversell?

A **negative control**: `NegativeControlTest` runs a deliberately naive purchase
(`NaiveInventoryRepository`: read the stock, check it in Kotlin, write an absolute value) through
the same `race()` and the same `assertNoOversell` that guard the real repository.

- When buyers take turns, the naive code passes the check. So it isn't simply buggy.
- When they race, a `CyclicBarrier` holds every buyer between its read and its write. All 12 read
  10 and all write 9: twelve sales from ten units, the stock ends at 9, and no error is raised
  (9 passes `CHECK (quantity >= 0)`). The check fails on its `oversold` assertion, and the test
  asserts it failed for that reason.

Because the check is **shared**, this proves the exact assertions on the real code can detect a
lost update. More detail: [NegativeControlTest notes](learning/notes/test/inventory/NegativeControlTest.kt.md).

### Why does the purchase stay correct with several app instances?

The guarantee lives in Postgres, not in the app: one `UPDATE ... SET quantity = quantity - :q
WHERE quantity >= :q` takes a row lock, so a second buyer waits and re-checks against the new value.
A Kotlin-side fix (a `synchronized` block or a per-SKU lock around read-then-write) would pass the
single-app race tests but fail with two instances, because each JVM has its own lock.
`MultiInstanceRaceTest` starts two full app contexts on one database and races buyers across both
over HTTP to prove it. More detail: [MultiInstanceRaceTest notes](learning/notes/test/inventory/MultiInstanceRaceTest.kt.md).

## Architecture and production

The service isn't deployed anywhere. These answers describe a hypothetical production setup; the full sketch,
with a diagram, is in [docs/architecture/architecture.md](architecture/architecture.md).

### How would you host this on AWS?

Route 53 and API Gateway (with WAF) in front of an experience layer, which calls the inventory service privately
through an internal ALB. Both run on ECS Fargate, the inventory service as 2+ tasks across 2 availability zones,
backed by RDS PostgreSQL Multi-AZ. Fargate because at ~100 TPS a cluster (EKS) is overkill, and Lambda means JVM
cold starts plus a database connection per invocation. Running several tasks is safe because the oversell
guarantee lives in Postgres, not in app memory: `MultiInstanceRaceTest` proves it.

### Fulfillment receives 500 units. How does that reach your database, and what if inventory is down?

Never by writing to the tables directly: the inventory service owns its data, so its rules always run.
Fulfillment publishes a "shipment received" message with a shipment ID to SQS. The inventory service consumes it
and runs the same code as `POST /inventory/{skuId}`. If inventory is down, the messages wait in the queue.

The shipment ID matters because receiving *adds* stock, and SQS can deliver a message twice. Inserting the ID into
a `processed_shipments` table in the same transaction as the stock change makes a duplicate a no-op.

### Would you put a Redis cache in front of the inventory?

Carts, yes: they're short-lived and a TTL handles abandonment. Inventory counts only when read traffic strains
Postgres. At ~100 TPS a primary-key read is about a millisecond, and RDS bills per instance, not per query, so
a cache would add cost.

If I add one: cache-aside, delete the key on every write, short TTL. Not write-through, because the database write
and the cache write aren't atomic: two purchases can update them in opposite orders and leave an old count cached.
And a purchase never reads the cache: the atomic `UPDATE ... WHERE quantity >= :q` is the final check *and* the
write. Checking first and then writing brings back the oversell race.

### Nuuly rents clothes. What does `quantity` mean for a garment that's out with a customer?

`quantity` is units on hand and available to ship. Rented garments aren't counted. A return is restocked
through the same receive path once it's inspected and cleaned. With more time, I'd track individual garments with
a status (available, rented, cleaning) instead of a count.
