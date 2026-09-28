// Study notes: docs/learning/notes/test/inventory/InventoryControllerTest.kt.md
package com.robertwoolbright.inventory.inventory

import com.robertwoolbright.inventory.support.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `GET /inventory`, `GET /inventory/{skuId}` and `POST /inventory/{skuId}` through the full stack
 * (MockMvc → controller → service → repository → Postgres), checking status, content type and the
 * exact body (JSON on success, plain text on 400/404) against the contract, and that rejected
 * requests leave stock unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class InventoryControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jdbcClient: JdbcClient,
) {

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

    private fun quantityOf(skuId: String): Int? =
        jdbcClient.sql("SELECT quantity FROM inventory WHERE sku_id = ?")
            .param(skuId)
            .query { rs, _ -> rs.getInt("quantity") }
            .list()
            .singleOrNull()

    private fun postInventory(
        skuId: String,
        body: String,
        mediaType: MediaType = MediaType.APPLICATION_JSON,
    ): ResultActionsDsl =
        mockMvc.post("/inventory/$skuId") {
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

    /** Contract: a JSON array of InventoryItem (`skuId`, `quantity`), here in SKU id order. */
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

    /** Contract: for an existing SKU "the quantity is added to the current stock"; the new total comes back. */
    @Test
    fun `POST inventory adds to an existing SKU`() {
        insert("widget", 5)

        postInventory("widget", """{"quantity": 3}""").andExpect {
            status { isOk() }
            content {
                contentType(MediaType.APPLICATION_JSON)
                json("""{"skuId": "widget", "quantity": 8}""", JsonCompareMode.STRICT)
            }
        }
        assertEquals(8, quantityOf("widget"))
    }

    /** Contract: `quantity` has `minimum: 1`, so 0 and negatives are a 400 and stock is untouched. */
    @ParameterizedTest(name = "quantity {0}")
    @ValueSource(ints = [0, -5])
    fun `POST inventory rejects a quantity below 1`(quantity: Int) {
        insert("widget", 5)

        postInventory("widget", """{"quantity": $quantity}""")
            .andExpectBadRequest("quantity must be at least 1")
        assertEquals(5, quantityOf("widget"))
    }

    /** A rejected POST for an unknown SKU must not create it with zero stock. */
    @Test
    fun `POST inventory with an invalid quantity does not create the SKU`() {
        postInventory("new-sku", """{"quantity": 0}""")
            .andExpectBadRequest("quantity must be at least 1")
        assertNull(quantityOf("new-sku"))
    }

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

    // Why: Spring's default is 415, which the contract doesn't list (README "Contract gaps" decision, 2026-09-28).
    /** The contract only accepts `application/json`; anything else is a 400 text/plain. */
    @Test
    fun `POST inventory rejects a non-JSON content type`() {
        insert("widget", 5)

        postInventory("widget", "5", MediaType.TEXT_PLAIN)
            .andExpectBadRequest("Content-Type must be application/json")
        assertEquals(5, quantityOf("widget"))
    }

    /** Stock is an Int; a total past Int.MAX_VALUE is an invalid request (400), not a 500, and nothing changes. */
    @Test
    fun `POST inventory rejects an add that would overflow the stock`() {
        insert("widget", Int.MAX_VALUE - 1)

        postInventory("widget", """{"quantity": 2}""")
            .andExpectBadRequest("Quantity would exceed the maximum stock")
        assertEquals(Int.MAX_VALUE - 1, quantityOf("widget"))
    }

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
}
