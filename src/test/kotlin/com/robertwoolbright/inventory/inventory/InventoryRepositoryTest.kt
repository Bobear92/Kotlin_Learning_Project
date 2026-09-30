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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [InventoryRepository] and the `inventory` schema against a real Postgres: row mapping, ordering,
 * single-SKU lookup, adding stock (create, add, overflow guard), removing stock (purchased, not found,
 * insufficient), and the `CHECK (quantity >= 0)` backstop.
 * Each test runs in a transaction that is rolled back.
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

    private fun quantityOf(skuId: String): Int? =
        jdbcClient.sql("SELECT quantity FROM inventory WHERE sku_id = ?")
            .param(skuId)
            .query { rs, _ -> rs.getInt("quantity") }
            .list()
            .singleOrNull()

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

    /** One row comes back as an [InventoryItem] when the SKU exists. */
    @Test
    fun `findBySkuId returns the item for an existing SKU`() {
        insert("widget", 5)
        insert("gadget", 3)

        assertEquals(InventoryItem("widget", 5), inventoryRepository.findBySkuId("widget"))
    }

    /** No row means null, not an exception: the service decides that a missing SKU is a 404. */
    @Test
    fun `findBySkuId returns null for a missing SKU`() {
        insert("widget", 5)

        assertNull(inventoryRepository.findBySkuId("missing"))
    }

    /** A sold-out SKU still exists, so it is found with quantity 0. */
    @Test
    fun `findBySkuId returns a SKU with zero stock`() {
        insert("sold-out", 0)

        assertEquals(InventoryItem("sold-out", 0), inventoryRepository.findBySkuId("sold-out"))
    }

    // Why: SKU ids are an exact, case-sensitive match for now (README "Column types" decision).
    /** `WIDGET` and `widget` are different SKUs. */
    @Test
    fun `findBySkuId matches the SKU id case-sensitively`() {
        insert("widget", 5)

        assertNull(inventoryRepository.findBySkuId("WIDGET"))
    }

    /** Contract: "If the SKU does not yet exist in inventory, it is created with the given quantity." */
    @Test
    fun `addStock creates a new SKU with the given quantity`() {
        assertEquals(InventoryItem("widget", 5), inventoryRepository.addStock("widget", 5))
        assertEquals(5, quantityOf("widget"))
    }

    /** Contract: "If it already exists, the quantity is added to the current stock." */
    @Test
    fun `addStock adds to the quantity of an existing SKU`() {
        insert("widget", 5)

        assertEquals(InventoryItem("widget", 8), inventoryRepository.addStock("widget", 3))
        assertEquals(8, quantityOf("widget"))
    }

    /** A sold-out SKU still has a row, so restocking it updates that row rather than inserting. */
    @Test
    fun `addStock restocks a SKU with zero stock`() {
        insert("sold-out", 0)

        assertEquals(InventoryItem("sold-out", 4), inventoryRepository.addStock("sold-out", 4))
    }

    // Why: a decoy row catches an UPDATE that forgot its WHERE / conflict target and hit every row.
    /** Adding stock to one SKU leaves every other SKU alone. */
    @Test
    fun `addStock changes only the given SKU`() {
        insert("widget", 5)
        insert("gadget", 3)

        inventoryRepository.addStock("widget", 2)

        assertEquals(3, quantityOf("gadget"))
    }

    /** The largest storable total is allowed: the overflow guard must not be off by one. */
    @Test
    fun `addStock can fill a SKU up to exactly Int MAX_VALUE`() {
        insert("widget", Int.MAX_VALUE - 1)

        assertEquals(InventoryItem("widget", Int.MAX_VALUE), inventoryRepository.addStock("widget", 1))
    }

    // Why: a naive `quantity + EXCLUDED.quantity <= max` check overflows itself, so Postgres throws
    // instead of returning null; the second call catches that.
    /** Overflow is refused atomically: null comes back and the stored quantity is unchanged. */
    @Test
    fun `addStock returns null and leaves stock unchanged when the total would overflow`() {
        insert("widget", Int.MAX_VALUE - 1)
        insert("full", Int.MAX_VALUE)

        assertNull(inventoryRepository.addStock("widget", 2))
        assertNull(inventoryRepository.addStock("full", Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE - 1, quantityOf("widget"))
        assertEquals(Int.MAX_VALUE, quantityOf("full"))
    }

    // Why: SKU ids are an exact, case-sensitive match (README "Contract gaps" decision, 2026-09-28).
    /** Adding to `WIDGET` creates a new SKU; it does not touch `widget`. */
    @Test
    fun `addStock treats SKU ids case-sensitively`() {
        insert("widget", 5)

        assertEquals(InventoryItem("WIDGET", 2), inventoryRepository.addStock("WIDGET", 2))
        assertEquals(5, quantityOf("widget"))
    }

    /** Contract: "Deducts the requested quantity"; the remaining stock comes back. */
    @Test
    fun `removeStock deducts the quantity and returns the remaining stock`() {
        insert("widget", 5)

        assertEquals(PurchaseResult.Purchased(InventoryItem("widget", 3)), inventoryRepository.removeStock("widget", 2))
        assertEquals(3, quantityOf("widget"))
    }

    // Why: the boundary of `quantity >= :quantity`; writing `>` instead would refuse the last unit.
    /** Buying exactly what is on hand succeeds and leaves a sold-out row with quantity 0, not a deleted one. */
    @Test
    fun `removeStock can sell the last unit and keeps the row at zero`() {
        insert("widget", 5)

        assertEquals(PurchaseResult.Purchased(InventoryItem("widget", 0)), inventoryRepository.removeStock("widget", 5))
        assertEquals(0, quantityOf("widget"))
    }

    /** Contract: "only allowed if the current stock is sufficient to fulfill the full requested quantity": no partial fill. */
    @Test
    fun `removeStock refuses a purchase larger than the stock and changes nothing`() {
        insert("widget", 5)

        assertEquals(PurchaseResult.InsufficientStock, inventoryRepository.removeStock("widget", 6))
        assertEquals(5, quantityOf("widget"))
    }

    /** A sold-out SKU exists, so buying from it is insufficient stock (400), not a missing SKU (404). */
    @Test
    fun `removeStock reports insufficient stock for a sold-out SKU`() {
        insert("sold-out", 0)

        assertEquals(PurchaseResult.InsufficientStock, inventoryRepository.removeStock("sold-out", 1))
        assertEquals(0, quantityOf("sold-out"))
    }

    /** Contract: "Returns 404 if the SKU does not exist"; a purchase must never create the SKU. */
    @Test
    fun `removeStock reports a missing SKU and creates nothing`() {
        insert("widget", 5)

        assertEquals(PurchaseResult.SkuNotFound, inventoryRepository.removeStock("missing", 1))
        assertNull(quantityOf("missing"))
    }

    // Why: SKU ids are an exact, case-sensitive match (README "Contract gaps" decision, 2026-09-28).
    /** Buying `WIDGET` does not touch `widget`; `WIDGET` does not exist. */
    @Test
    fun `removeStock treats SKU ids case-sensitively`() {
        insert("widget", 5)

        assertEquals(PurchaseResult.SkuNotFound, inventoryRepository.removeStock("WIDGET", 1))
        assertEquals(5, quantityOf("widget"))
    }

    // Why: a decoy row catches an UPDATE that forgot `sku_id = :skuId` and hit every row.
    /** Buying one SKU leaves every other SKU alone. */
    @Test
    fun `removeStock changes only the given SKU`() {
        insert("widget", 5)
        insert("gadget", 3)

        inventoryRepository.removeStock("widget", 2)

        assertEquals(3, quantityOf("gadget"))
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
