// Study notes: docs/learning/notes/test/support/InventoryHttpClient.kt.md
package com.robertwoolbright.inventory.support

import com.robertwoolbright.inventory.inventory.InventoryItem
import com.robertwoolbright.inventory.inventory.PurchaseResult
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Calls a running inventory app over real HTTP, for tests that need more than MockMvc: several app
 * instances, or real sockets under load. Answers come back as the repository's types
 * ([InventoryItem], [PurchaseResult]), so HTTP results can go through the same assertions.
 * Safe to share between threads.
 */
class InventoryHttpClient(private val baseUrl: String) {

    // Why: HTTP/1.1 like a typical client; the JDK's default would first try an HTTP/2 upgrade.
    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val json = jacksonObjectMapper()

    /** `POST /inventory/{skuId}`: adds stock and returns the new total; fails on anything but 200. */
    fun receive(skuId: String, quantity: Int): InventoryItem {
        val response = post("/inventory/$skuId", quantity)
        check(response.statusCode() == 200) { "receive $skuId: ${response.statusCode()} ${response.body()}" }
        return json.readValue(response.body())
    }

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
}
