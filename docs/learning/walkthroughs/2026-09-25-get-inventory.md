# Walkthrough: GET /inventory

- Date: 2026-09-25
- Covers: cf86d35..b150acb (plus uncommitted: yes, note improvements made during this session)
- Files: V1__create_inventory.sql, InventoryController.kt, InventoryService.kt, InventoryRepository.kt,
  InventoryItem.kt (all written by Robert; he explained each first), InventoryRepositoryTest.kt,
  InventoryControllerTest.kt (written by Claude; Claude explained)

## Concepts covered

- Primary key = `UNIQUE` + `NOT NULL` + index; natural key; `TEXT` compares case-sensitively; `CHECK` as the oversell backstop.
- Flyway migrations are a chain of changes: a new column is `V2__...` with `ALTER TABLE`; editing an applied V1 fails startup with a checksum mismatch.
- `@RestController` = `@Controller` + `@ResponseBody`; Jackson writes the return value as JSON with property names as keys.
- Class-level `@RequestMapping` is a path prefix; `@GetMapping("/{skuId}")` would map to `GET /inventory/{skuId}`.
- Beans are singletons built once at startup, bottom-up through constructor injection; one object serves every request thread, so beans must be stateless.
- The controller doesn't sort: ordering comes from the repository's `ORDER BY sku_id` (by collation, not strictly A-Z).
- Expression bodies; no checked exceptions; why not catching the DB exception is honest (500, not a false `[]`); why the service layer exists.
- Missing `@Service` fails fast at startup ("required a bean of type ... that could not be found").
- `JdbcClient` chain: `.sql` stores SQL, `.query` stores the row recipe, `.list()` runs and collects.
- Lambdas return their last expression; `_` is an unused parameter; the row-mapper lambda fixes a compile-time type problem, not a data problem.
- `data class` generates `equals`/`hashCode`/`toString`/`copy`/`componentN`; `data` doesn't mean immutable, `val` does.
- `==` calls `equals` (value vs identity); `copy` returns a new object and leaves the original unchanged.
- Test slices vs full context: `@JdbcTest` (rollback per test) vs `@SpringBootTest` + MockMvc (`@BeforeEach` cleanup), `@Autowired constructor`, Kotlin MockMvc DSL, raw strings, `JsonCompareMode.STRICT`.

## Questions Robert asked

- None beyond the understanding checks; he asked to go file by file along the request path.

## Understanding checks

- New `created_at` column after V1 ran → "new V2 file" (right), but thought V2 recreates the table and the newest file is the source of truth. Clarified: migrations are changes like git commits (`ALTER TABLE`), the schema is the whole chain. Then answered correctly that editing V1 errors at startup.
- Two simultaneous requests: how many controllers? → first said two objects on two threads. Re-explained (one ticket window, many customers; identityHashCode experiment). Re-check answered correctly: one object regardless of threads. Clarified that `val`-only fields are what make it safe.
- Remove `@Service` → correct: startup fails with a bean error (self-corrected from "fails on request").
- Repository chain and lambda → partial: thought `_` drives iteration and that the lambda returns nothing. Re-explained with a `Book`/`.map` trace table; he then answered `{ book -> book.year }` gives 1965, 1815 (List<Int>) correctly.
- Owed from session 02: `InventoryItem("widget", 5) == InventoryItem("widget", 5)` → first said "error, same skuId" (confused with the DB primary key). Clarified `==` is an in-memory `equals`: true with `data`, false without. Then `copy` check: `a == b` false (right); first said `a.quantity` becomes 6, corrected to 5 (copy makes a new object); answered `b` = widget, 6 correctly.
- `WHERE quantity > 0` added to `findAll` → right on ordering (pass) and zero stock (fail); wrong on empty list (thought fail) and backstop (right result, wrong reason). Lesson: trace which code each test actually calls.
- Rename `skuId` → `sku` → said no test fails. Clarified: the STRICT JSON test fails (Jackson writes `"sku"`); 1 of 7.

## Follow-ups

- Folded into notes: migrations-as-changes (V1 notes), singleton/threads/stateless (controller notes), chain table + lambda return values + `.map` example + collation (repository notes), `==`/`copy`/`data` ≠ immutable (InventoryItem notes), predicting test failures (repository test notes), rename breaks the STRICT test (controller test notes).
- Revisit before the interview: singleton beans vs threads, and lambda return values. Both needed a second explanation.
- Optional exercises: the `identityHashCode` experiment; comment out `@Service` and run `.\gradlew.bat test` to read the startup error.
- Optional polish (Robert's call): `InventoryService` class KDoc wording.
