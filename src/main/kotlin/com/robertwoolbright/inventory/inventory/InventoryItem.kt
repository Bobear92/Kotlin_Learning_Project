// Study notes: docs/learning/notes/main/inventory/InventoryItem.kt.md

package com.robertwoolbright.inventory.inventory

/** One SKU and its stock quantity; returned by the repository and serialized as JSON by GET /inventory. */
data class InventoryItem(val skuId: String, val quantity: Int)