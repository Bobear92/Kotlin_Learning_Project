// Study notes: docs/learning/notes/main/inventory/InventoryController.kt.md

package com.robertwoolbright.inventory.inventory

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


/** Controller for the inventory flow. HTTP entry point for the inventory endpoints; calls InventoryService. */
@RestController
@RequestMapping("/inventory")
class InventoryController (
    private val inventoryService: InventoryService
){
    // Contract: paths./inventory.get — 200 application/json array of InventoryItem, [] when empty.
    
    /** Returns every SKU and its quantity for GET /inventory.*/
    @GetMapping
    fun listInventory(): List<InventoryItem> = inventoryService.listInventory()
}