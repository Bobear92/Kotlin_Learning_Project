// Study notes: docs/learning/notes/main/inventory/InventoryService.kt.md

package com.robertwoolbright.inventory.inventory


import com.robertwoolbright.inventory.inventory.error.AddStockOverflowException
import com.robertwoolbright.inventory.inventory.error.SkuNotFoundException
import com.robertwoolbright.inventory.inventory.error.InsufficientInventoryException
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
    
    /**
     * Adds quantity to the SKU's stock, creating the SKU if it's new, and returns the new total.
     * @throws AddStockOverflowException if the add would push the stock past Int.MAX_VALUE.
     * @throws IllegalArgumentException if quantity is less than 1 (a caller bug, so it surfaces as a 500).
     */
    fun receiveInventory(skuId : String, quantity: Int) : InventoryItem { 
        // Why: @Valid only protects HTTP requests; this also stops direct callers (0 would create an empty SKU).
        require(quantity >= 1) {"quantity must be at least 1, was $quantity"}
        return inventoryRepository.addStock(skuId, quantity) ?: throw AddStockOverflowException()
    }
    
    /** 
     * Removes quantity from a SKU's stock during a purchase. Returns the new InventoryItem on a success.
     * @throws SkuNotFoundException when no sku is found.
     * @throws InsufficientInventoryException if not enough quantity for the purchase.
     * @throws IllegalArgumentException if quantity is less than 1 (a caller bug, so it surfaces as a 500).
     */
    fun purchaseInventory(skuId: String, quantity: Int): InventoryItem {
        // Why: @Valid only protects HTTP requests; this also stops direct callers (Cant have a 0 quantity in a purchase and a negative value would add stock. ).
        require(quantity >= 1) {"quantity must be at least 1, was $quantity"}
        return when (val purchaseOutcome = inventoryRepository.removeStock(skuId,quantity)){
                is PurchaseResult.Purchased -> purchaseOutcome.item
                PurchaseResult.SkuNotFound -> throw SkuNotFoundException(skuId)
                PurchaseResult.InsufficientStock -> throw InsufficientInventoryException(skuId)
            }
    }
}