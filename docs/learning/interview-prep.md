# Interview prep

Questions a reviewer might ask in the follow-up interview, with short answers I should be able to
give out loud. Answers are folded: try each question first, then open it to check.

New questions are added during walkthroughs when a topic looks interview-worthy. Longer
explanations live in the [study notes](notes/README.md); each answer links to them.

## Design and failure modes

### What happens if two rows ever share the same SKU?

<details>
<summary>Answer</summary>

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

Notes: [InventoryRepository.kt, `singleOrNull`](notes/main/inventory/InventoryRepository.kt.md#singleornull-zero-or-one-row)
</details>

### Why does the repository return `null` for a missing SKU instead of throwing?

<details>
<summary>Answer</summary>

"No row" is a normal answer to a lookup, not a failure. The repository only reports what the table
holds. The **service** decides what a missing row means for each endpoint: `GET /inventory/{skuId}`
turns it into a 404, and the purchase endpoint can make its own decision. The `?` in
`InventoryItem?` forces every caller to handle the missing case, because the compiler won't let them
use `.quantity` until they have.

Notes: [InventoryRepository.kt, nullable return type](notes/main/inventory/InventoryRepository.kt.md#findbyskuid-a-nullable-return-type)
</details>

### How do you prevent SQL injection?

<details>
<summary>Answer</summary>

The SKU comes straight from the URL, so it's untrusted. The query uses a named parameter,
`WHERE sku_id = :skuId`, bound with `.param("skuId", skuId)`. `JdbcClient` runs it as a JDBC
`PreparedStatement`: Postgres parses the SQL first and receives the value separately, so the value
can never change the query's structure. `x' OR '1'='1` is just a string that matches no SKU.

Building the SQL with a string template (`"... WHERE sku_id = '$skuId'"`) would paste the value
into the SQL text, and the attacker's quote would close ours.

Notes: [InventoryRepository.kt, binding the parameter](notes/main/inventory/InventoryRepository.kt.md#binding-the-parameter-and-mapping-the-row)
</details>

### Why a custom `SkuNotFoundException` instead of Spring's `ResponseStatusException`?

<details>
<summary>Answer</summary>

Not for a more detailed message: `ResponseStatusException(HttpStatus.NOT_FOUND, "SKU not found: $skuId")`
takes a message too. The real reasons are about which layer knows about HTTP:

1. **The service shouldn't speak HTTP.** `ResponseStatusException` puts a status code in the
   service. `SkuNotFoundException` only names the problem; `InventoryExceptionHandler` decides it's
   a 404. Same split as the repository returning `null` and the service deciding what that means.
2. **Reuse.** The purchase endpoint will throw the same exception, and the same handler maps it.
   One place to change if the 404 format changes.
3. **Contract.** `ResponseStatusException` gives a JSON error body by default; inventory.yaml
   requires `text/plain`.

One line: *the exception names the problem, the handler picks the response.*

Notes: [SkuNotFoundException.kt](notes/main/inventory/error/SkuNotFoundException.kt.md#the-whole-class-in-one-line)
</details>

### What happens if the database is down during `GET /inventory`?

<details>
<summary>Answer</summary>

`JdbcClient` throws a `DataAccessException`. Nothing catches it, so Spring returns a 500. That's
the honest answer: catching it and returning `[]` would tell the client "you have no stock" when
the truth is "we don't know".

Notes: [InventoryService.kt](notes/main/inventory/InventoryService.kt.md)
</details>

## API design and validation

### How does your API validate input?

<details>
<summary>Answer</summary>

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

Notes: [application.yml, strict JSON](notes/main/resources/application.yml.md#strict-json-the-jackson-block)
</details>

### Stock can be 0. Why does the request require a quantity of at least 1?

<details>
<summary>Answer</summary>

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

Notes: [application.yml, strict JSON](notes/main/resources/application.yml.md#strict-json-the-jackson-block)
</details>

### How would you add a new product that has no stock yet?

<details>
<summary>Answer</summary>

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
</details>

## Kotlin

### What does the `?` in `InventoryItem?` mean, and how is it different from Java's `Optional`?

<details>
<summary>Answer</summary>

`InventoryItem?` is its own type, "an item or `null`", and the compiler keeps it apart from
`InventoryItem`. You can't call `.quantity` on it, return it where an `InventoryItem` is expected,
or pass it along, until you've handled `null` (`?.`, `?:`, an `if` check, or `!!`).

In Java any reference can be null and nothing warns you; `Optional` is an opt-in wrapper object.
Kotlin puts the information in the type at no runtime cost: an `InventoryItem?` is a normal
reference with no wrapper.

Notes: [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md#findbyskuid-a-nullable-return-type)
</details>

### Walk me through `class SkuNotFoundException(skuId: String) : RuntimeException("SKU not found: $skuId")`

<details>
<summary>Answer</summary>

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
  which will matter once purchases write to the database.
- **`skuId: String` with no `val`** is a plain constructor parameter. It exists while the object is
  being built (it's used in the message) and is then gone, so `ex.skuId` doesn't compile. Adding
  `val` would make it a property stored on the object, like `private val jdbcClient` in the
  repository. Here the SKU only needs to live inside the message text.
- **`"SKU not found: $skuId"`** is a string template, Java's `"SKU not found: " + skuId`. Anything
  more than a bare name needs braces: `"${item.skuId}"`. `"$item.skuId"` inserts `item` followed by
  the literal text `.skuId`.

Notes: [SkuNotFoundException.kt](notes/main/inventory/error/SkuNotFoundException.kt.md#the-whole-class-in-one-line)
</details>

### `private val`, `val`, or a plain parameter: what's the difference, and why are the layers `private val`?

<details>
<summary>Answer</summary>

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

Notes: [SkuNotFoundException.kt, storage vs visibility](notes/main/inventory/error/SkuNotFoundException.kt.md#the-whole-class-in-one-line)
</details>

### What is `?:`, and how does `getInventory` return a non-null `InventoryItem` from a nullable lookup?

<details>
<summary>Answer</summary>

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

Notes: [InventoryService.kt, `getInventory`](notes/main/inventory/InventoryService.kt.md#getinventory-turning-no-row-into-an-error)
</details>

### What if the service just passed the `null` up and the controller returned `InventoryItem?`?

<details>
<summary>Answer</summary>

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

Notes: [InventoryService.kt, `getInventory`](notes/main/inventory/InventoryService.kt.md#getinventory-turning-no-row-into-an-error)
</details>

### Why is the row mapper a lambda and not `query(InventoryItem::class.java)`?

<details>
<summary>Answer</summary>

The class-based version didn't compile: Spring 7 declares it as "each row might map to null", so
Kotlin saw `List<InventoryItem?>`. The lambda keeps its own return type, and it always builds a
real `InventoryItem`, so the list is `List<InventoryItem>`. It's a compile-time fix; the data was
never actually null. It's also more explicit about which column goes into which property.

Notes: [InventoryRepository.kt, the lambda](notes/main/inventory/InventoryRepository.kt.md#mapping-each-row-the-lambda)
</details>

### What does `data class` give you, and does it make the class immutable?

<details>
<summary>Answer</summary>

It generates `equals`, `hashCode`, `toString`, `copy` and `componentN` from the constructor
properties. So `InventoryItem("widget", 5) == InventoryItem("widget", 5)` is `true` (value
equality, because `==` calls `equals`). It does **not** make the class immutable: `val` does.
`copy` returns a new object and leaves the original unchanged.

Notes: [InventoryItem.kt](notes/main/inventory/InventoryItem.kt.md)
</details>

## Spring

### Two requests arrive at the same time. How many controller objects are there?

<details>
<summary>Answer</summary>

One. Beans are singletons, built once at startup through constructor injection, bottom-up
(repository → service → controller). Every request thread shares the same object. That's safe
because the beans are **stateless**: they only hold `val` references to other beans, never
per-request data.

Notes: [InventoryController.kt](notes/main/inventory/InventoryController.kt.md)
</details>

### What happens if you remove `@Service` from `InventoryService`?

<details>
<summary>Answer</summary>

The app fails at **startup**, not on the first request: the controller's constructor needs an
`InventoryService` bean and none exists ("required a bean of type ... that could not be found").
Beans are created at startup, so missing dependencies fail fast.

Notes: [InventoryService.kt](notes/main/inventory/InventoryService.kt.md)
</details>

### How does a `SkuNotFoundException` thrown in the service become a 404?

<details>
<summary>Answer</summary>

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

Notes: [InventoryExceptionHandler.kt](notes/main/inventory/error/InventoryExceptionHandler.kt.md)
</details>

### When does each kind of mistake surface: compile time, startup, or the first request?

<details>
<summary>Answer</summary>

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

Notes: [InventoryController.kt](notes/main/inventory/InventoryController.kt.md)
</details>

### How would you deploy to a different database without changing any files?

<details>
<summary>Answer</summary>

Set environment variables such as `SPRING_DATASOURCE_URL`. Spring layers property sources, and
environment variables override `application.yml`, so the repo never holds per-environment values
or passwords. Whatever launches the process (Kubernetes, ECS, `docker run -e`) sets them.
Command-line arguments beat environment variables, which beat the yml.

Notes: [application.yml](notes/main/resources/application.yml.md)
</details>

## Database and migrations

### You need a new `created_at` column. What do you do?

<details>
<summary>Answer</summary>

Add a new migration, `V2__add_created_at.sql`, with `ALTER TABLE`. Migrations are a chain of
changes, like commits; the schema is the whole chain. Editing `V1` after it has run makes Flyway
fail at startup with a checksum mismatch.

Notes: [V1__create_inventory.sql](notes/main/resources/db/migration/V1__create_inventory.sql.md)
</details>

## Testing

### How do you know `GET /inventory/{skuId}` works? What do the tests actually cover?

<details>
<summary>Answer</summary>

Two levels, each against a real Postgres (Testcontainers):

- **Repository tests** (`@JdbcTest`, each test rolled back): call `findBySkuId` directly. Found,
  missing → `null`, zero stock is still found, and `WIDGET` ≠ `widget` (case-sensitive).
- **Controller tests** (`@SpringBootTest` + MockMvc): a real request through path matching,
  controller, service, repository and the exception handler. Exact JSON with
  `JsonCompareMode.STRICT` on 200; status, `text/plain` and the exact message on 404.

The zero-stock and case-sensitivity cases are tested at both levels on purpose: the repository
tests prove the SQL, and the controller tests prove nothing above the SQL changes the answer (for
example, a service that treated `quantity == 0` as "not found").

Notes: [InventoryControllerTest.kt](notes/test/inventory/InventoryControllerTest.kt.md),
[InventoryRepositoryTest.kt](notes/test/inventory/InventoryRepositoryTest.kt.md)
</details>

### If `findBySkuId` lost its `WHERE` clause, which repository tests would fail?

<details>
<summary>Answer</summary>

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

Notes: [InventoryRepositoryTest.kt](notes/test/inventory/InventoryRepositoryTest.kt.md)
</details>
