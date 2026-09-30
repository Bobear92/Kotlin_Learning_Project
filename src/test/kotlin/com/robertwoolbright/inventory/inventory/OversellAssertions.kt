// Study notes: docs/learning/notes/test/inventory/OversellAssertions.kt.md
package com.robertwoolbright.inventory.inventory

import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Why: one shared check, so the negative control proves that the exact assertions guarding the
// real repository can detect an oversell, not a separate check written to fail.
/**
 * The oversell invariant for a race of single-unit purchases: [outcomes] holds one result per buyer,
 * the SKU started with [stock] units and ended at [finalQuantity]. With at least as many buyers as
 * units, exactly [stock] purchases succeed, each unit is sold exactly once, the rest are refused,
 * and the SKU ends at 0.
 */
fun assertNoOversell(stock: Int, outcomes: List<PurchaseResult>, finalQuantity: Int?) {
    require(outcomes.size >= stock) { "needs at least $stock buyers, got ${outcomes.size}" }
    assertTrue(outcomes.none { it == PurchaseResult.SkuNotFound }, "an existing SKU was reported missing")

    val remaining = outcomes.filterIsInstance<PurchaseResult.Purchased>().map { it.item.quantity }.sorted()
    assertTrue(remaining.size <= stock, "oversold: ${remaining.size} purchases succeeded for a stock of $stock")
    assertEquals((0 until stock).toList(), remaining, "each unit must be sold exactly once")
    assertEquals(outcomes.size - stock, outcomes.count { it == PurchaseResult.InsufficientStock }, "refused purchases")
    assertEquals(0, finalQuantity, "final quantity")
}
