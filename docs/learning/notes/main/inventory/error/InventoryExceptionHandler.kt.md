# InventoryExceptionHandler.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/error/InventoryExceptionHandler.kt](../../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/error/InventoryExceptionHandler.kt) · Last synced with code: 2026-09-27

## Role

Written by Robert. It turns exceptions into HTTP error responses. It sits *beside* the request flow,
not in it: controller → service → repository run as usual, and when one of them throws, Spring
hands the exception to this class instead of failing with a `500`. Today it handles
[`SkuNotFoundException`](SkuNotFoundException.kt.md) → `404` `text/plain`. The purchase endpoint will
add handlers for its `400`s here, so every error body the contract requires is built in one file.

## Walkthrough

### Imports

```kotlin
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
```

**Kotlin:** `SkuNotFoundException` is in the same package (`inventory.error`), so it needs no import.
`org.springframework.http` holds the HTTP building blocks (status codes, media types, responses);
`web.bind.annotation` holds the Spring MVC annotations, the same package as `@GetMapping`.

### The class, and why it's an advice

```kotlin
// Why: RestController advice so spring will catch and return specific responses we have built out for every controller rather than us building out multiple response entities for each controller. We rejected ResponseStatusException because its body is JSON, not text/plain.

/** Where the various custom built exceptions are transformed to HTTP responses. */
@RestControllerAdvice
class InventoryExceptionHandler{
```

**Spring (`@RestControllerAdvice`):** it's `@ControllerAdvice` plus `@ResponseBody`, the same pairing
as [`@RestController`](../InventoryController.kt.md#a-rest-controller-mapped-to-inventory).
- `@ControllerAdvice` is a stereotype (it's annotated with `@Component`), so component scanning makes
  this class a bean at startup. Spring MVC then remembers it as a source of `@ExceptionHandler`
  methods that apply to **every** controller.
- `@ResponseBody` means a handler's return value is written as the response body. Here the return
  type is `ResponseEntity`, which is always written as the response anyway, so this half isn't
  strictly needed. It would matter for a handler that returned a plain `String`.

Think of it as one `try`/`catch` that Spring wraps around every controller method, written once
instead of in each controller.

**Why:** the `// Why:` records Robert's decision (2026-09-27). The two rejected options:
- **A `ResponseEntity` built in the controller.** The controller would need an `if`/`?:` and a
  `ResponseEntity<*>` return type, and every endpoint that can miss a SKU (purchase too) would repeat
  it. With the advice, controllers keep the happy path only.
- **`ResponseStatusException`.** The service would throw an exception that carries an HTTP status,
  so HTTP knowledge would leak into the service layer. That's the stronger reason. The body is
  also wrong by default: Spring Boot renders it through its error page, which means JSON for API
  clients, an HTML page for browsers, and an empty body under MockMvc (no error page there). None
  of these is the contract's `text/plain` string. (An advice *could* catch `ResponseStatusException`
  and write text itself, but then the custom exception is simpler.) The `// Why:` comment's "its body is
  JSON" is the common case, not the whole story.

**Placement:** the `// Why:` sits above the KDoc, with a blank line between, so the KDoc stays
directly on the class. Robert's first version put the comment between the KDoc and
`@RestControllerAdvice`. That cuts the KDoc off from the class: it no longer shows on hover, and
`check_kdoc.py` flagged the class as undocumented.

**Kotlin:** no constructor parameters, because the handler needs nothing injected. A class with no
primary constructor parameters is declared with just its name, as here. Spring calls its no-argument
constructor. `InventoryExceptionHandler{` with no space is legal.

### The `// Contract:` comment

```kotlin
// Contract: paths./inventory/{skuId}.get - 404 with text/plain when SkuNotFoundException is thrown.
```

**Contract:** `paths./inventory/{skuId}.get`, response `'404'`: content `text/plain`, schema
`#/components/schemas/Error` (`type: string`). This comment is the other end of the one in the
[controller](../InventoryController.kt.md#the-second--contract-comment): the controller says the `404`
comes from `SkuNotFoundException`, and this file is where it's produced.

### `handleSkuNotFound`

```kotlin
/** Returns a 404 text/plain response whose body is the exception's message. */
@ExceptionHandler(SkuNotFoundException::class)
fun handleSkuNotFound(ex: SkuNotFoundException ): ResponseEntity<String> =

    ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.TEXT_PLAIN)
        .body(ex.message ?: "SKU not found.")
```

**Spring (`@ExceptionHandler`):** registers this function for `SkuNotFoundException` (and any
subclass of it). When a controller method throws, Spring's `DispatcherServlet` asks its exception
resolvers for a match, in this order:
1. an `@ExceptionHandler` inside the controller class itself,
2. then `@ExceptionHandler`s in `@ControllerAdvice` beans, like this one.

Within one class, if several handlers match, the one for the *closest* exception type wins, so a
later `@ExceptionHandler(RuntimeException::class)` in this class wouldn't steal this one's
exceptions. Across several advice classes, their order (`@Order`) decides first: the first advice
with *any* matching handler wins. Spring passes the thrown exception in as the
parameter, and the function's return value becomes the response.

`SkuNotFoundException::class` is a [class reference](../../../test/InventoryApplicationTests.kt.md#starting-the-real-application-in-a-test),
Kotlin's `SkuNotFoundException.class`.

**Spring (`ResponseEntity` builder):** `ResponseEntity` is a whole HTTP response: status, headers and
body. It's built with a chain where each call returns a builder, and the last call produces the
response:

| Call | Sets | Returns |
| --- | --- | --- |
| `ResponseEntity.status(HttpStatus.NOT_FOUND)` | status `404` | a `BodyBuilder` |
| `.contentType(MediaType.TEXT_PLAIN)` | the `Content-Type` header | the same `BodyBuilder` |
| `.body(...)` | the body | `ResponseEntity<String>` |

The body's type becomes the `T` in `ResponseEntity<T>`, which is why the return type is
`ResponseEntity<String>`. Spring then writes the `String` as-is with its `StringHttpMessageConverter`
(no JSON quotes around it).

Two things this chain avoids:
- **`ResponseEntity.notFound()`** looks like a shortcut, but it returns a `HeadersBuilder`, which has
  no `.body(...)`. Only `.build()`, giving an empty `404`.
- **Leaving out `.contentType(...)`.** Spring would then choose the type from the request's
  `Accept` header, and could send `application/json` for a client that asks for JSON. Setting it
  here makes the `text/plain` promise hold for every client.

Robert's first version used `HttpStatus.CONFLICT` (409), copied from a parallel example. Both
`404` tests caught it: `Status expected:<404> but was:<409>`.

**Kotlin (`message` is nullable):** `ex.message` comes from Java's `Throwable.getMessage()`, which
may return null, so Kotlin types it `String?`. `body(...)` here needs a `String`, so the null case
must be handled. The [Elvis operator](../InventoryService.kt.md#getinventory-turning-no-row-into-an-error)
gives a fallback. The three options that were weighed:

| Option | If `message` were null | Chosen? |
| --- | --- | --- |
| `ex.message ?: "SKU not found."` | a readable fallback body | **yes** |
| `ex.message!!` | throws a `NullPointerException` inside the error handler | no |
| keep `skuId` as a `val` on the exception and build the text here | can't be null | no |

In practice the fallback never runs: `SkuNotFoundException`'s constructor always passes
`"SKU not found: $skuId"`, so `message` is never null. The `?:` is there because the compiler can't
see that. Robert chose a readable fallback over an empty string (2026-09-27), because a client
getting an empty `404` body would have nothing to go on.

**Worth knowing: handled exceptions aren't logged.** Once this handler returns, Spring treats the
exception as dealt with and logs nothing at the default log level. The fallback text only ever
reaches the API client. If missing SKUs should show up in the logs, that's a logger call in this
function.

**Worth knowing: no charset in the header.** Checked against the running app on 2026-09-27:
`curl -i http://localhost:8080/inventory/missing` returns `Content-Type: text/plain`, with no
`charset` parameter, while the body bytes are UTF-8 (a SKU `café` comes back as UTF-8 bytes). For
ASCII SKUs this makes no difference. A client that assumes a different charset could misread
non-ASCII ones. `MediaType("text", "plain", Charsets.UTF_8)` would state it; this is tracked in TODO.

**Kotlin:** an [expression body](../../../test/support/TestcontainersConfiguration.kt.md#the-container-bean)
split across lines. The blank line after `=` is legal, and each line starting with `.` continues the
chain. The space in `SkuNotFoundException )` changes nothing.

**Naming:** `handle` + the exception's name is the usual Spring convention. When purchase adds an
insufficient-stock handler to this class, the two function names will say which is which. Robert
first reused the class name, `inventoryExceptionHandler`, and renamed it in review.

**Contract:** status `404`, `Content-Type: text/plain`, and a body that is a bare string: the `Error`
schema is `type: string`, not an object. `InventoryControllerTest` checks all three, with
`contentTypeCompatibleWith(TEXT_PLAIN)` so a later `;charset=UTF-8` would still pass.

## Check your understanding

- Remove `@RestControllerAdvice` and run `GET /inventory/missing`. What status comes back, and why
  does the handler function no longer run?
- A second handler, `@ExceptionHandler(RuntimeException::class)`, is added to this class. Which
  handler answers a missing SKU, and which answers a database failure?
- What would the client receive with `ResponseEntity.notFound().build()` instead of the chain?
- Why does `ex.message` need `?:` even though the message can never be null here?
