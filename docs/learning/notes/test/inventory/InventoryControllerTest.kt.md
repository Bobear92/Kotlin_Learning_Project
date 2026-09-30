# InventoryControllerTest.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryControllerTest.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryControllerTest.kt) · Last synced with code: 2026-09-29

## Role

Tests `GET /inventory`, `GET /inventory/{skuId}`, `POST /inventory/{skuId}` and
`POST /inventory/{skuId}/purchase` through the whole stack: MockMvc → controller → service →
repository → real Postgres. It checks exactly what the contract specifies: the status code, the
`Content-Type` and the exact body (JSON on success, plain text on `400`/`404`). For both `POST`s
it also reads the table afterwards, so a rejected request is proven to have changed nothing.

## Walkthrough

### Full context plus MockMvc

```kotlin
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class InventoryControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jdbcClient: JdbcClient,
) {
```

**Spring: `@SpringBootTest` + `@AutoConfigureMockMvc`.** `@SpringBootTest` starts the full
application context (see [InventoryApplicationTests.kt](../InventoryApplicationTests.kt.md)).
`@AutoConfigureMockMvc` adds a `MockMvc` bean. `MockMvc` sends a fake HTTP request straight into
Spring MVC's `DispatcherServlet`, in the same JVM and on the same thread, with no real server or
network port. Everything after that is real: request mapping, the controller, Jackson and the
database.

**Why full stack rather than `@WebMvcTest`:** `@WebMvcTest` starts only the web layer and needs
the service replaced by a mock. For a read-only endpoint with no branching, the full path to a
real database is more convincing and needs no mocking library. When the error paths arrive
(`400`/`404`), a `@WebMvcTest` with a mocked service may become the better fit. For the `404` of
`GET /inventory/{skuId}` we stayed full stack: "missing" just means "no row", which is easy to set
up for real, and it checks the whole path from the empty query to the plain-text response. The
`POST` tests stay full stack too: several of its `400`s come from Spring and Jackson *before* the
controller runs (unreadable JSON, wrong content type, `@Valid`), and one comes from the database's
answer (overflow), so a mocked service would skip exactly the parts being tested.

**Kotlin:** `@Autowired constructor` is explained in
[InventoryRepositoryTest.kt](InventoryRepositoryTest.kt.md).

**Spring: context caching.** This class uses a different set of annotations from
`InventoryApplicationTests`, so Spring caches a separate context for it, with its own Postgres
container (see [TestcontainersConfiguration.kt](../support/TestcontainersConfiguration.kt.md)).

### A clean table before every test

```kotlin
/** Starts every test from an empty table, since all tests in this context share one database. */
@BeforeEach
fun clearInventory() {
    jdbcClient.sql("DELETE FROM inventory").update()
}
```

**Spring / JUnit: `@BeforeEach`.** JUnit calls this before each `@Test` method.

**Why explicit cleanup instead of `@Transactional` rollback** (which `InventoryRepositoryTest`
uses): rollback only covers work done on the test's own thread. MockMvc happens to run on that
thread, but tests that write from many threads at once can't rely on it (see
[InventoryConcurrencyTest.kt](InventoryConcurrencyTest.kt.md)). Explicit cleanup keeps every
full-context test on the same pattern.
Cleaning up *before* each test instead of after means a test that crashes halfway can't break
the next one.

The `insert` and `quantityOf` helpers are the same as in
[InventoryRepositoryTest.kt](InventoryRepositoryTest.kt.md).

### Helpers for POST

```kotlin
private fun postInventory(
    skuId: String,
    body: String,
    mediaType: MediaType = MediaType.APPLICATION_JSON,
): ResultActionsDsl =
    mockMvc.post("/inventory/$skuId") {
        contentType = mediaType
        content = body
    }

private fun postPurchase(
    skuId: String,
    body: String,
    mediaType: MediaType = MediaType.APPLICATION_JSON,
): ResultActionsDsl =
    mockMvc.post("/inventory/$skuId/purchase") {
        contentType = mediaType
        content = body
    }

private fun ResultActionsDsl.andExpectBadRequest(message: String) = andExpect {
    status { isBadRequest() }
    content {
        contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
        string(message)
    }
}
```

**Kotlin: default arguments.** `mediaType: MediaType = MediaType.APPLICATION_JSON` gives the
parameter a default, so most calls are `postInventory("widget", body)` and only the content-type
test passes a third argument. Java has no default arguments; you'd write an overload
`postInventory(skuId, body)` that calls the three-argument one. `postPurchase` is the same helper
for the purchase URL.

**Kotlin: the request-building lambda.** `mockMvc.post(url) { ... }` is another extension
function, like `get`. Its lambda has a request builder as its receiver, so `contentType = ...`
and `content = ...` set that builder's properties. The body is sent exactly as written. Nothing
parses it on the way out, which is how the tests can send broken JSON.

**Kotlin: writing your own extension function.** `ResultActionsDsl.andExpectBadRequest` is
declared here, in the test class, and it's `private`, so it exists only inside this class. It lets
every `400` test say `postInventory(...).andExpectBadRequest("...")` in one line. Inside it,
`andExpect { ... }` is called on `this`, the `ResultActionsDsl` it was called on. The return type
is inferred from `andExpect`, which returns the same `ResultActionsDsl`, so calls can chain.

### Empty inventory returns `[]`

```kotlin
/** Contract: "If no SKUs exist, returns an empty array." */
@Test
fun `GET inventory returns an empty JSON array when there are no SKUs`() {
    mockMvc.get("/inventory").andExpect {
        status { isOk() }
        content {
            contentType(MediaType.APPLICATION_JSON)
            json("[]", JsonCompareMode.STRICT)
        }
    }
}
```

**Kotlin: extension functions.** `MockMvc` is a Java class with no `get` method. The import
`org.springframework.test.web.servlet.get` brings in an **extension function** that Spring ships
for Kotlin. It's declared outside the class as `fun MockMvc.get(...)`, but it's called as if it
were a member. Under the hood it's a static method that takes the `MockMvc` as its first
argument, so it can't reach private members. Java can't do this; you'd call a static helper
yourself.

**Kotlin: the MockMvc DSL.** `andExpect { ... }` takes a *lambda with receiver*: inside the braces,
`this` is a matcher builder, so `status { }` and `content { }` are its methods, called without
a prefix. That's the same mechanism as Gradle's `dependencies { }` (see
[build.gradle.kts](../../build.gradle.kts.md)). The Java version of the same test:

```java
mockMvc.perform(get("/inventory"))
       .andExpect(status().isOk())
       .andExpect(content().contentType(MediaType.APPLICATION_JSON))
       .andExpect(content().json("[]", JsonCompareMode.STRICT));
```

**Spring: `JsonCompareMode.STRICT`.** `json(...)` compares JSON structure, not text, so spaces
and property order inside an object don't matter. `STRICT` also fails on extra properties and
on array elements in a different order. The default, `LENIENT`, allows both, which would let an
unexpected field or an ordering bug slip through.

**Contract:** status `200`, content type `application/json`, body `[]`.

### Rows become JSON objects, in SKU order

```kotlin
@Test
fun `GET inventory returns every SKU with its quantity`() {
    insert("widget", 5)
    insert("CW-XYCS-BM-01", 10)
    insert("sold-out", 0)

    mockMvc.get("/inventory").andExpect {
        status { isOk() }
        content {
            contentType(MediaType.APPLICATION_JSON)
            json(
                """
                [
                  {"skuId": "CW-XYCS-BM-01", "quantity": 10},
                  {"skuId": "sold-out", "quantity": 0},
                  {"skuId": "widget", "quantity": 5}
                ]
                """,
                JsonCompareMode.STRICT,
            )
        }
    }
}
```

**Kotlin: raw strings.** `"""..."""` is a raw string: quotes inside need no escaping and line
breaks are kept. It's like Java's text block `"""`, but Kotlin doesn't strip the indentation
unless you call `.trimIndent()`. Here the extra spaces don't matter, because JSON comparison
ignores whitespace.

**Why this one test covers so much:** `STRICT` checks the property names `skuId` and `quantity`
(so a renamed Kotlin property fails here), that values are numbers rather than strings, that no
extra fields appear, the order, and that a zero-stock SKU is included.

**Contract:** a JSON array of `InventoryItem` objects, `{"skuId": string, "quantity": integer}`.

**Renaming a property breaks exactly one test.** If `skuId` became `sku` in `InventoryItem`
(and everything still compiled), the repository tests would pass (they compare objects, and the
constructor takes arguments by position), and the empty-array test would pass (`[]` has no fields).
Only `GET inventory returns every SKU with its quantity` fails, because Jackson would write
`"sku"` and STRICT expects `"skuId"`. This test is what keeps the JSON in line with the contract.

### One SKU as a JSON object

```kotlin
/** Contract: 200 with a single InventoryItem as JSON. */
@Test
fun `GET inventory by SKU returns the item`() {
    insert("CW-XYCS-BM-01", 10)
    insert("widget", 5)

    mockMvc.get("/inventory/CW-XYCS-BM-01").andExpect {
        status { isOk() }
        content {
            contentType(MediaType.APPLICATION_JSON)
            json("""{"skuId": "CW-XYCS-BM-01", "quantity": 10}""", JsonCompareMode.STRICT)
        }
    }
}
```

**Why the contract's example SKU:** `CW-XYCS-BM-01` is the example in inventory.yaml. Dashes
are ordinary URL path characters, so it reaches the controller unchanged as the `{skuId}` path
variable. The second row makes sure the endpoint returns the SKU that was asked for, not just
the only one in the table.

**Contract:** `200`, `application/json`, a single `InventoryItem` object (not an array).

### Zero stock is a 200

```kotlin
/** A sold-out SKU exists, so it is a 200 with quantity 0, not a 404. */
@Test
fun `GET inventory by SKU returns zero stock as 200`() {
    insert("sold-out", 0)

    mockMvc.get("/inventory/sold-out").andExpect {
        status { isOk() }
        content {
            contentType(MediaType.APPLICATION_JSON)
            json("""{"skuId": "sold-out", "quantity": 0}""", JsonCompareMode.STRICT)
        }
    }
}
```

**Why:** the contract returns 404 only if the SKU "does not exist". A sold-out SKU exists. An
implementation that treated `quantity == 0` as missing would fail here.

### A missing SKU is a plain-text 404

```kotlin
// Why: contentTypeCompatibleWith ignores parameters, so "text/plain;charset=UTF-8" also passes.
/** Contract: 404 with a `text/plain` Error string, not JSON. Message text agreed with Robert. */
@Test
fun `GET inventory by SKU returns 404 text for a missing SKU`() {
    mockMvc.get("/inventory/missing").andExpect {
        status { isNotFound() }
        content {
            contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
            string("SKU not found: missing")
        }
    }
}
```

**Spring: `contentType` vs `contentTypeCompatibleWith`.** `contentType(X)` requires an exact
match, *including parameters*. A `text/plain` response can carry a character set,
`text/plain;charset=UTF-8`, and that is still `text/plain`. So the test uses
`contentTypeCompatibleWith`, which compares only the type and subtype. Today the app sends plain
`text/plain` with no charset (checked with `curl` on 2026-09-27): the handler sets the Content-Type
itself, and the message converter only adds its default charset when no Content-Type is set. The
looser check keeps the test passing if a `charset=UTF-8` is added later (a TODO item). The JSON tests keep the
exact `contentType(MediaType.APPLICATION_JSON)`, because Spring doesn't add a charset there.

**Spring: `string(...)`.** It compares the whole body as text, exactly. `json(...)` would be
wrong here: the contract's `Error` schema is a bare string, not a JSON document.

**Contract:** `404`, `text/plain`, an `Error` string. The message `SKU not found: <skuId>` was
Robert's choice on 2026-09-27, because the contract gives no example for the 404.

**Why this is the test that shapes the error handling:** an exception that escapes a controller
with no handler becomes a `500`, and `ResponseStatusException` gives no `text/plain` body (in MockMvc
the body is empty; in the running app Boot renders a JSON or HTML error page). Only a
handler that sets the status, the content type and the body passes all three checks.

### Case-sensitive at the HTTP level too

```kotlin
/** SKU ids match case-sensitively, so a SKU that differs only in case is not found. */
@Test
fun `GET inventory by SKU is case-sensitive`() {
    insert("widget", 5)

    mockMvc.get("/inventory/WIDGET").andExpect {
        status { isNotFound() }
        content {
            contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
            string("SKU not found: WIDGET")
        }
    }
}
```

**Why repeat the repository's case test here:** the repository test proves the query is exact.
This one proves that nothing between HTTP and the query changes the SKU (lowercasing it in the
controller, say), and that the error message echoes the SKU exactly as it was requested.

### POST creates a new SKU

```kotlin
/** Contract: a new SKU "is created with the given quantity"; 200 (not 201) with the InventoryItem as JSON. */
@Test
fun `POST inventory creates a new SKU`() {
    postInventory("CW-XYCS-BM-01", """{"quantity": 10}""").andExpect {
        status { isOk() }
        content {
            contentType(MediaType.APPLICATION_JSON)
            json("""{"skuId": "CW-XYCS-BM-01", "quantity": 10}""", JsonCompareMode.STRICT)
        }
    }
    assertEquals(10, quantityOf("CW-XYCS-BM-01"))
}
```

**Why check the table as well as the response:** a controller that echoed the request back
could fake the response. Reading the row proves the stock was stored.

**Contract:** the `post` operation lists only `200` for success. `201 Created` is the usual REST
answer for "created", but a `201` would break the contract, so the test pins `isOk()`.

### POST adds to an existing SKU

```kotlin
@Test
fun `POST inventory adds to an existing SKU`() {
    insert("widget", 5)

    postInventory("widget", """{"quantity": 3}""").andExpect {
        ...
            json("""{"skuId": "widget", "quantity": 8}""", JsonCompareMode.STRICT)
        ...
    }
    assertEquals(8, quantityOf("widget"))
}
```

**Contract:** "If it already exists, the quantity is added to the current stock. Returns the
updated inventory state." The body holds the new total (8), not the amount posted (3).

### Quantities below 1

```kotlin
/** Contract: `quantity` has `minimum: 1`, so 0 and negatives are a 400 and stock is untouched. */
@ParameterizedTest(name = "quantity {0}")
@ValueSource(ints = [0, -5])
fun `POST inventory rejects a quantity below 1`(quantity: Int) {
    insert("widget", 5)

    postInventory("widget", """{"quantity": $quantity}""")
        .andExpectBadRequest("quantity must be at least 1")
    assertEquals(5, quantityOf("widget"))
}
```

**JUnit: parameterized tests.** `@ParameterizedTest` runs the method once for each value from
the source. `@ValueSource(ints = [0, -5])` supplies the values, and each one arrives as the
`quantity` parameter. `name = "quantity {0}"` sets the display name, so the report lists
`quantity 0` and `quantity -5` as separate results.

**Kotlin: array literals in annotations.** `[0, -5]` is Kotlin's syntax for an array argument
inside an annotation. Java writes it `{0, -5}`. Outside annotations you'd write `intArrayOf(0, -5)`.

**Kotlin: a template inside a raw string.** `"""{"quantity": $quantity}"""` works because raw
strings still expand `$name` templates (see
[SkuNotFoundException.kt](../../main/inventory/error/SkuNotFoundException.kt.md)).

**Why `-5` as well as `0`:** `0` is the boundary. `-5` catches a check written as
`quantity != 0`. Without it, a negative "receive" would quietly remove stock.

**Contract:** `InventoryQuantity.quantity` has `minimum: 1`, and the `400` is `text/plain`. The
message was agreed with Robert on 2026-09-28.

### A rejected POST creates nothing

```kotlin
@Test
fun `POST inventory with an invalid quantity does not create the SKU`() {
    postInventory("new-sku", """{"quantity": 0}""")
        .andExpectBadRequest("quantity must be at least 1")
    assertNull(quantityOf("new-sku"))
}
```

**Why:** an implementation could insert the SKU first and only then fail on the quantity,
leaving a `new-sku` row with 0 stock behind. This test fails unless validation happens
before anything is written.

### Every unreadable body is a 400

```kotlin
// Why: strict JSON (README "Contract gaps" decision, 2026-09-28). Jackson's defaults would turn
// "5" into 5 and 1.5 into 1; stock counts must never be guessed.
/** Any body that isn't `{"quantity": <whole number that fits in Int>}` is a 400 text/plain. */
@ParameterizedTest(name = "body [{0}]")
@ValueSource(
    strings = [
        "",
        "{",
        "null",
        "[5]",
        "{}",
        """{"quantity": null}""",
        """{"quantity": "5"}""",
        """{"quantity": 1.5}""",
        """{"quantity": 3000000000}""",
    ],
)
fun `POST inventory rejects an unreadable body`(body: String) {
    insert("widget", 5)

    postInventory("widget", body).andExpectBadRequest("Invalid request body")
    assertEquals(5, quantityOf("widget"))
}
```

Each body fails in a different way:

| Body | What goes wrong |
| --- | --- |
| `""` | no body at all; the contract says `required: true` |
| `{` | not valid JSON |
| `null` | valid JSON, but not an object; a required `@RequestBody` can't be `null` |
| `[5]` | an array, not an object |
| `{}` | `quantity` is missing; the contract says `required: [quantity]` |
| `{"quantity": null}` | `quantity` is a non-null `Int` in Kotlin |
| `{"quantity": "5"}` | a string, not an integer; lenient Jackson would coerce it |
| `{"quantity": 1.5}` | not a whole number; lenient Jackson would truncate it to 1 |
| `{"quantity": 3000000000}` | a whole number, but too big for an `Int` |

**Spring:** each of these fails while Spring is still turning the body into the controller's
parameter, before the controller runs. Spring reports them all as one exception type,
`HttpMessageNotReadableException`, which is why they share one message.

**Why `"5"` and `1.5` matter most:** these two rows fail only because we chose strict mode.
With Jackson's lenient defaults, `1.5` would quietly receive 1 unit, and nobody would
notice. That's the argument for the "strict" decision in the README.

**Why `3000000000`:** it's bigger than `Int.MAX_VALUE` (2,147,483,647), so it can't be read into
an `Int`. The overflow test below is different: there each number fits, but their sum
doesn't.

### Wrong content type is a 400, not a 415

```kotlin
// Why: Spring's default is 415, which the contract doesn't list (README "Contract gaps" decision, 2026-09-28).
/** The contract only accepts `application/json`; anything else is a 400 text/plain. */
@Test
fun `POST inventory rejects a non-JSON content type`() {
    insert("widget", 5)

    postInventory("widget", "5", MediaType.TEXT_PLAIN)
        .andExpectBadRequest("Content-Type must be application/json")
    assertEquals(5, quantityOf("widget"))
}
```

**Spring:** `@RequestBody` looks for a message converter that can read the request's
`Content-Type` into `InventoryQuantity`. Only the JSON converter can, so a `text/plain` request
fails with `HttpMediaTypeNotSupportedException`. Spring's default answer is
`415 Unsupported Media Type`. That's correct HTTP, but the contract lists only `400`.

### Overflow is a 400 and changes nothing

```kotlin
/** Stock is an Int; a total past Int.MAX_VALUE is an invalid request (400), not a 500, and nothing changes. */
@Test
fun `POST inventory rejects an add that would overflow the stock`() {
    insert("widget", Int.MAX_VALUE - 1)

    postInventory("widget", """{"quantity": 2}""")
        .andExpectBadRequest("Quantity would exceed the maximum stock")
    assertEquals(Int.MAX_VALUE - 1, quantityOf("widget"))
}
```

**Why:** both numbers are valid on their own; only the sum is too big. The repository catches
this in its SQL (see [InventoryRepositoryTest.kt](InventoryRepositoryTest.kt.md)). This test
checks that the repository's "no row came back" answer becomes a plain-text `400`, not a `500`.

### Case-sensitive POST

```kotlin
/** SKU ids match case-sensitively, so posting to `WIDGET` creates a separate SKU. */
@Test
fun `POST inventory is case-sensitive`() {
    insert("widget", 5)

    postInventory("WIDGET", """{"quantity": 2}""").andExpect {
        status { isOk() }
        content { json("""{"skuId": "WIDGET", "quantity": 2}""", JsonCompareMode.STRICT) }
    }
    assertEquals(5, quantityOf("widget"))
}
```

**Why:** this is the "exact match" decision (README, 2026-09-28) checked over HTTP. `WIDGET`
becomes its own SKU, and `widget` keeps its 5.

### Purchase: the success paths

```kotlin
/** Contract: 200 with "the remaining inventory for the SKU" as an InventoryItem. */
@Test
fun `POST purchase deducts stock and returns the remaining inventory`() {
    insert("widget", 5)

    postPurchase("widget", """{"quantity": 2}""").andExpect {
        status { isOk() }
        content {
            contentType(MediaType.APPLICATION_JSON)
            json("""{"skuId": "widget", "quantity": 3}""", JsonCompareMode.STRICT)
        }
    }
    assertEquals(3, quantityOf("widget"))
}

/** Buying the whole stock is allowed and leaves the SKU at 0, still in inventory. */
@Test
fun `POST purchase can buy the last unit`() {
    insert("widget", 5)

    postPurchase("widget", """{"quantity": 5}""").andExpect {
        status { isOk() }
        content { json("""{"skuId": "widget", "quantity": 0}""", JsonCompareMode.STRICT) }
    }
    assertEquals(0, quantityOf("widget"))
}
```

**Contract:** the response is the *remaining* stock (3), not the quantity bought (2). The
`quantityOf` check shows the database agrees with what the client was told.

**Why the last-unit test:** it's the boundary. `quantity >= :quantity` must allow buying exactly
what's on hand; `>` would refuse it. `quantityOf` returning `0` (not `null`) also proves the row
stays, so `GET /inventory/widget` keeps working for a sold-out SKU (the "zero-stock SKUs are
listed" decision).

### Purchase: 400 for insufficient stock, 404 for a missing SKU

```kotlin
/** Contract: "Returns 400 if there is insufficient inventory"; no partial fill. Message text agreed with Robert. */
@Test
fun `POST purchase returns 400 text when stock is insufficient`() {
    insert("widget", 5)

    postPurchase("widget", """{"quantity": 6}""")
        .andExpectBadRequest("Insufficient inventory for widget")
    assertEquals(5, quantityOf("widget"))
}

/** Contract: "Returns 404 if the SKU does not exist", as `text/plain`; the SKU is not created. */
@Test
fun `POST purchase returns 404 text for a missing SKU`() {
    postPurchase("missing", """{"quantity": 1}""").andExpect {
        status { isNotFound() }
        content {
            contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
            string("SKU not found: missing")
        }
    }
    assertNull(quantityOf("missing"))
}

/** SKU ids match case-sensitively, so buying `WIDGET` is a 404 and `widget` is untouched. */
@Test
fun `POST purchase is case-sensitive`() {
    insert("widget", 5)

    postPurchase("WIDGET", """{"quantity": 1}""").andExpect {
        status { isNotFound() }
        content { string("SKU not found: WIDGET") }
    }
    assertEquals(5, quantityOf("widget"))
}
```

**Contract: no partial fill.** "Only allowed if the current stock is sufficient to fulfill the
*full* requested quantity." Asking for 6 of 5 must not sell 5; the stock stays at 5.

**Why the 404 reuses `SkuNotFoundException`'s text:** a missing SKU means the same thing on every
endpoint, so the purchase gets the same `SKU not found: <skuId>` body as
`GET /inventory/{skuId}`, from the same handler. The message for insufficient stock,
`Insufficient inventory for <skuId>`, was Robert's choice (2026-09-29). It follows the contract's
example `Insufficient inventory` and names the SKU, like the 404 does.

**Why `assertNull` after the 404:** unlike `POST /inventory/{skuId}`, a purchase must never
create a SKU. A buggy upsert-style purchase would.

### Purchase: invalid requests reuse the existing handlers

```kotlin
@ParameterizedTest(name = "quantity {0}")
@ValueSource(ints = [0, -5])
fun `POST purchase rejects a quantity below 1`(quantity: Int) { ... }

// Why: Spring validates the body before the controller runs, so an invalid request is a 400
// even when the SKU is also missing (decided with Robert, 2026-09-29).
/** An invalid body for a missing SKU is a 400, not a 404. */
@Test
fun `POST purchase with an invalid quantity for a missing SKU is a 400`() {
    postPurchase("missing", """{"quantity": 0}""")
        .andExpectBadRequest("quantity must be at least 1")
}

@ParameterizedTest(name = "body [{0}]")
@ValueSource(strings = [ /* the same nine bodies as for POST inventory */ ])
fun `POST purchase rejects an unreadable body`(body: String) { ... }

@Test
fun `POST purchase rejects a non-JSON content type`() { ... }
```

These are the `POST /inventory/{skuId}` rejection tests pointed at the purchase URL, with the same
bodies and messages. They should pass with **no new handler code**: `@RestControllerAdvice`
applies to every controller method, so the `MethodArgumentNotValidException`,
`HttpMessageNotReadableException` and `HttpMediaTypeNotSupportedException` handlers already cover
the new endpoint as soon as it uses `@Valid @RequestBody InventoryQuantity`. The tests prove it
rather than assuming it.

**Spring: order of checks.** Spring reads the body (Jackson) and validates it (`@Valid`) while
resolving the controller method's arguments, so before the method runs. A request that is both
invalid and for a missing SKU never reaches the service's lookup, so it gets the `400`. The
"missing SKU" test pins that order.

## Check your understanding

- Does this test start Tomcat or open a network port? What does `mockMvc.get(...)` actually call?
- If `InventoryItem` gained a third property, `lastUpdated`, which assertion would fail, and
  would it still fail with `LENIENT`?
- Why is the cleanup a `DELETE` in `@BeforeEach` here, when the repository test needs none?
- Why does the `404` test use `contentTypeCompatibleWith` while the `200` tests use
  `contentType`?
- Which test fails if the service treats `quantity == 0` as "not found"? What status would it
  see?
- What would the `404` test report if the exception escaped with no handler at all?
- Which `POST` tests would still pass if the controller returned the request body's quantity
  instead of the stored total?
- Name two bodies in the unreadable-body list that would get a `200` if Jackson were lenient.
  What quantity would be stored for each?
- Why does the overflow test use `Int.MAX_VALUE - 1` plus `2`, and not a body of
  `{"quantity": 3000000000}`?
- `postInventory` has a default argument. What would the Java version of that helper need?
- Where in the request path does each kind of `400` come from: before the controller, in the
  controller's validation, or after the database answers?
- The purchase's invalid-body tests pass without any new handler. Why? What would you have to
  forget in the controller for them to fail?
- `{"quantity": 0}` to a missing SKU gets a `400`, not a `404`. At which step of the request does
  Spring decide that?
- Why does the purchase `404` test check that the SKU wasn't created, when the `GET` `404` test
  doesn't?
