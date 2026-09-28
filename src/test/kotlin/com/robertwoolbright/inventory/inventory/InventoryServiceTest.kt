// Study notes: docs/learning/notes/test/inventory/InventoryServiceTest.kt.md
package com.robertwoolbright.inventory.inventory

import com.robertwoolbright.inventory.support.TestcontainersConfiguration
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [InventoryService] preconditions that hold even when the controller's `@Valid` check is bypassed
 * (a direct Kotlin caller). Runs against a real Postgres; each test is rolled back.
 */
@JdbcTest
@Import(TestcontainersConfiguration::class, InventoryRepository::class, InventoryService::class)
class InventoryServiceTest @Autowired constructor(
    private val inventoryService: InventoryService,
    private val jdbcClient: JdbcClient,
) {

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

    // Why: a programming error, not a client error, so IllegalArgumentException (a 500), not a 400.
    /** A quantity below 1 is refused before the database is touched, and an existing SKU is unchanged. */
    @ParameterizedTest(name = "quantity {0}")
    @ValueSource(ints = [0, -5])
    fun `receiveInventory rejects a quantity below 1 for an existing SKU`(quantity: Int) {
        insert("widget", 5)

        assertFailsWith<IllegalArgumentException> { inventoryService.receiveInventory("widget", quantity) }
        assertEquals(5, quantityOf("widget"))
    }

    /** A quantity below 1 for an unknown SKU must not create it. */
    @ParameterizedTest(name = "quantity {0}")
    @ValueSource(ints = [0, -5])
    fun `receiveInventory rejects a quantity below 1 for a new SKU`(quantity: Int) {
        assertFailsWith<IllegalArgumentException> { inventoryService.receiveInventory("new-sku", quantity) }
        assertNull(quantityOf("new-sku"))
    }
}
