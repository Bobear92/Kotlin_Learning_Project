// Study notes: docs/learning/notes/main/inventory/InventoryController.kt.md

package com.robertwoolbright.inventory.inventory

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
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
    
    // Contract: paths./inventory/{skuId}.get - 200 application/json with InventoryItem, 404 with text/plain when SkuNotFoundException is thrown. 
    
    /** Returns an SKU and its quantity for GET /inventory/{skuId}. */
    @GetMapping("/{skuId}")
    fun getInventory(@PathVariable skuId: String): InventoryItem = inventoryService.getInventory(skuId)
    
    // Contract: paths./inventory/{skuId}.post - 200 application/json with InventoryItem, 400 text/plain for a quantity below 1, an unreadable body, the wrong Content-Type, or an add that would overflow.
    
    /** Adds quantity to the SKU's stock, creating the SKU if it's new, and returns the new total for POST /inventory/{skuId}.*/
    @PostMapping("/{skuId}")
    fun receiveInventory(@PathVariable skuId: String, @Valid @RequestBody requestQuantity: InventoryQuantity): InventoryItem = inventoryService.receiveInventory(skuId, requestQuantity.quantity)
    
    // Contract: paths./inventory/{skuId}/purchase.post - 200 application/json with InventoryItem, 400 text/plain for a quantity below 1, an unreadable body, the wrong Content-Type, or insufficient amount of inventory, 404 text/plain for SKU not found.
    
    /** 
     * Removes quantity from the SKU's stock on a purchase, and returns the remaining stock for POST /inventory/{skuId}/purchase. 
     */
    @PostMapping("/{skuId}/purchase")
    fun purchaseInventory(@PathVariable skuId: String, @Valid @RequestBody requestQuantity: InventoryQuantity): InventoryItem = inventoryService.purchaseInventory(skuId, requestQuantity.quantity)
}