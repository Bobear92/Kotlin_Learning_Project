// Study notes: docs/learning/notes/test/inventory/InventoryControllerTest.kt.md
package com.robertwoolbright.inventory.inventory

import com.robertwoolbright.inventory.support.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * `GET /inventory` and `GET /inventory/{skuId}` through the full stack
 * (MockMvc → controller → service → repository → Postgres), checking status, content type and the
 * exact body (JSON on success, plain text on 404) against the contract.
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
}
