// Study notes: docs/learning/notes/main/inventory/InventoryQuantity.kt.md

package com.robertwoolbright.inventory.inventory

import jakarta.validation.constraints.Min

/** One Inventory quantity; Used as a request body for POST /inventory/{skuId}. */
data class InventoryQuantity(
    // Contract: The contract requires the minimum value for for the InventoryQuantity object to be 1.
    @Min(1, message = "quantity must be at least 1") val quantity: Int
    )