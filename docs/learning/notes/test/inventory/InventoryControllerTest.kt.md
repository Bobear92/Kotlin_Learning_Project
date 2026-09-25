# InventoryControllerTest.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryControllerTest.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryControllerTest.kt) · Last synced with code: 2026-09-25

## Role

Tests `GET /inventory` through the whole stack: MockMvc → controller → service → repository →
real Postgres. It checks exactly what the contract specifies: the status code, the
`Content-Type` and the exact JSON body.

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
(`400`/`404`), a `@WebMvcTest` with a mocked service may become the better fit.

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

## Check your understanding

- Does this test start Tomcat or open a network port? What does `mockMvc.get(...)` actually call?
- If `InventoryItem` gained a third property, `lastUpdated`, which assertion would fail, and
  would it still fail with `LENIENT`?
- Why is the cleanup a `DELETE` in `@BeforeEach` here, when the repository test needs none?
