# InventoryController.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryController.kt](../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryController.kt) · Last synced with code: 2026-09-27

## Role

The web layer, written by Robert, and the top of the request flow: **controller** → service →
repository. It turns HTTP requests for `GET /inventory` and `GET /inventory/{skuId}` into calls
to the [service](InventoryService.kt.md), and hands the result back to Spring to write as JSON. It
holds no logic of its own. Not even the `404`: that comes from
[`InventoryExceptionHandler`](error/InventoryExceptionHandler.kt.md). [InventoryControllerTest](../../test/inventory/InventoryControllerTest.kt.md)
checks it end to end through MockMvc.

## Walkthrough

### Imports

```kotlin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
```

**Spring:** all four annotations come from Spring MVC's `web.bind.annotation` package, which is
on the classpath through `spring-boot-starter-webmvc` (see [build.gradle.kts](../../build.gradle.kts.md)).

### A REST controller mapped to `/inventory`

```kotlin
/** Controller for the inventory flow. HTTP entry point for the inventory endpoints; calls InventoryService. */
@RestController
@RequestMapping("/inventory")
class InventoryController (
    private val inventoryService: InventoryService
){
```

**Spring (`@RestController`):** this is `@Controller` plus `@ResponseBody`, in one annotation.
- `@Controller` is a stereotype, so component scanning makes the class a bean (like `@Service`),
  and Spring MVC scans it for request mappings.
- `@ResponseBody` says "a function's return value **is** the response body". Spring passes it to
  an HTTP message converter, here Jackson, which writes it as JSON.

With only `@Controller`, Spring would try to render an HTML **view** instead of writing the
body. A returned `String` is taken as the view's name. A returned object, like this list, is put
in the model for the view to use, and the view name comes from the URL (`inventory`). There is no
such template in this project, so the request fails with a `500`. That's why a JSON API uses
`@RestController`.

**Spring (`@RequestMapping` on the class):** sets a base path for every handler in this class.
Each function's mapping is added to the end of it. Robert chose this over putting the full path on
each function. It's the usual Spring pattern, and it fits the next endpoint:
`getInventory`'s `@GetMapping("/{skuId}")` maps to `/inventory/{skuId}` ([below](#get-inventoryskuid)).

**Kotlin:** constructor injection of the service, the same pattern as the
[repository](InventoryRepository.kt.md#a-spring-bean-with-constructor-injection). The space in
`InventoryController (` is legal and changes nothing.

**Spring (one object, many threads):** Spring builds this controller **once, at startup**, not per
request. Component scanning finds the stereotypes, and Spring builds the beans bottom-up
(`JdbcClient` → repository → service → controller), passing each into the next constructor.
After that there is exactly one `InventoryController` (a *singleton*). When requests arrive,
Tomcat gives each one its own **thread** (up to 200 by default; the rest queue), and every thread
calls `listInventory()` on that **same object**. More requests means more threads, not more objects.

That's why Spring beans must be **stateless**. A `var lastResult` field would be shared by every
thread: request A writes it, request B overwrites it, and A reads B's data. That's a **race
condition**, and it only shows up intermittently. This class only holds a `private val` reference
that never changes after startup, so it's safe. Purchases have the same shape of risk (read stock,
check, write), which is why stock changes are single atomic SQL statements.

To see it yourself: temporarily add `println(System.identityHashCode(this))` to `listInventory()`,
call the endpoint twice with `bootRun` running, and see the same number both times.

### The `// Contract:` comment

```kotlin
// Contract: paths./inventory.get — 200 application/json array of InventoryItem, [] when empty.
```

**Contract:** this is the file's `// Contract:` comment. It names the exact part of the API contract
the function below exists for, so a reviewer can jump straight to it in `inventory.yaml`:
`paths./inventory.get` (`operationId: listInventory`) promises a `200` with `application/json`, a
body that is an array of `InventoryItem`, and "If no SKUs exist, returns an empty array." The
comment compresses all three into one line.

**Why this wording:** Robert's first version described the endpoint in prose ("The endpoint is a
simple and returns all inventory"). When the code was reviewed (2026-09-25), it was rewritten to name the contract path
and the rules, because a `// Contract:` comment is a pointer into the YAML, not a description.

**Placement:** it sits above the KDoc, separated by a blank line, so the KDoc stays directly on top
of the function it documents.

### `GET /inventory`

```kotlin
/** Returns every SKU and its quantity for GET /inventory.*/
@GetMapping
fun listInventory(): List<InventoryItem> = inventoryService.listInventory()
```

**Spring (`@GetMapping`):** short for `@RequestMapping(method = [RequestMethod.GET])`. With no
path of its own, it maps to the class's base path, so this function handles `GET /inventory`.
A `@GetMapping` also answers `HEAD` (same status and headers, no body), and Spring MVC answers
`OPTIONS` itself with an `Allow` header. Every other method on that path, such as `POST` or
`DELETE /inventory`, gets `405 Method Not Allowed`. Spring
matches paths exactly, and trailing-slash matching has been off by default since Spring 6, so
`GET /inventory/` is a `404`.

The function's **name** doesn't matter to Spring. Only the annotation does. `listInventory`,
`list` or `getInventory` would all be routed the same way. Robert first called it
`findAllInventory` and renamed it to `listInventory` in review, to match the contract's
`operationId: listInventory` and the service function. One name then runs from the YAML through
the controller to the service, which makes the code easy to trace. The tests call the endpoint by
its URL through MockMvc (a simulated HTTP request), never the function by name, so the rename didn't
touch them.

**KDoc style:** the KDoc starts with a verb and says what the function does ("Returns every SKU…"),
not what kind of thing it is ("A function that…"). The signature already shows it's a function.

**Spring (what happens after `return`):**
1. The `DispatcherServlet` receives the request and finds this function through its mapping.
2. The function returns a `List<InventoryItem>`.
3. Because of `@ResponseBody`, Spring picks a message converter that can write a list for the
   client's `Accept` header (MockMvc sends none, which means "anything"). Jackson's JSON
   converter matches, so the response gets `Content-Type: application/json`.
4. Jackson writes the list as a JSON array, one object per item, with the Kotlin property
   names as keys ([see InventoryItem](InventoryItem.kt.md)). An empty list becomes `[]`.

The status is `200 OK` because nothing threw and nothing set another status. If the database
fails, the exception passes through here untouched, and Spring Boot's error handling answers `500`
(see [why the service has no try/catch](InventoryService.kt.md#listinventory)).

**Kotlin:** an [expression body](../../test/support/TestcontainersConfiguration.kt.md#the-container-bean)
again. The return type is written out, even though Kotlin could infer it from `listInventory()`.
On a public function that's the better choice: the signature shows what the endpoint returns
without anyone having to look at the service.

**Contract:** returning `List<InventoryItem>` is what makes the body "an array of `InventoryItem`".
The empty-array rule comes from the repository returning an empty list, and the SKU order from its
`ORDER BY sku_id`, not from anything here. The controller never sorts or filters.
`InventoryControllerTest` checks both cases with `JsonCompareMode.STRICT`.

### The second `// Contract:` comment

```kotlin
// Contract: paths./inventory/{skuId}.get - 200 application/json with InventoryItem, 404 with text/plain when SkuNotFoundException is thrown.
```

**Contract:** `paths./inventory/{skuId}.get` (`operationId: getInventory`) promises a `200` with an
`InventoryItem` as JSON, and a `404` whose body is the `Error` schema (a plain string) as
`text/plain` when "the SKU does not exist". The comment names both, and names the exception that
triggers the `404`, because nothing in this file produces it. A reader following the `404` needs
to know to look for `SkuNotFoundException`, which leads to the handler.

### `GET /inventory/{skuId}`

```kotlin
/** Returns an SKU and its quantity for GET /inventory/{skuId}. */
@GetMapping("/{skuId}")
fun getInventory(@PathVariable skuId: String): InventoryItem = inventoryService.getInventory(skuId)
```

**Spring (path templates):** `"/{skuId}"` is appended to the class's `/inventory`, giving
`/inventory/{skuId}`. The braces mark a **path variable**: that segment matches any text up to the
next `/`. So `/inventory/widget` matches with `skuId = "widget"`. `/inventory/widget/purchase` doesn't
match this mapping (it has one segment too many); that path will get its own mapping later.
`/inventory` alone still goes to `listInventory`, because it has no segment for `{skuId}`.

**Spring (`@PathVariable`):** tells Spring to fill this parameter from the path variable. With no
name given, it uses the **parameter's name**, so the Kotlin name `skuId` must match `{skuId}`
exactly. Spring can read Kotlin parameter names at runtime (the Spring Boot Gradle plugin turns on the
compiler's `javaParameters` option, and Spring can also read Kotlin's own metadata). If the names differed, you'd
write `@PathVariable("skuId") id: String`. Spring also URL-decodes the segment first, so a request
for `/inventory/caf%C3%A9` arrives as `café`.

**When a name mismatch fails** (walkthrough, 2026-09-28): `@GetMapping("/{skuId}")` with
`@PathVariable sku: String` compiles, because `"/{skuId}"` is just a string to the compiler, and the
app starts, because Spring doesn't compare placeholders with parameters when it registers the mapping.
It fails on the first request: Spring throws `MissingPathVariableException` and answers `500`, since
it's a server bug, not a client mistake. Only a test that sends a real request through MockMvc
catches it; calling `controller.getInventory("widget")` directly would skip path matching and pass.

**Kotlin (annotating a parameter):** an annotation can sit directly in front of a parameter,
inside the parentheses, the same as in Java.

**Kotlin + Spring (non-null `String`):** the parameter is `String`, not `String?`. A path variable is
always present when the mapping matches, so it can't be null, and the type says so. (A `String?`
parameter would tell Spring the value is optional.)

**Spring (the exception path):** when the service throws `SkuNotFoundException`, the exception passes
straight out of this function. Nothing is caught here. Spring's `DispatcherServlet` catches it,
asks its exception resolvers whether anyone handles that type, and finds
[`InventoryExceptionHandler.handleSkuNotFound`](error/InventoryExceptionHandler.kt.md). Its
`ResponseEntity` becomes the response. Before the handler existed, nothing matched, so the exception
went up to the servlet container as a `500`. MockMvc has no container, so the tests saw it as a
`ServletException`.

**Why the function stays a one-liner:** Robert chose `@RestControllerAdvice` over building a
`ResponseEntity` here (2026-09-27). The controller keeps the happy path (return an `InventoryItem`,
Spring writes `200` JSON), and error mapping lives in one place for every endpoint. The rejected
version would have been an `if`/`?:` in this function that built a `404` `ResponseEntity`, with the
return type changed to `ResponseEntity<*>`. It can't be `ResponseEntity<InventoryItem>`, because
the error body is a `String`, not an `InventoryItem`, so the type would stop saying what the
endpoint returns.

**Naming:** `getInventory` matches the contract's `operationId: getInventory` and the service
function, the same one-name rule as `listInventory`. Kotlin allows it even though the class also has
a `listInventory`: the names differ. Overloading (two functions with the *same* name and different
parameters) would also be allowed, as in Java.

**Contract:** returning `InventoryItem` gives the `200` body `{"skuId": ..., "quantity": ...}`. A
sold-out SKU is still a row, so it comes back as `200` with `"quantity": 0`, never `404`
(`InventoryControllerTest` checks this).

## Check your understanding

- What would the response be if `@RestController` were replaced with `@Controller`?
- A thousand requests hit `GET /inventory` at once. How many `InventoryController` objects exist? Why must it have no `var` fields?
- Which handler answers `GET /inventory/widget`, and which answers `GET /inventory/widget/purchase` today?
- If the parameter were renamed to `id` but the mapping kept `{skuId}`, what would happen? How would you fix it without renaming?
- Walk through `GET /inventory/missing`: which classes run, and in what order, until the `404` body is written?
- What status does `POST /inventory` get today, and what about `GET /inventory/`?
- Where in this code is JSON produced? Which part decides the field names?
