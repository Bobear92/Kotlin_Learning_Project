// Study notes: docs/learning/notes/main/inventory/InventoryItem.kt.md

package com.robertwoolbright.inventory.inventory

/** One item in the inventory and how much there is of it from the repository for the GET /inventory flow. */

data class InventoryItem(val skuId: String, val quantity: Int)