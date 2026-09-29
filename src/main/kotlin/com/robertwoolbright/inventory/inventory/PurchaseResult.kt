// Study notes: docs/learning/notes/main/inventory/PurchaseResult.kt.md

package com.robertwoolbright.inventory.inventory

// Why: We used the sealed interface because we have 3 possible outcomes we need to account for. Not just a success or null so returning InventoryItem? doesn't help us between when we need to throw 404 or 400.

/** PurchaseResult is returned by the repository and translated by the service. */
sealed interface PurchaseResult {
    
    /** The SKU exists and had enough stock. Item holds the remaining stock. */
    data class Purchased(val item: InventoryItem) : PurchaseResult
    
    /** There's no row for that SKU id. */
    data object SkuNotFound : PurchaseResult
    
    /** The row exists but has less than the requested quantity. */
    data object InsufficientStock : PurchaseResult
    
}