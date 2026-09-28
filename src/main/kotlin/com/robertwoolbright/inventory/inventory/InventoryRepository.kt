// Study notes: docs/learning/notes/main/inventory/InventoryRepository.kt.md

package com.robertwoolbright.inventory.inventory

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository


/** Reads and writes inventory rows from Postgres, used by the service layer. */
@Repository
class InventoryRepository(
    private val jdbcClient: JdbcClient,
){
    
    /** Returns every SKU with its quantity, sorted by SKU id; zero-stock SKUs included, empty list when none. */
    fun findAll(): List<InventoryItem> = jdbcClient.sql("SELECT sku_id, quantity FROM inventory ORDER BY sku_id")
    
    // Why: Chose the result set lambda instead of InventoryItem::class.java so Kotlin sees the items as non null.
            .query { rs, _ -> 
                InventoryItem(rs.getString("sku_id"), rs.getInt("quantity"))
            }
            .list()
            
            
    /** Returns a SKU with its quantity for a specific SKU. Will return null if no such SKU exists */        
    fun findBySkuId(skuId: String): InventoryItem? = jdbcClient.sql("SELECT sku_id, quantity FROM inventory WHERE sku_id = :skuId")

    // Why: We parameterized the skuid instead of adding it directly to the sql above for sql injection prevention. 
            .param("skuId", skuId)
            .query{rs, _ ->
                InventoryItem(skuId = rs.getString("sku_id"), quantity = rs.getInt("quantity"))
            }
    // Why: The list singleOrNull below, we did this over the Java optional so we can stay within kotlin. We wont have more than one entry in the list because sku_id is the primary key so it is unique.
            .list().singleOrNull()
            
    
    /** Adds an item into the database with an SKU and quantity. Will update the quantity if SKU already exists. Returns the row after the change with the new total. Only chance of it returning null is if its goes past the max value for an Int and nothing would be changed. Quantity must be at least 1. */
    fun addStock(skuId: String, quantity: Int): InventoryItem? = jdbcClient.sql(
    // Why: We have one upsert sql instead of find then insert so we don't deal with requests racing. 
    // Why: The WHERE column we have the Overflow guard where guard written as current ≤ max − new because current + new would itself overflow in Postgres.
        """ 
            INSERT INTO inventory (sku_id, quantity)  
                VALUES (:skuId, :quantity) 
                    ON CONFLICT (sku_id) 
                        DO UPDATE SET quantity = inventory.quantity + EXCLUDED.quantity  
                            WHERE inventory.quantity <= :maxQuantity - EXCLUDED.quantity 
                                RETURNING sku_id, quantity
                """
    )
            .param("skuId", skuId)
            .param("quantity", quantity)
            .param("maxQuantity", Int.MAX_VALUE)
            .query{rs, _ ->
                InventoryItem(skuId = rs.getString("sku_id"), quantity = rs.getInt("quantity"))
            }
            .list().singleOrNull()
}