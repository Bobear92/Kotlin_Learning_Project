// Study notes: docs/learning/notes/main/inventory/error/InventoryExceptionHandler.kt.md

package com.robertwoolbright.inventory.inventory.error

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

// Why: RestController advice so spring will catch and return specific responses we have built out for every controller rather than us building out multiple response entities for each controller. We rejected ResponseStatusException because its body is JSON, not text/plain.

/** Where the various custom built exceptions and spring exceptions are transformed to HTTP responses. */
@RestControllerAdvice
class InventoryExceptionHandler{

    // Contract: paths./inventory/{skuId}.get - 404 with text/plain when SkuNotFoundException is thrown.
    
    /** Returns a 404 text/plain response whose body is the exception's message. */
    @ExceptionHandler(SkuNotFoundException::class)
    fun handleSkuNotFound(ex: SkuNotFoundException ): ResponseEntity<String> = 
        
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.TEXT_PLAIN)
            .body(ex.message ?: "SKU not found.")
            
    
    // Contract: paths./inventory/{skuId}.post - 400 with text/plain when AddStockOverflowException is thrown.
    
    /** Returns a 400 text/plain response whose body is the exception's message.*/        
    @ExceptionHandler(AddStockOverflowException::class)        
    fun handleStockOverflow(ex: AddStockOverflowException): ResponseEntity<String> =

        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.TEXT_PLAIN)
            .body(ex.message ?: "Overflow Guard Triggered")
    
            
    // Contract: paths./inventory/{skuId}.post - quantity has minimum: 1; a violation is a 400 text/plain.
    
    /** Returns a 400 text/plain response from the @Min message from the failing field.*/              
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMinQuantity(ex: MethodArgumentNotValidException): ResponseEntity<String> =

        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.TEXT_PLAIN)
            .body(ex.bindingResult.fieldError?.defaultMessage ?: "quantity must be at least 1")
       
            
    // Contract: paths./inventory/{skuId}.post - when the request body is invalid we return a 400 text/plain.
    
    /** Returns a 400 text/plain response with the fixed text Invalid request body.*/             
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleInvalidRequestBody(): ResponseEntity<String> =

        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.TEXT_PLAIN)
            .body("Invalid request body")
    
            
    // Contract: paths./inventory/{skuId}.post - when the content type is not application/json we return a 400 text/plain.
    
    /** Returns a 400 text/plain response with the fixed text Content-Type must be application/json.*/      
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleContentTypeMismatch():ResponseEntity<String> =

        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.TEXT_PLAIN)
            .body("Content-Type must be application/json")
}