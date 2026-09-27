# InventoryControllerTest.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryControllerTest.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryControllerTest.kt) · Last synced with code: 2026-09-27

## Role

Tests `GET /inventory` and `GET /inventory/{skuId}` through the whole stack: MockMvc →
controller → service → repository → real Postgres. It checks exactly what the contract
specifies: the status code, the `Content-Type` and the exact body (JSON on success, plain text
on `404`).

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
up for real, and it checks the whole path from the empty query to the plain-text response.

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
thread, but the concurrency tests in the oversell test plan will send requests from many
threads at once. Starting with explicit cleanup keeps all the web tests using the same pattern.
Cleaning up *before* each test instead of after means a test that crashes halfway can't break
the next one.

The `insert` helper is the same as in
[InventoryRepositoryTest.kt](InventoryRepositoryTest.kt.md).

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
