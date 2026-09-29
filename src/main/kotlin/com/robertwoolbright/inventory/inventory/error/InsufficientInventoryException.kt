// Study notes: docs/learning/notes/main/inventory/error/InsufficientInventoryException.kt.md

package com.robertwoolbright.inventory.inventory.error

/** Thrown by the service when removeStock reports insufficient stock for the purchase. Mapped to a 400 by InventoryExceptionHandler. */
class InsufficientInventoryException(skuId: String) : RuntimeException("Insufficient inventory for $skuId") 