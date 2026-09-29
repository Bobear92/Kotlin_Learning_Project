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
            
    
    /**
     * Adds quantity to a SKU's stock, creating the SKU if it doesn't exist yet, and returns the row with the new total.
     * Returns null, changing nothing, only if the new total would go past Int.MAX_VALUE. Quantity must be at least 1.
     */
    fun addStock(skuId: String, quantity: Int): InventoryItem? = jdbcClient.sql(
    // Why: one upsert instead of find-then-insert, so concurrent requests can't race each other.
    // Why: the overflow guard in the WHERE clause is written as current <= MAX - new, because current + new would itself overflow in Postgres.
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
            
            
    /** 
     * Removes the requested quantity because of a purchase from the SKU's stock.
     * Quantity must be at least 1.
     * Returns [PurchaseResult.Purchased] with the remaining stock if successful, [PurchaseResult.InsufficientStock] if there is not enough quantity, [PurchaseResult.SkuNotFound] if the skuId doesn't exist.
     */
    fun removeStock(skuId: String, quantity: Int): PurchaseResult = jdbcClient.sql(
        // Why: One statement instead of two (update, then select), so another request can't change the stock between them (a race condition)
        """
            WITH purchased AS (
                UPDATE inventory SET quantity = quantity - :requested_quantity
                    WHERE sku_id = :skuId AND quantity >= :requested_quantity
                        RETURNING quantity
            )
            SELECT 
                (SELECT quantity FROM purchased) AS remaining_quantity,
                    EXISTS (SELECT 1 FROM inventory WHERE sku_id = :skuId) AS sku_exists
        """
        )
        .param("skuId", skuId)
        .param("requested_quantity", quantity)
        .query{rs, _ ->
                // Why: Used the javaObjectType so null stays null. JDBC getInt would turn null into 0, but 0 is a valid use case here. Meaning the last unit was just sold now the stock is 0. Null would be nothing was bought. We need to keep these distinct.
                val remainingQuantity: Int? = rs.getObject("remaining_quantity", Int::class.javaObjectType) 
                    val skuExists = rs.getBoolean("sku_exists")
                        when {
                            remainingQuantity != null -> PurchaseResult.Purchased(InventoryItem(skuId, remainingQuantity))
                            skuExists -> PurchaseResult.InsufficientStock
                            else -> PurchaseResult.SkuNotFound
                        }
        }
        .single()
}