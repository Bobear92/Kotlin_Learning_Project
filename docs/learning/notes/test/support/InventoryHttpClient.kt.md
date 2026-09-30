# InventoryHttpClient.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/support/InventoryHttpClient.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/support/InventoryHttpClient.kt) · Last synced with code: 2026-09-30

## Role

A small client for a *running* inventory app, used by tests that need real HTTP instead of MockMvc:
[MultiInstanceRaceTest.kt](../inventory/MultiInstanceRaceTest.kt.md) (two apps) and the HTTP load
test. MockMvc calls the controller inside the same JVM without a network; this goes through a real
socket, Tomcat, and everything a real client would hit.

It turns HTTP answers back into the repository's types, so an HTTP race can go through the same
`assertNoOversell` as a repository race ([OversellAssertions.kt](../inventory/OversellAssertions.kt.md)).

## Walkthrough

### The JDK's HTTP client

```kotlin
class InventoryHttpClient(private val baseUrl: String) {

    // Why: HTTP/1.1 like a typical client; the JDK's default would first try an HTTP/2 upgrade.
    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val json = jacksonObjectMapper()
```

**Java: `java.net.http.HttpClient`.** Built into the JDK since Java 11, so no dependency. One
client is thread-safe and keeps a pool of connections, so fifty racing threads share it.
`send(request, BodyHandlers.ofString())` blocks until the response arrives and gives the body as a
`String`.

**Why HTTP/1.1:** the JDK client defaults to HTTP/2. Over plain `http://` it sends ordinary HTTP/1.1
requests carrying `Upgrade: h2c` headers, asking the server to switch. Tomcat doesn't enable h2c by
default, so it ignores the headers and answers in 1.1; nothing breaks. Pinning 1.1 keeps the
requests plain and predictable, like `curl`'s.

**Kotlin/Jackson: `jacksonObjectMapper()`.** From `jackson-module-kotlin` (the app already depends on
it): a Jackson 3 `JsonMapper` that understands Kotlin data classes, so `InventoryItem` can be read
without a no-arg constructor.

### Purchase: HTTP back to a `PurchaseResult`

```kotlin
// Why: 400 is also the status for an invalid body, so the message decides; anything unexpected
// throws instead of being counted as a refused purchase.
/** `POST /inventory/{skuId}/purchase`, with the response translated back into a [PurchaseResult]. */
fun purchase(skuId: String, quantity: Int): PurchaseResult {
    val response = post("/inventory/$skuId/purchase", quantity)
    return when {
        response.statusCode() == 200 -> PurchaseResult.Purchased(json.readValue(response.body()))
        response.statusCode() == 400 && response.body().startsWith("Insufficient inventory") ->
            PurchaseResult.InsufficientStock
        response.statusCode() == 404 -> PurchaseResult.SkuNotFound
        else -> error("purchase $skuId: ${response.statusCode()} ${response.body()}")
    }
}
```

This is the controller's mapping run backwards: 200 → `Purchased`, the insufficient-stock 400 →
`InsufficientStock`, 404 → `SkuNotFound`.

**Why check the message on a 400:** if a bug made every request a "400 Invalid request body", a
test that counted every 400 as "refused" could still pass the oversell check (nothing sold, nothing
oversold). Anything that isn't one of the three expected answers throws, and `race` reports it as
a failure.

**Kotlin: `readValue` with an inferred type.** `json.readValue(response.body())` is a reified generic
function (see the concepts index). The type to parse into comes from where the result goes:
`Purchased(...)` needs an `InventoryItem`, so that's what's read. In `quantityOf`, nothing tells the
compiler the type, so it's written out: `readValue<InventoryItem>(...)`.

**Kotlin: `when` without a subject** (first seen in
[InventoryRepository.kt](../../main/inventory/InventoryRepository.kt.md#choosing-the-outcome-a-when-without-a-subject)):
the first true branch wins, and `else` is required.

**Kotlin: `error(...)`.** Throws an `IllegalStateException` with the message (see
[InventoryConcurrencyTest.kt](../inventory/InventoryConcurrencyTest.kt.md#restocks-mixed-with-purchases)).
Inside `race` it becomes a failed `Result`, so an unexpected answer shows up in the failures list.

### `receive`, `quantityOf`, `inventory`

```kotlin
/** `POST /inventory/{skuId}`: adds stock and returns the new total; fails on anything but 200. */
fun receive(skuId: String, quantity: Int): InventoryItem {
    val response = post("/inventory/$skuId", quantity)
    check(response.statusCode() == 200) { "receive $skuId: ${response.statusCode()} ${response.body()}" }
    return json.readValue(response.body())
}
```

**Kotlin: `check`** (indexed with `require` in the concepts index). `check(condition) { message }` throws `IllegalStateException` when the
condition is false. It's the sibling of `require` (`IllegalArgumentException`, "you called me
wrong"): `check` means "the state I found is wrong", here an unexpected response.

```kotlin
/** `GET /inventory/{skuId}`: the SKU's stock, or `null` on a 404. */
fun quantityOf(skuId: String): Int? {
    val response = http.send(request("/inventory/$skuId").GET().build(), HttpResponse.BodyHandlers.ofString())
    return when (response.statusCode()) {
        200 -> json.readValue<InventoryItem>(response.body()).quantity
        404 -> null
        else -> error("get $skuId: ${response.statusCode()} ${response.body()}")
    }
}

/** `GET /inventory`: every SKU, in the order the app returns them. */
fun inventory(): List<InventoryItem> {
    val response = http.send(request("/inventory").GET().build(), HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() == 200) { "list: ${response.statusCode()} ${response.body()}" }
    return json.readValue(response.body())
}
```

**Kotlin: `Int?` as "maybe absent".** `quantityOf` returns `null` for a 404, mirroring the
`quantityOf` helpers the repository-level tests run against the database, so callers write
`assertEquals(0, client.quantityOf("widget"))` the same way.

**Kotlin: `when` with a subject.** `when (response.statusCode()) { 200 -> ...; 404 -> ... }` compares
the value against each branch, like Java's `switch`. Here the status alone decides, unlike in
`purchase`, where a 400 also needs its body checked.

**Jackson: reading a list.** In `inventory()`, the return type `List<InventoryItem>` tells
`readValue` what to build, generic element type included. That works because `readValue` is
reified: the full type survives to runtime, which Java's `readValue(json, List.class)` can't do.

### The request helpers

```kotlin
private fun post(path: String, quantity: Int): HttpResponse<String> =
    http.send(
        request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"quantity": $quantity}"""))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

private fun request(path: String): HttpRequest.Builder =
    HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
```

**Java: the request builder.** `HttpRequest.newBuilder(uri)` then `.header`, `.POST(body)` or
`.GET()`, then `.build()`. The body is a `BodyPublisher`; `ofString` sends a `String`.

**Kotlin: string templates in a raw string.** `"""{"quantity": $quantity}"""` needs no escaped
quotes and still fills in `$quantity`.

**Why a timeout:** a hung server should fail the test after 30 seconds, not hang the build.

## Check your understanding

- What does this client test that MockMvc can't?
- Why does `purchase` look at the body of a 400 instead of treating every 400 as "insufficient"?
  Describe a bug the looser version would hide.
- `check` vs. `require`: which one fits here and why?
- Why can one `HttpClient` be shared by fifty threads?
- Why does `quantityOf` need `readValue<InventoryItem>` while `receive` can write plain `readValue`?
