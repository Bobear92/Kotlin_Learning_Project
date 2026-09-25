// Study notes: docs/learning/notes/main/inventory/InventoryService.kt.md

package com.robertwoolbright.inventory.inventory


import org.springframework.stereotype.Service

/** Service class for the GET /inventory flow. Used by controller layer and consumes the repository layer. */
@Service
class InventoryService(
    private val inventoryRepository: InventoryRepository
    ){
    
    /** Returns every inventory item, sorted by SKU id. */
    fun listInventory() : List<InventoryItem> = inventoryRepository.findAll()
    
}