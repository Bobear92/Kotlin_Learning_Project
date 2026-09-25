// Study notes: docs/learning/notes/main/inventory/InventoryRepository.kt.md

package com.robertwoolbright.inventory.inventory

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository


/** Reads inventory rows from Postgres, used by the service layer. */
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
}