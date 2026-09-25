# InventoryController.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryController.kt](../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryController.kt) · Last synced with code: 2026-09-25

## Role

The web layer, written by Robert, and the top of the request flow: **controller** → service →
repository. It turns an HTTP request for `GET /inventory` into a call to the
[service](InventoryService.kt.md), and hands the result back to Spring to write as JSON. It holds no
logic of its own. [InventoryControllerTest](../../test/inventory/InventoryControllerTest.kt.md)
checks it end to end through MockMvc.

## Walkthrough

### Imports

```kotlin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
```

**Spring:** all three annotations come from Spring MVC's `web.bind.annotation` package, which is
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
each function. It's the usual Spring pattern, and it will fit the next endpoints: a
`@GetMapping("/{skuId}")` in this class will map to `/inventory/{skuId}`.

**Kotlin:** constructor injection of the service, the same pattern as the
[repository](InventoryRepository.kt.md#a-spring-bean-with-constructor-injection). The space in
`InventoryController (` is legal and changes nothing.

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
The empty-array rule comes from the repository returning an empty list, not from anything here.
`InventoryControllerTest` checks both cases with `JsonCompareMode.STRICT`.

## Check your understanding

- What would the response be if `@RestController` were replaced with `@Controller`?
- Which path does `@GetMapping("/{skuId}")` in this class map to, and why?
- What status does `POST /inventory` get today, and what about `GET /inventory/`?
- Where in this code is JSON produced? Which part decides the field names?
