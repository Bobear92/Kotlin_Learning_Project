// Study notes: docs/learning/notes/main/InventoryApplication.kt.md
package com.robertwoolbright.inventory

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Entry point of the inventory service. Component scanning starts from this package, so every
 * controller, service and repository below it is picked up automatically.
 */
@SpringBootApplication
class InventoryApplication

/** Starts Spring: builds the application context, runs Flyway migrations and starts Tomcat. */
fun main(args: Array<String>) {
    runApplication<InventoryApplication>(*args)
}
