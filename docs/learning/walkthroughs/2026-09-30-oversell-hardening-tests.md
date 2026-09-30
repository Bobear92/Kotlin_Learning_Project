# Walkthrough: oversell hardening and contract tests (partial)
- Date: 2026-09-30
- Covers: 69b82b2..da71988 (plus uncommitted: yes, the load test and doc-review fixes)
- Files: build.gradle.kts, support/Race.kt, OversellAssertions.kt, NaiveInventoryRepository.kt,
  NegativeControlTest.kt (covered); InventoryConcurrencyTest.kt (refactor only),
  InventoryContractTest.kt, InventoryPropertyTest.kt, support/InventoryHttpClient.kt,
  MultiInstanceRaceTest.kt, InventoryLoadTest.kt (not reached)
- Pace: "focused" (Robert). Stopped after NegativeControlTest to ship within the time left.

## Concepts covered
- JUnit tags + a custom Gradle `Test` task: `excludeTags("load")` keeps the load test out of `test`/`build`; `loadTest` includes only it; `upToDateWhen { false }` forces a fresh run.
- Top-level functions: `race` lives in a file, not a class; Java sees it as `RaceKt.race`.
- `List<Result<T>>`: collect every thread's outcome instead of stopping at the first failure.
- Shared invariant check: the negative control is only convincing because it uses the same `assertNoOversell` as the real test.
- `require` (the test is set up wrong) vs. assertions (the code under test is wrong).
- Assertions stop at the first failure; order decides which message you see.
- Function types `() -> Unit`, `private val` stores a constructor argument as a property.
- Lost update: an absolute write from a stale read overwrites another buyer's sale; the gap is the whole read→write span, the pause only widens it.
- `CyclicBarrier` (meet in the middle) vs. `CountDownLatch` (start gate).

## Questions Robert asked
- (none; answered check questions only)

## Understanding checks
- Does `./gradlew build` run the load test? → Correct (no). Clarified: the `excludeTags("load")` line decides; without `upToDateWhen { false }` Gradle would skip the task as UP-TO-DATE rather than "use old data".
- Why `List<Result<T>>`? → Said "we'd lose the exception info". Clarified: the exception would still arrive; what's lost is every *other* thread's outcome.
- Naive run through the four checks → Thought check 1 "passes, it looks like we oversold" and check 3 needs an exception. Clarified: an assertion *fails* when its condition is broken; check 3 counts refusals (expected 2, got 0). All four fail; only the first (`oversold`) is reported.
- Move `final == 0` to the top → Correct that it fails first; clarified that the negative control then fails its `startsWith("oversold")` check (caught for the wrong reason).
- Explain NaiveInventoryRepository → src/test and no-bean answers correct. `() -> Unit` = "void" (added: it's a stored function, takes no arguments). Thought `private val` relates to the return type; clarified `val` = keep as a property, `private` = visibility.
- Walk `removeStock` → Correct six steps and located the gap at the pause. Clarified: the gap is the whole read→write span; the absolute write *erases* other sales, not just returns a stale number.

## Follow-ups
- Finish the walkthrough: NegativeControlTest check question (what would a failing control 1 mean?), then InventoryContractTest, InventoryPropertyTest (shrinking, `runBlocking`), InventoryHttpClient, MultiInstanceRaceTest (interview-heavy), InventoryLoadTest.
- Reinforce: assertion semantics ("the assertion states what must be true"), and `val` in constructors.
- Two interview-prep questions added (negative control; multiple instances).
