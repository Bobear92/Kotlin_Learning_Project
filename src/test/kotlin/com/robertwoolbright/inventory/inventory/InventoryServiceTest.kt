// Study notes: docs/learning/notes/test/inventory/InventoryServiceTest.kt.md
package com.robertwoolbright.inventory.inventory

import com.robertwoolbright.inventory.inventory.error.InsufficientInventoryException
import com.robertwoolbright.inventory.inventory.error.SkuNotFoundException
import com.robertwoolbright.inventory.support.TestcontainersConfiguration
import org.junit.jupiter.api.Test
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
 * (a direct Kotlin caller), and how `purchaseInventory` turns each [PurchaseResult] into an item or
 * a domain exception. Runs against a real Postgres; each test is rolled back.
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
    @ParameterizedTest(name = "{displayName} [quantity {0}]")
    @ValueSource(ints = [0, -5])
    fun `receiveInventory rejects a quantity below 1 for an existing SKU`(quantity: Int) {
        insert("widget", 5)

        assertFailsWith<IllegalArgumentException> { inventoryService.receiveInventory("widget", quantity) }
        assertEquals(5, quantityOf("widget"))
    }

    /** A quantity below 1 for an unknown SKU must not create it. */
    @ParameterizedTest(name = "{displayName} [quantity {0}]")
    @ValueSource(ints = [0, -5])
    fun `receiveInventory rejects a quantity below 1 for a new SKU`(quantity: Int) {
        assertFailsWith<IllegalArgumentException> { inventoryService.receiveInventory("new-sku", quantity) }
        assertNull(quantityOf("new-sku"))
    }

    /** A successful purchase returns the item with its remaining stock. */
    @Test
    fun `purchaseInventory returns the remaining stock`() {
        insert("widget", 5)

        assertEquals(InventoryItem("widget", 3), inventoryService.purchaseInventory("widget", 2))
    }

    /** A missing SKU becomes [SkuNotFoundException], which the handler turns into a 404. */
    @Test
    fun `purchaseInventory throws SkuNotFoundException for a missing SKU`() {
        val ex = assertFailsWith<SkuNotFoundException> { inventoryService.purchaseInventory("missing", 1) }
        assertEquals("SKU not found: missing", ex.message)
    }

    /** Not enough stock becomes [InsufficientInventoryException] (a 400), and stock is unchanged. */
    @Test
    fun `purchaseInventory throws InsufficientInventoryException when stock is too low`() {
        insert("widget", 5)

        val ex = assertFailsWith<InsufficientInventoryException> { inventoryService.purchaseInventory("widget", 6) }
        assertEquals("Insufficient inventory for widget", ex.message)
        assertEquals(5, quantityOf("widget"))
    }

    // Why: without the guard, 0 would be a no-op "purchase" and -5 would run `quantity - (-5)`: a purchase that adds stock.
    /** A quantity below 1 is refused before the database is touched. */
    @ParameterizedTest(name = "{displayName} [quantity {0}]")
    @ValueSource(ints = [0, -5])
    fun `purchaseInventory rejects a quantity below 1`(quantity: Int) {
        insert("widget", 5)

        assertFailsWith<IllegalArgumentException> { inventoryService.purchaseInventory("widget", quantity) }
        assertEquals(5, quantityOf("widget"))
    }
}
