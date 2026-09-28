// Study notes: docs/learning/notes/main/inventory/InventoryService.kt.md

package com.robertwoolbright.inventory.inventory


import com.robertwoolbright.inventory.inventory.error.SkuNotFoundException
import org.springframework.stereotype.Service

/** Service class for the various inventory flows. Used by controller layer and consumes the repository layer. */
@Service
class InventoryService(
    private val inventoryRepository: InventoryRepository
    ){
    
    /** Returns every inventory item, sorted by SKU id. */
    fun listInventory() : List<InventoryItem> = inventoryRepository.findAll()
    
    /** 
     * Returns an inventory item by SKU. 
     * @throws SkuNotFoundException if no sku is found. 
     */
    fun getInventory(skuId : String) : InventoryItem = inventoryRepository.findBySkuId(skuId) ?: throw SkuNotFoundException(skuId)
    
}