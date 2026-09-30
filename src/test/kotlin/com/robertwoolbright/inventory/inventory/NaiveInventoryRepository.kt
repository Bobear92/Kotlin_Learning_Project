// Study notes: docs/learning/notes/test/inventory/NaiveInventoryRepository.kt.md

package com.robertwoolbright.inventory.inventory

import org.springframework.jdbc.core.simple.JdbcClient

// Why: This repository lives only in test because the class is deliberately unsafe. 
// We have it so NegativeControlTest can prove the oversell check catches a lost update.
/**
 * A purchase done the naive way: read the stock, check it in Kotlin, then write an absolute value.
 * Correct when buyers take turns; under concurrency, buyers overwrite each other and it oversells.
 */
class NaiveInventoryRepository(
    private val jdbcClient: JdbcClient,
    private val pauseBeforeWrite: () -> Unit
) {
    /** Sells [quantity] of [skuId] in three separate steps, calling [pauseBeforeWrite] between the read and the write. */
    fun removeStock(skuId: String, quantity: Int): PurchaseResult  {
        
        val current: Int? = jdbcClient.sql("SELECT quantity FROM inventory WHERE sku_id = :skuId")
        
            .param("skuId", skuId)
            .query{ rs, _ -> 
                 rs.getInt("quantity")
            }
            .list().singleOrNull()
            
            
        if(current == null) return PurchaseResult.SkuNotFound
        if(quantity > current) return PurchaseResult.InsufficientStock
        
        val newQuantity = current - quantity
        
        
        pauseBeforeWrite()
        
        jdbcClient.sql("UPDATE inventory SET quantity = :quantity WHERE sku_id = :skuId")
        .param("skuId", skuId)
        .param("quantity", newQuantity)
        .update()
        
        return PurchaseResult.Purchased(InventoryItem(skuId, newQuantity))
            
    }
    
    
}