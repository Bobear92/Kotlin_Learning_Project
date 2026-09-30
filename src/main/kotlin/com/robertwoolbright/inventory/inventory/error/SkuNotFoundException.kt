// Study notes: docs/learning/notes/main/inventory/error/SkuNotFoundException.kt.md

package com.robertwoolbright.inventory.inventory.error

/** Thrown when the database is queried with the SKU, but the result comes back as null. And thrown during the purchase flow on PurchaseResult.SkuNotFound. This is thrown at the service level and is mapped to a 404 by InventoryExceptionHandler. */
class SkuNotFoundException(skuId: String) : RuntimeException("SKU not found: $skuId") 