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
| Primary constructor properties | `class X(private val dep: Dep)`: declares and assigns a property in the constructor | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| Read-only `List` vs `MutableList` | `List` has no `add`/`remove`; callers get a snapshot | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| Lambdas, `_` and SAM conversion | `{ rs, _ -> ... }` stands in for Java's single-method `RowMapper`; `_` = unused parameter | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| A lambda's last expression is its return value | `.query { }` / `.map { }` call the lambda once per element and collect what it returns | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| KDoc vs `// Why:` | KDoc (`/** */`) says what, for callers, shown on hover; `// Why:` says why, for maintainers | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| JSpecify nullability from Java | Spring 7's `@Nullable` makes `query(Class)` yield `T?`; a row-mapper lambda keeps `T` non-null | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| `data class` | Generated `equals`/`hashCode`/`toString`/`copy`/`componentN` from constructor properties; Kotlin's Java `record` | [InventoryItem.kt](notes/main/inventory/InventoryItem.kt.md) |
| `==` vs `===`, and `copy` | `==` calls `equals` (value equality for data classes); `===` is identity. `copy` returns a new object and leaves the original alone | [InventoryItem.kt](notes/main/inventory/InventoryItem.kt.md) |
| No checked exceptions | Kotlin has no `throws` clause; unchecked `DataAccessException` travels up to a `500` | [InventoryService.kt](notes/main/inventory/InventoryService.kt.md) |
| Nullable types `T?` | `InventoryItem?` = an item or `null`; the compiler makes you handle `null` before use. Kotlin's built-in `Optional` | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| `singleOrNull` / `single` / `firstOrNull` | Zero-or-one lookups on a list; `singleOrNull` returns `null` (no exception) for 0 *or* 2+ elements | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| Elvis operator `?:` | `a ?: b` = `a` unless it's `null`, then `b` | [InventoryService.kt](notes/main/inventory/InventoryService.kt.md) |
| `throw` as an expression, `Nothing` | `x ?: throw E()` has the non-null type of `x`; `Nothing` fits any type | [InventoryService.kt](notes/main/inventory/InventoryService.kt.md) |
| KDoc block tags (`@throws`) | Tags start their own line; `@throws` is how a signature announces an exception without checked exceptions | [InventoryService.kt](notes/main/inventory/InventoryService.kt.md) |
| Extending a class | `class A(x: T) : B(...)` = `extends B` plus the `super(...)` call | [SkuNotFoundException.kt](notes/main/inventory/error/SkuNotFoundException.kt.md) |
| Plain constructor parameters | No `val`/`var` = usable only during construction, not a property | [SkuNotFoundException.kt](notes/main/inventory/error/SkuNotFoundException.kt.md) |
| Storage vs visibility | `val` decides whether a constructor parameter is kept; `private` decides who can read it. `private val` enforces the layers | [SkuNotFoundException.kt](notes/main/inventory/error/SkuNotFoundException.kt.md) |
| No ternary; `if` is an expression | Java's `c ? a : b` is `if (c) a else b`; `?:` (Elvis) only tests for `null` | [InventoryService.kt](notes/main/inventory/InventoryService.kt.md) |
| String templates | `"SKU not found: $skuId"`, and `${expr}` for anything longer than a name | [SkuNotFoundException.kt](notes/main/inventory/error/SkuNotFoundException.kt.md) |
| Nullable values from Java | `Throwable.message` is `String?` because `getMessage()` may return null | [InventoryExceptionHandler.kt](notes/main/inventory/error/InventoryExceptionHandler.kt.md) |
| Default arguments | `fun f(x: T = default)`: callers may leave the argument out; replaces Java overloads | [InventoryControllerTest.kt](notes/test/inventory/InventoryControllerTest.kt.md) |
| Array literals in annotations | `@ValueSource(ints = [0, -5])`; Java writes `{0, -5}` | [InventoryControllerTest.kt](notes/test/inventory/InventoryControllerTest.kt.md) |
| Generic functions and function types | `fun <T> race(action: (Int) -> T)`: a type parameter plus a parameter that holds a function | [InventoryConcurrencyTest.kt](notes/test/inventory/InventoryConcurrencyTest.kt.md) |
| Ranges `..` and `until` | `1..n` includes `n`, `0 until n` excludes it; both work with `map`, `sum` | [InventoryConcurrencyTest.kt](notes/test/inventory/InventoryConcurrencyTest.kt.md) |
| `runCatching` and `Result` | Runs a block and captures success or exception as a value, nothing thrown | [InventoryConcurrencyTest.kt](notes/test/inventory/InventoryConcurrencyTest.kt.md) |
| Implicit lambda parameter `it` | A one-parameter lambda can leave the parameter out and use `it` | [InventoryConcurrencyTest.kt](notes/test/inventory/InventoryConcurrencyTest.kt.md) |
| Safe-call operator `?.` | `a?.b` is `null` instead of an NPE when `a` is `null`; chains end in one `?:` fallback | [InventoryExceptionHandler.kt](notes/main/inventory/error/InventoryExceptionHandler.kt.md) |
| `require` / `check` | `require(cond) { msg }` throws `IllegalArgumentException`; the message lambda runs only on failure | [InventoryService.kt](notes/main/inventory/InventoryService.kt.md) |
| Block body vs expression body | `= expr` holds one expression; two steps (check, then act) read better as `{ }` with an explicit `return` | [InventoryService.kt](notes/main/inventory/InventoryService.kt.md) |
| Class with no constructor parameters | `class E : RuntimeException("msg")`, created with `E()` | [AddStockOverflowException.kt](notes/main/inventory/error/AddStockOverflowException.kt.md) |
| Sealed interfaces | `sealed interface` closes the list of implementations (same package + module), so a `when` over it can be exhaustive; Java 17's `sealed ... permits` | [PurchaseResult.kt](notes/main/inventory/PurchaseResult.kt.md) |
| `object` and `data object` | A class with exactly one instance, used by name without `()`; `data` adds a readable `toString` | [PurchaseResult.kt](notes/main/inventory/PurchaseResult.kt.md) |
| Nested classes | A class declared inside another is referenced as `Outer.Inner`; like a Java `static` nested class unless marked `inner` | [PurchaseResult.kt](notes/main/inventory/PurchaseResult.kt.md) |
| `when` without a subject | `when { cond -> value }`: an `if`/`else if` chain, checked top to bottom, `else` required when used as a value | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| Smart casts | After `x != null` or `x is T`, the compiler treats a variable nothing can change in between (e.g. a local `val`) as the narrower type in that branch | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| `Int::class.java` vs `javaObjectType` | `.java` is the primitive `int.class`, `.javaObjectType` the boxed `Integer.class` (can hold `null`) | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| KDoc links `[Name]` | Square brackets in KDoc link to a declaration; Javadoc's `{@link}` | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| `when` with a subject, `is` and exhaustiveness | `when (val x = ...) { is A -> ...; B -> ... }`: type checks and equality; no `else` needed over a sealed type | [InventoryService.kt](notes/main/inventory/InventoryService.kt.md#purchaseinventory-one-branch-per-outcome) |
| `filterIsInstance<T>()` | Keeps the elements of type `T` and returns a `List<T>`: a smart cast for a whole list | [InventoryConcurrencyTest.kt](notes/test/inventory/InventoryConcurrencyTest.kt.md#the-same-sku-purchase-race) |
| `return` and a line break | A line break after a bare `return` ends the statement; the value has to start on the same line | [InventoryService.kt](notes/main/inventory/InventoryService.kt.md#purchaseinventory-one-branch-per-outcome) |

## Spring

| Concept | What it is | First seen |
| --- | --- | --- |
| `@SpringBootApplication` | `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` from this package down | [InventoryApplication.kt](notes/main/InventoryApplication.kt.md) |
| Starters and the Boot BOM | Starters bundle dependencies; the dependency-management plugin supplies versions | [build.gradle.kts](notes/build.gradle.kts.md) |
| Docker Compose support | `bootRun` starts compose.yaml and wires the DataSource | [compose.yaml](notes/compose.yaml.md) |
| Property source precedence | Command line > environment variables > application.yml > defaults; relaxed binding maps `SPRING_DATASOURCE_URL` to `spring.datasource.url` | [application.yml](notes/main/resources/application.yml.md) |
| YAML map properties | `spring.jackson.deserialization:` binds its children into a `Map<Feature, Boolean>`; each indent level is one segment of the dotted name | [application.yml](notes/main/resources/application.yml.md#strict-json-the-jackson-block) |
| Strict JSON (Jackson 3) | `accept-float-as-int` / `allow-coercion-of-scalars` off, so `1.5` and `"5"` are 400s instead of guesses; a non-null `Int` rejects `{}` and `null` | [application.yml](notes/main/resources/application.yml.md#strict-json-the-jackson-block) |
| Bean Validation starter | `spring-boot-starter-validation`: `jakarta.validation` annotations + Hibernate Validator, run on `@Valid @RequestBody` | [build.gradle.kts](notes/build.gradle.kts.md) |
| Connection details | Compose / Testcontainers supply the DB connection, overriding `spring.datasource.*` | [application.yml](notes/main/resources/application.yml.md) |
| Test context caching | Identical test configurations share one context (and container) until the run ends | [TestcontainersConfiguration.kt](notes/test/support/TestcontainersConfiguration.kt.md) |
| `@TestConfiguration` + `@Import` | Test-only beans that component scanning skips | [TestcontainersConfiguration.kt](notes/test/support/TestcontainersConfiguration.kt.md) |
| `@ServiceConnection` | A Testcontainers container supplies DataSource connection details | [TestcontainersConfiguration.kt](notes/test/support/TestcontainersConfiguration.kt.md) |
| `@SpringBootTest` | Starts the full application context in a test | [InventoryApplicationTests.kt](notes/test/InventoryApplicationTests.kt.md) |
| Exception translation | SQL errors become `DataAccessException`s, e.g. `DataIntegrityViolationException` | [InventoryRepositoryTest.kt](notes/test/inventory/InventoryRepositoryTest.kt.md) |
| `@JdbcTest` slice | JDBC-only context, no component scan, each test rolled back | [InventoryRepositoryTest.kt](notes/test/inventory/InventoryRepositoryTest.kt.md) |
| `@AutoConfigureMockMvc` + Kotlin MockMvc DSL | In-process HTTP requests; `mockMvc.get(...).andExpect { }` | [InventoryControllerTest.kt](notes/test/inventory/InventoryControllerTest.kt.md) |
| `JsonCompareMode.STRICT` | Exact JSON match: no extra fields, array order matters | [InventoryControllerTest.kt](notes/test/inventory/InventoryControllerTest.kt.md) |
| Flyway migrations | `V<n>__<desc>.sql` (two underscores) in `db/migration`, run once at startup, checksummed; wrong names are skipped silently | [V1__create_inventory.sql](notes/main/resources/db/migration/V1__create_inventory.sql.md) |
| Migrations are changes, not snapshots | A schema change is a new `V2__` with `ALTER TABLE`; the schema is the whole V1→Vn chain | [V1__create_inventory.sql](notes/main/resources/db/migration/V1__create_inventory.sql.md) |
| Component scanning and stereotypes | `@Repository`/`@Service`/`@RestController` make scanned classes beans; `@Import` registers one by hand | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| Constructor injection | A single constructor is autowired without `@Autowired`; beans are created at startup | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| `JdbcClient` query chain | `sql(...)` prepares, `query { row mapper }` maps, `list()` runs and collects | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| `@RestController` vs `@Controller` | `@RestController` = `@Controller` + `@ResponseBody`: return values are written as the body, not rendered as views | [InventoryController.kt](notes/main/inventory/InventoryController.kt.md) |
| `@RequestMapping` + `@GetMapping` | Class-level base path plus method-level mappings; the function name doesn't matter | [InventoryController.kt](notes/main/inventory/InventoryController.kt.md) |
| Message converters (Jackson) | A `@ResponseBody` value is written as JSON; Kotlin property names become JSON keys | [InventoryController.kt](notes/main/inventory/InventoryController.kt.md) |
| The service layer | Business rules and error mapping sit between HTTP (controller) and SQL (repository) | [InventoryService.kt](notes/main/inventory/InventoryService.kt.md) |
| Singleton beans and threads | One bean instance, built at startup, serves every request thread at once, so beans must be stateless | [InventoryController.kt](notes/main/inventory/InventoryController.kt.md) |
| Named parameters in SQL | `:skuId` + `.param("skuId", value)` binds the value separately, preventing SQL injection | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md) |
| `@PathVariable` and path templates | `@GetMapping("/{skuId}")` matches one path segment and binds it to the same-named parameter | [InventoryController.kt](notes/main/inventory/InventoryController.kt.md) |
| Domain exceptions | The service throws a domain error (`SkuNotFoundException`); a handler decides the HTTP status | [SkuNotFoundException.kt](notes/main/inventory/error/SkuNotFoundException.kt.md) |
| `@RestControllerAdvice` + `@ExceptionHandler` | One class that catches exceptions from every controller; within a class the closest exception type wins, across advice classes their `@Order` decides first | [InventoryExceptionHandler.kt](notes/main/inventory/error/InventoryExceptionHandler.kt.md) |
| Exception resolver chain | Spring catches every controller exception: `@ExceptionHandler` (controller, then advice) → `@ResponseStatus` → built-in mappings → `500` | [InventoryExceptionHandler.kt](notes/main/inventory/error/InventoryExceptionHandler.kt.md) |
| When mistakes surface | Compile time (types), startup (missing beans), first request (strings: paths, SQL, missing advice); only request-level tests catch the last | [InventoryController.kt](notes/main/inventory/InventoryController.kt.md) |
| `ResponseEntity` builder | `status(...).contentType(...).body(...)` sets status, headers and body; `notFound()` has no body | [InventoryExceptionHandler.kt](notes/main/inventory/error/InventoryExceptionHandler.kt.md) |
| `@ParameterizedTest` + `@ValueSource` | One test method run once per supplied value, each reported separately | [InventoryControllerTest.kt](notes/test/inventory/InventoryControllerTest.kt.md) |
| `@RepeatedTest` | Runs a test N times; used for intermittent concurrency bugs | [InventoryConcurrencyTest.kt](notes/test/inventory/InventoryConcurrencyTest.kt.md) |
| Test transactions are per thread | A `@JdbcTest` rollback covers only the test thread; worker threads commit on their own connections | [InventoryConcurrencyTest.kt](notes/test/inventory/InventoryConcurrencyTest.kt.md) |
| Start gate (`CountDownLatch`) | Hold N threads at one line, then release them together so they really overlap | [InventoryConcurrencyTest.kt](notes/test/inventory/InventoryConcurrencyTest.kt.md) |
| Request processing order | Routing → `@RequestBody` (Content-Type, then Jackson) → `@Valid` → the handler function; a bad body is a `400` before any database lookup could give a `404` | [InventoryController.kt](notes/main/inventory/InventoryController.kt.md#post-inventoryskuidpurchase) |
| `@PostMapping` + `@RequestBody` | Map a POST; Jackson reads the JSON body into a class through its primary constructor | [InventoryController.kt](notes/main/inventory/InventoryController.kt.md) |
| `@Valid` + Bean Validation (`@Min`) | Constraints on a request class run only when the parameter has `@Valid`; failures throw `MethodArgumentNotValidException` | [InventoryQuantity.kt](notes/main/inventory/InventoryQuantity.kt.md) |
| Request body failure order | Content-Type → JSON read → validation → the function; each failure is its own Spring exception | [InventoryController.kt](notes/main/inventory/InventoryController.kt.md) |
| Handling Spring's own exceptions | Advice handlers run before Spring's built-in resolver, so they can replace its `415`/empty `400`; never send Spring's `ex.message` to clients | [InventoryExceptionHandler.kt](notes/main/inventory/error/InventoryExceptionHandler.kt.md) |
| `@ExceptionHandler` with no parameter | The annotation routes the exception; the parameter is only needed to read it | [InventoryExceptionHandler.kt](notes/main/inventory/error/InventoryExceptionHandler.kt.md) |
| Three layers of checks | `@Valid` for clients (400), `require` for our own callers (500), DB `CHECK` as the backstop | [InventoryService.kt](notes/main/inventory/InventoryService.kt.md) |
| Default response status | A plain return value is `200`; `201` needs `@ResponseStatus` or a `ResponseEntity`. Defaults are invisible in code, so tests pin them | [InventoryController.kt](notes/main/inventory/InventoryController.kt.md#post-inventoryskuid) |
| `@ExceptionHandler` parameters by type | Spring fills a handler's parameter with the thrown exception, matched by type; the name (`ex`) is only a convention | [InventoryExceptionHandler.kt](notes/main/inventory/error/InventoryExceptionHandler.kt.md#handleinsufficientinventory-the-exception-comes-in-as-a-parameter) |
| Fixed path segments and `405` | `"/{skuId}/purchase"` mixes a variable and a literal; a path that matches with the wrong method gets `405` | [InventoryController.kt](notes/main/inventory/InventoryController.kt.md#post-inventoryskuidpurchase) |

## Database and API design

| Concept | What it is | First seen |
| --- | --- | --- |
| Contract schemas are not tables | OpenAPI schemas describe JSON on the wire; tables describe what's stored | [V1__create_inventory.sql](notes/main/resources/db/migration/V1__create_inventory.sql.md) |
| Natural vs surrogate key | `sku_id` as the primary key vs a generated `id` plus a unique `sku_id` | [V1__create_inventory.sql](notes/main/resources/db/migration/V1__create_inventory.sql.md) |
| Constraints as a backstop | `PRIMARY KEY`, `NOT NULL`, `CHECK` protect data even when app code is wrong | [V1__create_inventory.sql](notes/main/resources/db/migration/V1__create_inventory.sql.md) |
| Identifier case folding | Postgres lowercases unquoted names; use `snake_case` in SQL, `camelCase` in Kotlin | [V1__create_inventory.sql](notes/main/resources/db/migration/V1__create_inventory.sql.md) |
| Upsert and `EXCLUDED` | `INSERT ... ON CONFLICT DO UPDATE`; `EXCLUDED` is the row the insert tried to add | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md#the-upsert) |
| `RETURNING` | A write returns the changed row in the same statement; no second `SELECT` | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md#the-upsert) |
| Lost update (find-then-write race) | Two requests read the same value and the second write erases the first; one atomic statement prevents it | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md#addstock-the-signature-and-the-two-decisions) |
| Overflow-safe guard | `current <= MAX - new` instead of `current + new <= MAX`, which itself overflows | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md#the-overflow-guard) |
| Data-modifying CTE | `WITH x AS (UPDATE ... RETURNING ...) SELECT ...`: a write and a read in one atomic statement | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md#the-sql-a-data-modifying-cte) |
| One snapshot per statement | Every part of a statement sees the data as it was when it started (except that an `UPDATE` re-checks rows changed by concurrent commits); only `RETURNING` shows the CTE's change | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md#the-sql-a-data-modifying-cte) |
| Scalar subquery, `EXISTS`, `SELECT 1` | `(SELECT ...)` as a value is `NULL` for no rows; `EXISTS` is `true`/`false`, never `NULL` | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md#the-sql-a-data-modifying-cte) |
| Conditional `UPDATE` under concurrency | Under `READ COMMITTED`, a waiting `UPDATE` re-checks its `WHERE` on the committed row, so two buyers can't both take the last unit | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md#the-sql-a-data-modifying-cte) |
| JDBC `NULL` vs `0` | `getInt` returns `0` for `NULL`; `getObject(col, Int::class.javaObjectType)` keeps `null` | [InventoryRepository.kt](notes/main/inventory/InventoryRepository.kt.md#reading-the-row-null-is-not-0) |
