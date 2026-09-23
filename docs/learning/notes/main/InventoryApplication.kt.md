# InventoryApplication.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/InventoryApplication.kt](../../../../src/main/kotlin/com/robertwoolbright/inventory/InventoryApplication.kt) · Last synced with code: 2026-09-23

## Role

The entry point. `./gradlew bootRun` (or `java -jar`) calls `main`, which starts Spring. Spring
then finds every controller, service and repository and wires them together. No request ever
passes through this file, but none could be served without it.

## Walkthrough

### The package

```kotlin
package com.robertwoolbright.inventory
```

**Kotlin:** same as Java, except the directory doesn't *have* to match the package in Kotlin. We
keep them matching anyway because that's the convention and Spring's scan relies on packages.

**Why:** this file sits in the *root* package on purpose. See `@ComponentScan` below.

### `@SpringBootApplication` and a class with no body

```kotlin
@SpringBootApplication
class InventoryApplication
```

**Spring:** `@SpringBootApplication` combines three annotations:

| Annotation | Effect |
| --- | --- |
| `@SpringBootConfiguration` | A specialized `@Configuration`: this class may declare `@Bean` methods, and it's what `@SpringBootTest` searches for. |
| `@EnableAutoConfiguration` | Configure whatever the classpath implies: Tomcat because Web MVC is present, a `DataSource` because JDBC + the Postgres driver are, Flyway, Jackson… |
| `@ComponentScan` | Find `@Component`, `@Service`, `@Repository` and `@RestController` classes **in this package and its sub-packages**, and create them as beans. |

That scan is why the file lives at `com.robertwoolbright.inventory`: the future
`inventory.InventoryController` sits below it and gets found automatically.

**Kotlin:** a class with no body needs no `{}`. Spring never calls anything on this class; it
only reads the annotations. It must still be a `class` rather than an `object` (Kotlin's
singleton), because Spring wants to create and proxy configuration classes itself. The
`plugin.spring` compiler plugin makes it `open` so Spring can subclass it. See the
[build notes](../build.gradle.kts.md).

### Top-level `main`

```kotlin
/** Starts Spring: builds the application context, runs Flyway migrations and starts Tomcat. */
fun main(args: Array<String>) {
    runApplication<InventoryApplication>(*args)
}
```

**Kotlin: top-level functions.** `main` isn't inside any class. Kotlin allows functions at file
level. The compiler still has to put bytecode in a class, so it generates one named after the
file: `InventoryApplicationKt`, with a `public static void main(String[] args)`. The startup log
shows it: `Started InventoryApplicationKt in 5.9 seconds`. In Java, `main` must be a static
method written inside a class.

Top-level functions are imported directly by name:
`import org.springframework.boot.runApplication` imports a *function*, not a class. Java can
only do that with `import static`, and only for static methods of a class.

**Kotlin: `Array<String>`** is Java's `String[]`. Kotlin arrays are an ordinary generic class, not
special syntax.

**Kotlin: reified generics.** `runApplication<InventoryApplication>(...)` names the class in angle
brackets. It's an `inline` function with a `reified` type parameter, so it can use `T::class` at
runtime (normally generics are erased). The Java equivalent:
`SpringApplication.run(InventoryApplication.class, args)`.

**Kotlin: spread operator.** `runApplication` takes `vararg args: String`, so inside the
function `args` is an `Array<String>`, but *callers* must pass individual `String`s. Passing
`args` without the `*` doesn't compile: an `Array<String>` isn't a `String`. `*args` spreads the
array's elements into the vararg. (Java accepts an array where a vararg is expected; Kotlin makes
you say so. You can also mix: `runApplication<X>("--debug", *args)`.)

**Spring:** `runApplication` creates the application context. It runs auto-configuration,
creates every bean (including the Flyway bean, which applies migrations while starting up),
starts Tomcat on port 8080, and then returns. The JVM stays alive because Tomcat's threads
are still running.

## Check your understanding

- If `InventoryController` were put in package `com.robertwoolbright.web`, would Spring find it? Why?
- What is the name of the JVM class that contains `main`, and why?
- Why doesn't `runApplication<InventoryApplication>(args)` (no `*`) compile?
