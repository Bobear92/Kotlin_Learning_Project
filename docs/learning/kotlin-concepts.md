# Kotlin & Spring Concepts Index

A running index of every Kotlin and Spring concept used in this codebase. Each row links to
the study notes that explain it in full (see [notes/README.md](notes/README.md)). `/sync-docs`
and `/walkthrough` add rows as new concepts come up.

## Kotlin

| Concept | What it is | First seen |
| --- | --- | --- |
| Trailing lambdas / type-safe builders | A lambda passed last can sit outside `()`; Gradle's `plugins { }` blocks are function calls | [build.gradle.kts](notes/build.gradle.kts.md) |
| Final by default | Classes and functions are `final` unless `open`; the `plugin.spring` compiler plugin opens Spring beans | [build.gradle.kts](notes/build.gradle.kts.md) |
| Final is not immutable | `open`/`final` controls subclassing and overriding; `val`/`var` controls mutability. A final method under a Spring proxy fails silently | [build.gradle.kts](notes/build.gradle.kts.md) |
| Platform types and `-Xjsr305=strict` | How Java nullability annotations become real `T` / `T?` types | [build.gradle.kts](notes/build.gradle.kts.md) |
| Class with no body | `class InventoryApplication` needs no braces | [InventoryApplication.kt](notes/main/InventoryApplication.kt.md) |
| Top-level functions | `fun main` lives outside any class; compiled into `InventoryApplicationKt` | [InventoryApplication.kt](notes/main/InventoryApplication.kt.md) |
| Reified type parameters | `runApplication<T>()`, `withType<Test>`: the type in `<>` instead of `T::class.java` | [InventoryApplication.kt](notes/main/InventoryApplication.kt.md) |
| Infix functions | `a version b` means `a.version(b)` | [build.gradle.kts](notes/build.gradle.kts.md) |
| `Array<String>` | Kotlin's `String[]`: an ordinary generic class | [InventoryApplication.kt](notes/main/InventoryApplication.kt.md) |
| Spread operator `*` | Pass an array's elements as a vararg | [InventoryApplication.kt](notes/main/InventoryApplication.kt.md) |
| Named arguments | `proxyBeanMethods = false`, also in annotations | [TestcontainersConfiguration.kt](notes/test/support/TestcontainersConfiguration.kt.md) |
| Expression bodies | `fun f(): T = expr` instead of `{ return expr }` | [TestcontainersConfiguration.kt](notes/test/support/TestcontainersConfiguration.kt.md) |
| Class references `X::class` | Kotlin's version of Java's `X.class` | [InventoryApplicationTests.kt](notes/test/InventoryApplicationTests.kt.md) |
| `internal` vs package-private | Kotlin has no package-private; `internal` = same Gradle module | [README](../../README.md#project-layout) |
| Public by default | No modifier means `public` (Java: package-private); helpers must say `private` | [InventoryRepositoryTest.kt](notes/test/inventory/InventoryRepositoryTest.kt.md) |
| `@Autowired constructor` | Annotating the primary constructor needs the `constructor` keyword | [InventoryRepositoryTest.kt](notes/test/inventory/InventoryRepositoryTest.kt.md) |
| Backtick function names | `` fun `reads like a sentence`() `` for test names | [InventoryRepositoryTest.kt](notes/test/inventory/InventoryRepositoryTest.kt.md) |
| `kotlin.test` assertions | `assertEquals`, `assertTrue`, `assertFailsWith<T> { }` | [InventoryRepositoryTest.kt](notes/test/inventory/InventoryRepositoryTest.kt.md) |
| Extension functions | `fun MockMvc.get(...)`: a function added to a class from outside it | [InventoryControllerTest.kt](notes/test/inventory/InventoryControllerTest.kt.md) |
| Raw strings `"""` | Multi-line strings without escaping; `trimIndent()` to strip indentation | [InventoryControllerTest.kt](notes/test/inventory/InventoryControllerTest.kt.md) |

## Spring

| Concept | What it is | First seen |
| --- | --- | --- |
| `@SpringBootApplication` | `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` from this package down | [InventoryApplication.kt](notes/main/InventoryApplication.kt.md) |
| Starters and the Boot BOM | Starters bundle dependencies; the dependency-management plugin supplies versions | [build.gradle.kts](notes/build.gradle.kts.md) |
| Docker Compose support | `bootRun` starts compose.yaml and wires the DataSource | [compose.yaml](notes/compose.yaml.md) |
| Property source precedence | Command line > environment variables > application.yml > defaults; relaxed binding maps `SPRING_DATASOURCE_URL` to `spring.datasource.url` | [application.yml](notes/main/resources/application.yml.md) |
| Connection details | Compose / Testcontainers supply the DB connection, overriding `spring.datasource.*` | [application.yml](notes/main/resources/application.yml.md) |
| Test context caching | Identical test configurations share one context (and container) until the run ends | [TestcontainersConfiguration.kt](notes/test/support/TestcontainersConfiguration.kt.md) |
| `@TestConfiguration` + `@Import` | Test-only beans that component scanning skips | [TestcontainersConfiguration.kt](notes/test/support/TestcontainersConfiguration.kt.md) |
| `@ServiceConnection` | A Testcontainers container supplies DataSource connection details | [TestcontainersConfiguration.kt](notes/test/support/TestcontainersConfiguration.kt.md) |
| `@SpringBootTest` | Starts the full application context in a test | [InventoryApplicationTests.kt](notes/test/InventoryApplicationTests.kt.md) |
| Exception translation | SQL errors become `DataAccessException`s, e.g. `DataIntegrityViolationException` | [InventoryRepositoryTest.kt](notes/test/inventory/InventoryRepositoryTest.kt.md) |
| `@JdbcTest` slice | JDBC-only context, no component scan, each test rolled back | [InventoryRepositoryTest.kt](notes/test/inventory/InventoryRepositoryTest.kt.md) |
| `@AutoConfigureMockMvc` + Kotlin MockMvc DSL | In-process HTTP requests; `mockMvc.get(...).andExpect { }` | [InventoryControllerTest.kt](notes/test/inventory/InventoryControllerTest.kt.md) |
| `JsonCompareMode.STRICT` | Exact JSON match: no extra fields, array order matters | [InventoryControllerTest.kt](notes/test/inventory/InventoryControllerTest.kt.md) |
