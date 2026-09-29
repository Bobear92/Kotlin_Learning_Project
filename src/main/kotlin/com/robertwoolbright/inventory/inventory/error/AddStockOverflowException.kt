// Study notes: docs/learning/notes/main/inventory/error/AddStockOverflowException.kt.md

package com.robertwoolbright.inventory.inventory.error

/** Thrown by the service when addStock returns null because of the overflow guard. Maps to a 400 error in the InventoryExceptionHandler. */
class AddStockOverflowException : RuntimeException("Quantity would exceed the maximum stock") 