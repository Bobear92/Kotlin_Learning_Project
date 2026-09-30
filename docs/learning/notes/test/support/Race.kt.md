# Race.kt

> Source: [src/test/kotlin/com/robertwoolbright/inventory/support/Race.kt](../../../../../src/test/kotlin/com/robertwoolbright/inventory/support/Race.kt) · Last synced with code: 2026-09-30

## Role

Shared test helper for the oversell test plan in [TODO.md](../../../../../.claude/TODO.md). It
starts N threads at the same moment and collects every thread's outcome. Used by
[InventoryConcurrencyTest.kt](../inventory/InventoryConcurrencyTest.kt.md) and
[NegativeControlTest.kt](../inventory/NegativeControlTest.kt.md), and later by the HTTP races.
It began as a private function inside `InventoryConcurrencyTest` and moved here on 2026-09-30.

## Walkthrough

### A top-level function in a shared package

```kotlin
package com.robertwoolbright.inventory.support
...
fun <T> race(threads: Int, action: (Int) -> T): List<Result<T>> {
```

**Kotlin: top-level functions.** A Kotlin function doesn't need a class around it. `race` lives
directly in the file, and other files call it after `import com.robertwoolbright.inventory.support.race`.
The compiler puts it in a generated class named `RaceKt`, so Java would call it as `RaceKt.race(...)`.
Java's closest equivalent is a `public static` method on a utility class such as `RaceHelpers`.

**Why `support`:** the package already holds shared test setup
([TestcontainersConfiguration.kt](TestcontainersConfiguration.kt.md)), and `race` has nothing
specific to inventory in it.

### The start gate

```kotlin
// Why: a start gate. Every thread first checks in on `ready`, then waits on `start`; releasing
// `start` once makes them all hit the database together instead of one after another.
...
fun <T> race(threads: Int, action: (Int) -> T): List<Result<T>> {
    val pool = Executors.newFixedThreadPool(threads)
    val ready = CountDownLatch(threads)
    val start = CountDownLatch(1)
    try {
        val futures = (0 until threads).map { i ->
            pool.submit(Callable {
                ready.countDown()
                start.await()
                action(i)
            })
        }
        ready.await()
        start.countDown()
        return futures.map { runCatching { it.get(30, TimeUnit.SECONDS) } }
    } finally {
        pool.shutdownNow()
    }
}
```

**Why a gate at all:** starting 50 threads one by one in a loop spreads them out in time. The
first threads may finish before the last ones start, and then there's no race to test. The gate
makes them all wait at the same line and releases them together.

**Java concurrency: `CountDownLatch`.** A counter threads can wait on. `countDown()` decrements
it; `await()` blocks until it reaches zero. There are two latches here:

1. `ready` starts at `threads`. Each worker counts it down as it arrives, and the test thread's
   `ready.await()` returns once all of them are waiting.
2. `start` starts at 1. Every worker blocks on `start.await()`. The test's single
   `start.countDown()` releases all of them at once.

**Java concurrency: thread pool and futures.** `Executors.newFixedThreadPool(threads)` makes one
thread per worker, so all of them can wait at the gate together (a smaller pool would deadlock:
the queued tasks could never arrive at `ready`). `submit` returns a `Future`; `get(30, SECONDS)`
waits for its result and rethrows anything the task threw, wrapped in an `ExecutionException`.
The timeout makes a hung test fail instead of running forever. `shutdownNow()` in `finally`
stops the threads even when something failed.

**Kotlin: a generic function.** `fun <T> race(..., action: (Int) -> T): List<Result<T>>` works for
any result type. `T` is whatever `action` returns: `InventoryItem?` for `addStock`,
`PurchaseResult` for `removeStock`, a `Pair` or an `Int` in the purchase races of
`InventoryConcurrencyTest`. Java writes it the same way: `<T> List<Result<T>> race(...)`.

**Kotlin: function types.** `action: (Int) -> T` is a parameter that holds a function taking an
`Int` (the thread's index) and returning a `T`. Java would use `IntFunction<T>` or
`Function<Integer, T>`. The call sites pass a trailing lambda: `race(threads) { i -> ... }`.

**Kotlin: `Callable { ... }`.** `submit` is overloaded for `Runnable` (no result) and `Callable`
(returns a result). Writing `Callable { ... }` picks the one that returns a value. This is a SAM
conversion, like the row mapper in [InventoryRepository.kt](../../main/inventory/InventoryRepository.kt.md).

**Kotlin: ranges.** `0 until threads` is `0, 1, ..., threads - 1` (the end is excluded), and
`.map { i -> ... }` turns each index into a submitted task. Java: `IntStream.range(0, threads)`.

**Kotlin: `runCatching` and `Result`.** `runCatching { block }` runs the block and returns a
`Result`: a success holding the value, or a failure holding the exception. Nothing is thrown.
That lets the test collect *every* thread's outcome first, then assert on all of them together.
`exceptionOrNull()` and `getOrNull()` read the two sides. Java has no built-in equivalent; you'd
catch the exception in a loop and keep it in a list.

## Check your understanding

- What happens to the race if you delete the `start` latch and keep only `ready`? And if you
  delete both?
- Why must the thread pool have `threads` threads, and not, say, 10?
- `race` returns `List<Result<T>>` instead of throwing on the first failure. What would a test
  report lose if it threw?
- How would Java code call `race`, and why does the class name end in `Kt`?
