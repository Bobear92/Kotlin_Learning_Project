# Kotlin & Spring Concepts Index

A running index of every Kotlin and Spring concept used in this codebase. Each row
links to where the concept first appears. `/walkthrough` adds rows as new concepts
come up. For the full explanation, read the tagged comments in the code:

```sh
grep -rn "// Kotlin:" src    # language features
grep -rn "// Spring:" src    # framework behavior
grep -rn "// Why:" src       # design decisions
grep -rn "// Contract:" src  # lines that satisfy the OpenAPI contract
```

## Kotlin

| Concept | What it is | First seen |
| --- | --- | --- |
| Trailing lambdas / type-safe builders | A lambda passed last can sit outside `()`; Gradle's `plugins { }` blocks are function calls | [build.gradle.kts](../../build.gradle.kts) |
| Final by default | Classes and functions are `final` unless `open`; the `plugin.spring` compiler plugin opens Spring beans | [build.gradle.kts](../../build.gradle.kts) |
| Platform types and `-Xjsr305=strict` | How Java nullability annotations become real `T` / `T?` types | [build.gradle.kts](../../build.gradle.kts) |
| Class with no body | `class InventoryApplication` needs no braces | [InventoryApplication.kt](../../src/main/kotlin/com/robertwoolbright/inventory/InventoryApplication.kt) |
| Top-level functions | `fun main` lives outside any class; compiled into `InventoryApplicationKt` | [InventoryApplication.kt](../../src/main/kotlin/com/robertwoolbright/inventory/InventoryApplication.kt) |
| Reified type parameters | `runApplication<T>()`, `withType<Test>`: the type in `<>` instead of `T::class.java` | [InventoryApplication.kt](../../src/main/kotlin/com/robertwoolbright/inventory/InventoryApplication.kt) |
| Spread operator `*` | Pass an array's elements as a vararg | [InventoryApplication.kt](../../src/main/kotlin/com/robertwoolbright/inventory/InventoryApplication.kt) |
| Named arguments | `proxyBeanMethods = false`, also in annotations | [TestcontainersConfiguration.kt](../../src/test/kotlin/com/robertwoolbright/inventory/support/TestcontainersConfiguration.kt) |
| Expression bodies | `fun f(): T = expr` instead of `{ return expr }` | [TestcontainersConfiguration.kt](../../src/test/kotlin/com/robertwoolbright/inventory/support/TestcontainersConfiguration.kt) |
| Class references `X::class` | Kotlin's version of Java's `X.class` | [InventoryApplicationTests.kt](../../src/test/kotlin/com/robertwoolbright/inventory/InventoryApplicationTests.kt) |
| `internal` vs package-private | Kotlin has no package-private; `internal` = same Gradle module | [README](../../README.md#project-layout) |

## Spring

| Concept | What it is | First seen |
| --- | --- | --- |
| `@SpringBootApplication` | `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` from this package down | [InventoryApplication.kt](../../src/main/kotlin/com/robertwoolbright/inventory/InventoryApplication.kt) |
| Starters and the Boot BOM | Starters bundle dependencies; the dependency-management plugin supplies versions | [build.gradle.kts](../../build.gradle.kts) |
| Docker Compose support | `bootRun` starts compose.yaml and wires the DataSource | [compose.yaml](../../compose.yaml) |
| `@TestConfiguration` + `@Import` | Test-only beans that component scanning skips | [TestcontainersConfiguration.kt](../../src/test/kotlin/com/robertwoolbright/inventory/support/TestcontainersConfiguration.kt) |
| `@ServiceConnection` | A Testcontainers container supplies DataSource connection details | [TestcontainersConfiguration.kt](../../src/test/kotlin/com/robertwoolbright/inventory/support/TestcontainersConfiguration.kt) |
| `@SpringBootTest` | Starts the full application context in a test | [InventoryApplicationTests.kt](../../src/test/kotlin/com/robertwoolbright/inventory/InventoryApplicationTests.kt) |
