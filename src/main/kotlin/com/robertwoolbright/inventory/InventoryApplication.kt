package com.robertwoolbright.inventory

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// Spring: `@SpringBootApplication` combines three annotations:
//   @Configuration            this class may declare beans
//   @EnableAutoConfiguration  set up whatever the classpath implies (Tomcat, DataSource, Flyway, Jackson...)
//   @ComponentScan            find @Component/@Service/@RestController classes in this package and below
// Why: that scan is why this file sits at the root package, com.robertwoolbright.inventory.
// Kotlin: a class with no body needs no braces. Spring never calls anything on this class; it
// only reads the annotations. It still has to be a `class` (not an `object`), because Spring
// creates and proxies configuration classes itself.

/**
 * Entry point of the inventory service and root of its Spring configuration.
 *
 * Every request flows through beans that Spring discovers from here:
 * controller → service → repository, all in the `inventory` feature package below this one.
 */
@SpringBootApplication
class InventoryApplication

// Kotlin: `main` is a top-level function; it doesn't live inside a class. The compiler puts it
// in a generated class called `InventoryApplicationKt` as a `public static void main`, which
// is exactly what the JVM looks for. In Java it would have to be a static method on a class.

/**
 * Starts the Spring application: builds the context, runs Flyway migrations and starts Tomcat.
 *
 * @param args command-line arguments, passed through to Spring (e.g. `--server.port=9090`).
 */
fun main(args: Array<String>) {
    // Kotlin: `runApplication<InventoryApplication>` is an inline function with a reified type
    // parameter, so the class is named in angle brackets. The Java equivalent is
    // `SpringApplication.run(InventoryApplication.class, args)`.
    // `*args` is the spread operator: it passes the array's elements as a vararg.
    runApplication<InventoryApplication>(*args)
}
