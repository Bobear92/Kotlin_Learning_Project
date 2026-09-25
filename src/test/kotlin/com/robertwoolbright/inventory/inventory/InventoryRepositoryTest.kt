// Study notes: docs/learning/notes/test/inventory/InventoryRepositoryTest.kt.md
package com.robertwoolbright.inventory.inventory

import com.robertwoolbright.inventory.support.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [InventoryRepository] and the `inventory` schema against a real Postgres: row mapping, ordering,
 * and the `CHECK (quantity >= 0)` backstop. Each test runs in a transaction that is rolled back.
 */
@JdbcTest
@Import(TestcontainersConfiguration::class, InventoryRepository::class)
class InventoryRepositoryTest @Autowired constructor(
    private val inventoryRepository: InventoryRepository,
    private val jdbcClient: JdbcClient,
) {

    private fun insert(skuId: String, quantity: Int) {
        jdbcClient.sql("INSERT INTO inventory (sku_id, quantity) VALUES (?, ?)")
            .params(skuId, quantity)
            .update()
    }

    /** No rows means an empty list, never null. */
    @Test
    fun `findAll returns an empty list when there are no SKUs`() {
        assertTrue(inventoryRepository.findAll().isEmpty())
    }

    /** Every row comes back, mapped to [InventoryItem] and sorted by SKU id. */
    @Test
    fun `findAll returns every SKU ordered by SKU id`() {
        insert("widget", 5)
        insert("CW-XYCS-BM-01", 10)
        insert("gadget", 3)

        assertEquals(
            listOf(
                InventoryItem("CW-XYCS-BM-01", 10),
                InventoryItem("gadget", 3),
                InventoryItem("widget", 5),
            ),
            inventoryRepository.findAll(),
        )
    }

    /** A sold-out SKU still exists, so it is listed with quantity 0. */
    @Test
    fun `findAll includes SKUs with zero stock`() {
        insert("sold-out", 0)

        assertEquals(listOf(InventoryItem("sold-out", 0)), inventoryRepository.findAll())
    }

    /** Oversell backstop: the database itself refuses to store negative stock. */
    @Test
    fun `database rejects an update that would make quantity negative`() {
        insert("widget", 1)

        assertFailsWith<DataIntegrityViolationException> {
            jdbcClient.sql("UPDATE inventory SET quantity = quantity - 2 WHERE sku_id = ?")
                .param("widget")
                .update()
        }
    }
}
