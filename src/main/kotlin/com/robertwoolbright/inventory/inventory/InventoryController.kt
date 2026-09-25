// Study notes: docs/learning/notes/main/inventory/InventoryController.kt.md

package com.robertwoolbright.inventory.inventory

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


/** Controller for the inventory Flow. At the top layer, the controller layer, and calls the service layer. */
@RestController
@RequestMapping("/inventory")
class InventoryController (
    private val inventoryService: InventoryService
){
    // Contract: The below mapping maps to the Get /Inventory endpoint in the inventory.yaml contract. The endpoint is a simple and returns all inventory.
    
    /** A function that calls inventory service and returns a list of inventory items.*/
    @GetMapping
    fun findAllInventory(): List<InventoryItem> = inventoryService.listInventory()
}