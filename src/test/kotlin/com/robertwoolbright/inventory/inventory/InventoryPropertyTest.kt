// Study notes: docs/learning/notes/test/inventory/InventoryPropertyTest.kt.md
package com.robertwoolbright.inventory.inventory

import com.robertwoolbright.inventory.support.TestcontainersConfiguration
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Oversell test plan, model-based: random sequences of receives and purchases run against
 * [InventoryRepository] (real Postgres) and against a simple in-memory model at the same time.
 * After every step both must give the same answer and hold the same stock, and no SKU may ever go
 * negative. Single-threaded: the race tests cover concurrency.
 */
@JdbcTest
@Import(TestcontainersConfiguration::class, InventoryRepository::class)
class InventoryPropertyTest @Autowired constructor(
    private val inventoryRepository: InventoryRepository,
    private val jdbcClient: JdbcClient,
) {

    /** One step of a generated sequence. */
    private sealed interface Operation {
        val skuId: String
        val quantity: Int

        /** `POST /inventory/{skuId}`: add stock, creating the SKU if needed. */
        data class Receive(override val skuId: String, override val quantity: Int) : Operation

        /** `POST /inventory/{skuId}/purchase`: take stock if there is enough. */
        data class Purchase(override val skuId: String, override val quantity: Int) : Operation
    }

    // Why: the obviously-correct version of the rules, with no SQL and no concurrency. If the
    // repository ever disagrees with it, one of the two is wrong, and the model is easy to check by eye.
    /** In-memory inventory: a map from SKU to stock, following the same rules as the contract. */
    private class InventoryModel {
        private val stock = mutableMapOf<String, Int>()

        fun receive(skuId: String, quantity: Int): InventoryItem {
            val newQuantity = stock.getOrDefault(skuId, 0) + quantity
            stock[skuId] = newQuantity
            return InventoryItem(skuId, newQuantity)
        }

        fun purchase(skuId: String, quantity: Int): PurchaseResult {
            val current = stock[skuId] ?: return PurchaseResult.SkuNotFound
            if (current < quantity) return PurchaseResult.InsufficientStock
            stock[skuId] = current - quantity
            return PurchaseResult.Purchased(InventoryItem(skuId, current - quantity))
        }

        fun items(): List<InventoryItem> = stock.map { (skuId, quantity) -> InventoryItem(skuId, quantity) }.sortedBy { it.skuId }
    }

    // Why: three SKUs so operations collide on the same row often; purchases up to 15 against
    // receives up to 10, so "insufficient" is common; "c" is never received, so "not found" happens too.
    private val skuIds = Arb.element("a", "b", "c")
    private val operation: Arb<Operation> = Arb.choice(
        Arb.bind(Arb.element("a", "b"), Arb.int(1..10)) { skuId, quantity -> Operation.Receive(skuId, quantity) },
        Arb.bind(skuIds, Arb.int(1..15)) { skuId, quantity -> Operation.Purchase(skuId, quantity) },
    )

    /**
     * For any sequence of up to 30 operations, the repository and the model agree after every step,
     * and stock never goes negative.
     */
    // Why: a block body, not `= runBlocking { ... }`. That form would return checkAll's result, and
    // JUnit silently skips @Test methods that don't return Unit.
    @Test
    fun `the repository behaves like the in-memory model for any sequence of operations`() {
        runBlocking {
            checkAll(PropTestConfig(iterations = 200), Arb.list(operation, 1..30)) { operations ->
                jdbcClient.sql("DELETE FROM inventory").update()
                val model = InventoryModel()

                operations.forEachIndexed { step, operation ->
                    val context = "step $step of $operations"
                    when (operation) {
                        is Operation.Receive -> assertEquals(
                            model.receive(operation.skuId, operation.quantity),
                            inventoryRepository.addStock(operation.skuId, operation.quantity),
                            "receive at $context",
                        )
                        is Operation.Purchase -> assertEquals(
                            model.purchase(operation.skuId, operation.quantity),
                            inventoryRepository.removeStock(operation.skuId, operation.quantity),
                            "purchase at $context",
                        )
                    }
                    val stored = inventoryRepository.findAll()
                    assertEquals(model.items(), stored, "stock after $context")
                    assertTrue(stored.all { it.quantity >= 0 }, "negative stock after $context: $stored")
                }
            }
        }
    }
}
