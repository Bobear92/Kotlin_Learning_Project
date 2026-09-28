// Study notes: docs/learning/notes/main/inventory/InventoryQuantity.kt.md

package com.robertwoolbright.inventory.inventory

import jakarta.validation.constraints.Min

/** One inventory quantity; used as the request body for POST /inventory/{skuId}. */
data class InventoryQuantity(
    // Contract: quantity has minimum: 1 in inventory.yaml.
    @Min(1, message = "quantity must be at least 1") val quantity: Int
    )