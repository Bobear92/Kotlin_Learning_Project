// Study notes: docs/learning/notes/main/inventory/InventoryService.kt.md

package com.robertwoolbright.inventory.inventory


import org.springframework.stereotype.Service

/** Service class for the get /inventory flow. Use by controller layer and consumes the repository layer. */
@Service
class InventoryService(
    private val inventoryRepository: InventoryRepository
    ){
    
    /** Function that returns the list of sorted inventory items. */
    fun listInventory() : List<InventoryItem> = inventoryRepository.findAll()
    
}