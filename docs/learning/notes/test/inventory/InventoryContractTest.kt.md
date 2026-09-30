# InventoryContractTest.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryContractTest.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/inventory/InventoryContractTest.kt) · Last synced with code: 2026-09-30

## Role

The **contract-validation test** promised by the "Controllers" decision in the README: because the
controllers are hand-written rather than generated from `inventory.yaml`, something has to prove
they still match it. This class sends one request for every response the YAML declares (all four
endpoints, every status) and lets a validator compare the real response with the YAML: is the
status declared, is the content type allowed, does the body fit the schema?

It complements [InventoryControllerTest.kt](InventoryControllerTest.kt.md). That test pins the
*exact* bodies (`SKU not found: widget`); this one checks the *shape* against the YAML itself, so
if someone edits the YAML or the code and they drift apart, this is where it shows.

## Walkthrough

### Two validators

```kotlin
/** Validates the request and the response; for requests that follow the contract. */
private val validator = OpenApiInteractionValidator.createFor(strictContract()).build()

// Why: the 400 tests send invalid requests on purpose. Only the response is on trial there,
// so request errors are ignored instead of failing the test for doing its job.
/** Validates the response only; for requests that break the contract on purpose. */
private val responseOnlyValidator = OpenApiInteractionValidator.createFor(strictContract())
    .withLevelResolver(
        LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build()
    )
    .build()
```

**The library:** Atlassian's `openapi-request-validator` (see
[build.gradle.kts](../../build.gradle.kts.md#test-dependencies)). `OpenApiInteractionValidator`
validates an *interaction*: a request and its response, both against the spec.

**Why two:** `{"quantity": 0}` breaks the request schema (`minimum: 1`). The full validator would
report that as an error, and the test would fail even though the app answered correctly with a
400. Every validation message has a key (`validation.request.body.schema.minimum`, ...) and a level;
setting the `validation.request` prefix to `IGNORE` drops all request checks. Verified on
2026-09-30: with the full validator, exactly the two invalid-quantity tests fail.

**Kotlin: calling a Java builder.** `createFor(...)` returns a `Builder`; each `with...` returns the
builder again, and `build()` produces the validator. That's the Java builder pattern, called from
Kotlin exactly as written in Java (compare `SimpleResponse.Builder` below).

**Kotlin: property initializers.** Both validators are `private val`s initialized where they're
declared. JUnit makes a new instance of the test class per test, so they're rebuilt for each test.
Parsing a tiny YAML costs milliseconds, so that's fine.

### Test data and a POST helper

```kotlin
/** Starts every test from an empty table, since all tests in this context share one database. */
@BeforeEach
fun clearInventory() {
    jdbcClient.sql("DELETE FROM inventory").update()
}

private fun insert(skuId: String, quantity: Int) {
    jdbcClient.sql("INSERT INTO inventory (sku_id, quantity) VALUES (?, ?)")
        .params(skuId, quantity)
        .update()
}

private fun post(path: String, body: String): ResultActionsDsl =
    mockMvc.post(path) {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }
```

The same helpers as in [InventoryControllerTest.kt](InventoryControllerTest.kt.md#helpers-for-post):
a clean table per test (`@SpringBootTest` has no rollback), rows inserted straight into the
database (`.params(...)` fills the positional `?`s in order), and the MockMvc Kotlin DSL's
`post(path) { ... }`, whose block sets the request's content type and body.

### One test per declared response

```kotlin
/** 404 with a text/plain error. */
@Test
fun `GET inventory by SKU for a missing SKU matches the contract`() {
    mockMvc.get("/inventory/missing").andExpect {
        status { isNotFound() }
        match(openApi().isValid(validator))
    }
}
```

The ten app tests follow this pattern: set up the data, make the request, then assert two
things.

1. `status { ... }` pins **which** response this is. Without it, a bug that turned the 404 into a
   200 would still pass, because a 200 is also declared in the YAML.
2. `match(openApi().isValid(validator))` checks it against the contract.

**Spring: `ResultMatcher`.** MockMvc's assertions are `ResultMatcher`s: objects with a
`match(result)` method that throws if something's wrong. `openApi().isValid(validator)` is one
built by the library. The Kotlin DSL's `match(...)` plugs any `ResultMatcher` into the
`andExpect { }` block, next to the built-in `status { }` and `content { }` (see
[InventoryControllerTest.kt](InventoryControllerTest.kt.md)).

**Coverage:** GET list (with items, and empty), GET one (200, 404), POST receive (200, 400), and
POST purchase (200, 400 for insufficient stock, 400 for an invalid body, 404). That's every
response in `inventory.yaml`.

### Proving the validator can fail

```kotlin
// Why: each check asserts the exact validation key, not just "has errors", so a response
// rejected for some other reason can't make it pass.
private fun assertRejected(expectedKey: String, response: SimpleResponse) {
    val report = validator.validateResponse("/inventory/widget", Request.Method.GET, response)
    val keys = report.messages.map { it.key }
    assertTrue(expectedKey in keys, "expected $expectedKey, got ${report.messages}")
}
```

The same idea as [NegativeControlTest.kt](NegativeControlTest.kt.md): a validator that never
complains would make every test above pass. So seven tests hand the validator responses built by
hand (`SimpleResponse`, no app involved): one correct item as a control, then six broken ones.

| Response | Expected key |
| --- | --- |
| `{"skuId": "widget"}` | `...body.schema.required` (only because we made the schema strict) |
| an extra `price` field | `...body.schema.additionalProperties` |
| `quantity: -1` | `...body.schema.minimum` (`minimum: 0` in the YAML) |
| a 200 as `text/plain` | `...contentType.notAllowed` |
| a 404 as `application/json` | `...contentType.notAllowed` |
| a `500` | `...status.unknown` (the YAML lists no 500; Spring's default 415 would fail the same way) |

```kotlin
private fun jsonItem(body: String): SimpleResponse =
    SimpleResponse.Builder.ok().withContentType("application/json").withBody(body).build()
...
/** Strict schema: a missing field is an error (the YAML alone would allow it). */
@Test
fun `the validator rejects an item with a missing field`() {
    assertRejected("validation.response.body.schema.required", jsonItem("""{"skuId": "widget"}"""))
}
```

**The library: `SimpleResponse` and `validateResponse`.** `SimpleResponse` is the library's own
response object, built with a Java builder (`ok()` sets 200). `validator.validateResponse(path,
method, response)` checks it as if the app had answered `GET /inventory/widget` with it, and returns
a `ValidationReport`: `report.messages` lists what's wrong (each with a `key` and a text), and
`report.hasErrors()` is true if any message is at error level. The control test asserts
`hasErrors()` is false; the others use `assertRejected`.

**Why the exact key:** a first version only asserted `report.hasErrors()`. That passes for *any*
error, including the wrong one. Asserting the key ties each test to the one rule it's about.

**Kotlin: `in` on a list.** `expectedKey in keys` calls `keys.contains(expectedKey)`. `in` is
Kotlin's operator for `contains`, the same `in` that works with ranges (`x in 1..10`).

### Making `InventoryItem` strict

```kotlin
private companion object {
    // Why: the YAML marks no InventoryItem field as required, so a literal check would accept `{}`.
    // Both are required here (decided 2026-09-30); the YAML itself is unchanged.
    /** inventory.yaml as parsed from the classpath, with both `InventoryItem` fields required. */
    fun strictContract(): OpenAPI {
        val yaml = checkNotNull(InventoryContractTest::class.java.getResource("/openapi/inventory.yaml")) {
            "inventory.yaml is not on the classpath"
        }.readText()
        val contract = checkNotNull(OpenAPIV3Parser().readContents(yaml).openAPI) { "inventory.yaml did not parse" }
        val item = contract.components.schemas.getValue("InventoryItem")
        item.required = listOf("skuId", "quantity")
        return contract
    }
}
```

**Why (Robert, 2026-09-30):** in OpenAPI, a property is optional unless listed under `required`, and
`InventoryItem` lists none. Validated literally, `{}` would be a valid item and the body checks
would prove almost nothing. So the test parses the YAML into objects (`OpenAPIV3Parser`, from
`swagger-parser`), adds `required: [skuId, quantity]` to that parsed copy, and validates against it.
The file on disk stays exactly as Nuuly wrote it. Extra fields need no change: the Atlassian
validator rejects properties a schema doesn't declare by default.

**Something learned on the way (2026-09-30):** the validator only enforces `required` for
properties the schema defines. Requiring a made-up field (`price`) is silently ignored. So an
experiment that "requires something the app doesn't send" proves nothing; tightening a real
constraint (say `quantity` maximum 2) does make the four 200 tests fail, which is how the
matcher was confirmed to check real responses.

**Kotlin: `companion object`.** Kotlin has no `static`. A `companion object` is a single object
tied to the class, and its members are called on the class name, much like Java's static members.
Here it's `private`, and `strictContract()` is called from the property initializers above. It
could also be a private top-level function; keeping it in the companion keeps it next to the
class that uses it.

**Kotlin: `X::class.java.getResource(...)`.** `InventoryContractTest::class.java` is the Java
`Class` object (Java: `InventoryContractTest.class`). `getResource("/openapi/...")` finds a file on
the classpath; `src/main/resources` is on it, so the leading `/` means "from the root of
resources". It returns `null` if the file isn't there, hence `checkNotNull`. `readText()` is a
Kotlin extension that reads the whole URL into a `String`.

**Kotlin: `Map.getValue(key)`.** `schemas["InventoryItem"]` would return a nullable `Schema?`.
`getValue` returns a non-null value or throws `NoSuchElementException`, which suits a test: a
missing schema should fail loudly.

**Kotlin: assigning Java setters as properties.** `item.required = listOf(...)` calls the Java
method `setRequired(List<String>)`. Kotlin exposes Java getter/setter pairs as properties.

## Check your understanding

- Why does every app test assert `status { ... }` as well as the validator? What bug would slip
  through without it?
- Why can't the invalid-quantity tests use `validator`? What exactly would fail?
- Without the `required` line, which of the seven self-checks would fail, and why?
- Why does `assertRejected` check for a specific key instead of `report.hasErrors()`?
- A teammate proves the matcher works by requiring a field called `price`. The tests stay green.
  What went wrong with their experiment, and what would you do instead?
- What's the difference between this test and `InventoryControllerTest`? Could either replace the other?
