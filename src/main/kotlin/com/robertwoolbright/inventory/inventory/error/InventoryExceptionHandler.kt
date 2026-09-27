// Study notes: docs/learning/notes/main/inventory/error/InventoryExceptionHandler.kt.md

package com.robertwoolbright.inventory.inventory.error

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

// Why: RestController advice so spring will catch and return specific responses we have built out for every controller rather than us building out multiple response entities for each controller. We rejected ResponseStatusException because its body is JSON, not text/plain.

/** Where the various custom built exceptions are transformed to HTTP responses. */
@RestControllerAdvice
class InventoryExceptionHandler{

    // Contract: paths./inventory/{skuId}.get - 404 with text/plain when SkuNotFoundException is thrown.
    
    /** Returns a 404 text/plain response whose body is the exception's message. */
    @ExceptionHandler(SkuNotFoundException::class)
    fun handleSkuNotFound(ex: SkuNotFoundException ): ResponseEntity<String> = 
        
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.TEXT_PLAIN)
            .body(ex.message ?: "SKU not found.")
    
}