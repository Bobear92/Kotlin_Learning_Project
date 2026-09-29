# InventoryExceptionHandler.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/error/InventoryExceptionHandler.kt](../../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/error/InventoryExceptionHandler.kt) · Last synced with code: 2026-09-29

## Role

Written by Robert. It turns exceptions into HTTP error responses. It sits *beside* the request flow,
not in it. It handles six exceptions of two kinds: ours, thrown by the service, and Spring's own,
thrown while Spring reads the request, before the controller function runs. Without this class,
ours would become `500`s and Spring's would get Spring's default `400` or `415`, never the
contract's `text/plain` text.

| Exception | From | Response |
| --- | --- | --- |
| [`SkuNotFoundException`](SkuNotFoundException.kt.md) | our service | `404` `SKU not found: <skuId>` |
| [`AddStockOverflowException`](AddStockOverflowException.kt.md) | our service | `400` `Quantity would exceed the maximum stock` |
| [`InsufficientInventoryException`](InsufficientInventoryException.kt.md) | our service | `400` `Insufficient inventory for <skuId>` |
| `MethodArgumentNotValidException` | Spring (Bean Validation) | `400` with the `@Min` message |
| `HttpMessageNotReadableException` | Spring (Jackson) | `400` `Invalid request body` |
| `HttpMediaTypeNotSupportedException` | Spring (content negotiation) | `400` `Content-Type must be application/json` |

Every body is `text/plain`, and every error body the contract requires is built in this one file.
The handlers are global, so the purchase endpoint reuses four of them: the `404` and the three
request-reading `400`s. Its fifth, the insufficient-stock `400`, is new. Only the overflow handler is
specific to receiving stock.

## Walkthrough

### Imports

```kotlin
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
```

**Kotlin:** `SkuNotFoundException`, `AddStockOverflowException` and `InsufficientInventoryException`
are in the same package (`inventory.error`), so they need no import. The three Spring exceptions do. Robert's first
`MethodArgumentNotValidException` handler didn't compile for exactly this reason: his own exceptions
had never needed an import, so the rule hadn't come up.
`org.springframework.http` holds the HTTP building blocks (status codes, media types, responses);
`web.bind.annotation` holds the Spring MVC annotations, the same package as `@GetMapping`. Note
`web.bind` (the exception) vs `web.bind.annotation` (the annotations): two different packages.

### The class, and why it's an advice

```kotlin
// Why: RestController advice so spring will catch and return specific responses we have built out for every controller rather than us building out multiple response entities for each controller. We rejected ResponseStatusException because its body is JSON, not text/plain.

/** Where the various custom built exceptions and spring exceptions are transformed to HTTP responses. */
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
// Contract: paths./inventory/{skuId}.get and paths./inventory/{skuId}/purchase.post - 404 with text/plain when SkuNotFoundException is thrown.
```

**Contract:** `paths./inventory/{skuId}.get`, response `'404'`: content `text/plain`, schema
`#/components/schemas/Error` (`type: string`). This comment is the other end of the one in the
[controller](../InventoryController.kt.md#the-second--contract-comment): the controller says the `404`
comes from `SkuNotFoundException`, and this file is where it's produced.

**Why two paths:** `paths./inventory/{skuId}/purchase.post` also has a `'404'` "SKU not found", and
the service throws the same `SkuNotFoundException` for it, so this one handler serves both endpoints.
When purchase was added, Robert widened this comment and the three request-reading comments below
to list every path that depends on them. A reader checking the purchase contract can then find its
error responses by searching for the path.

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
2. then `@ExceptionHandler`s in `@ControllerAdvice` beans, like this one,
3. then `@ResponseStatus` on the exception class and `ResponseStatusException`,
4. then Spring's built-in mappings (e.g. a malformed request → `400`),
5. and if nothing matches, a `500` with Spring Boot's default JSON error body.

Spring catches *every* exception, custom or not: the advice isn't where Spring looks when it
"doesn't recognize" an exception (the 2026-09-28 walkthrough's first framing). It just gets a
custom exception handled at step 2 instead of falling through to the `500` at step 5.

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

**Naming:** `handle` + what went wrong is the usual Spring convention, so each function name says
which case it covers. Robert first reused the class name, `inventoryExceptionHandler`, and renamed
it in review.

**Contract:** status `404`, `Content-Type: text/plain`, and a body that is a bare string: the `Error`
schema is `type: string`, not an object. `InventoryControllerTest` checks all three, with
`contentTypeCompatibleWith(TEXT_PLAIN)` so a later `;charset=UTF-8` would still pass.

### `handleStockOverflow`: our exception, our message

```kotlin
// Contract: paths./inventory/{skuId}.post - 400 with text/plain when AddStockOverflowException is thrown.

/** Returns a 400 text/plain response whose body is the exception's message.*/
@ExceptionHandler(AddStockOverflowException::class)
fun handleStockOverflow(ex: AddStockOverflowException): ResponseEntity<String> =

    ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.TEXT_PLAIN)
        .body(ex.message ?: "Overflow Guard Triggered")
```

**Spring:** the same shape as `handleSkuNotFound`, with `HttpStatus.BAD_REQUEST` (`400`) instead of
`NOT_FOUND`. Robert's first version kept `INTERNAL_SERVER_ERROR` from an earlier draft; the overflow
test caught it (`Status expected:<400> but was:<500>`). Each `HttpStatus` constant is just a name
for a number, and the client gets whatever is passed to `status(...)`.

**Why `ex.message` is safe here:** we wrote this exception's message for the client
(`"Quantity would exceed the maximum stock"`, in
[AddStockOverflowException](AddStockOverflowException.kt.md)). As with `SkuNotFoundException`, the
`?:` fallback never runs; it's only there because `Throwable.message` is typed `String?`.

**Contract:** `paths./inventory/{skuId}.post`, response `'400'`, `text/plain`. The overflow case
itself is a README "Contract gaps (POST)" decision.

### `handleValidationFailure`: the message from `@Min`

```kotlin
// Contract: paths./inventory/{skuId}.post and paths./inventory/{skuId}/purchase.post - a request body that fails validation is a 400 text/plain.

/** Returns a 400 text/plain response from the failing field's validation message.*/
@ExceptionHandler(MethodArgumentNotValidException::class)
fun handleValidationFailure(ex: MethodArgumentNotValidException): ResponseEntity<String> =

    ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.TEXT_PLAIN)
        .body(ex.bindingResult.fieldError?.defaultMessage ?: "Invalid request")
```

**Spring (handling Spring's own exceptions):** `MethodArgumentNotValidException` is what
`@Valid @RequestBody` throws when Bean Validation fails. An advice doesn't care whose exception it
handles. Because `@ExceptionHandler` methods in advice are tried (step 2 in the list above) before
Spring's built-in mappings (step 4), this handler replaces Spring's default answer: a `400` without
our text (Spring Boot's JSON error body in the running app, an empty body under MockMvc).

**Why not `ex.message`:** for Spring's exceptions the message is written for developers, not
clients. Robert's first version returned it, and the test showed what the client would have got:

```text
Validation failed for argument [1] in public com.robertwoolbright.inventory.inventory.InventoryItem
com.robertwoolbright.inventory.inventory.InventoryController.receiveInventory(java.lang.String,...
```

That leaks the package, class and method names to anyone calling the API. The `?:` fallback didn't
help either: `?:` only runs when the left side is `null`, and Spring's messages never are.

**Kotlin (a chain of safe calls):** the message the client should see is the one in
`@Min(message = ...)`. Spring stores it on the exception:

| Expression | Type | What it is |
| --- | --- | --- |
| `ex.bindingResult` | `BindingResult` | every validation error for the body |
| `.fieldError` | `FieldError?` | the first error on a field; `null` if none |
| `?.defaultMessage` | `String?` | that error's message, here `"quantity must be at least 1"` |
| `?: "Invalid request"` | `String` | the fallback if anything above was `null` |

`?.` is the **safe-call operator**: `a?.b` is `a.b` if `a` isn't `null`, and `null` if it is, instead
of a `NullPointerException`. One `?:` at the end covers a `null` from any step in the chain. Java
equivalent:

```java
FieldError error = ex.getBindingResult().getFieldError();
String message = (error != null && error.getDefaultMessage() != null)
        ? error.getDefaultMessage() : "Invalid request";
```

Both `fieldError` and `defaultMessage` are Java getters marked `@Nullable` in Spring, so Kotlin
types them `T?` and the compiler insists on `?.` (see [platform types](../../../build.gradle.kts.md#compiler-options)).

**Why read it from the exception, not hardcode it:** Robert's second version returned the fixed text
`"quantity must be at least 1"`. The tests passed, but the text then lived in two places, and the
handler would say "quantity must be at least 1" for *any* validation failure, including rules added
later. Reading `defaultMessage` keeps the annotation as the single source of the text.

**Only the first error:** `fieldError` returns one error. The request body has one field today, so
there's only ever one. With several fields, the client would see the first failure only.

**Naming:** first `handleMalformedBody` (wrong: a malformed body is the next handler's job), then
`handleMinQuantity`, renamed in review to `handleValidationFailure`, because the advice is global
and will catch every Bean Validation failure on any `@Valid` body, not just `@Min`. The fallback and
KDoc were made general at the same time. Spring never calls the method by name, so the rename
couldn't change behavior.

### `handleInvalidRequestBody`: a handler with no parameters

```kotlin
// Contract: paths./inventory/{skuId}.post and paths./inventory/{skuId}/purchase.post - when the request body is invalid we return a 400 text/plain.

/** Returns a 400 text/plain response with the fixed text Invalid request body.*/
@ExceptionHandler(HttpMessageNotReadableException::class)
fun handleInvalidRequestBody(): ResponseEntity<String> =

    ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.TEXT_PLAIN)
        .body("Invalid request body")
```

**Spring:** `HttpMessageNotReadableException` covers every way reading the body can fail: empty,
malformed JSON, `null`, an array, a missing or `null` `quantity`, a string, a decimal, or a number too
big for `Int` (the nine cases in
[InventoryControllerTest](../../../test/inventory/InventoryControllerTest.kt.md#every-unreadable-body-is-a-400)).
They all get the same fixed text, the README "Contract gaps (POST)" decision.

**Why a fixed string:** the exception's message is Jackson's, for example
`JSON parse error: Cannot coerce String value ("5") to 'int' value (but might if coercion using 'CoercionConfig' was enabled)`.
It names internal settings and differs for every case. There's nothing useful in it for the client
that "Invalid request body" doesn't say.

**Spring (no parameters):** once the body doesn't need the exception, the parameter can go. The
exception type in `@ExceptionHandler(...)` is what routes the exception here; the parameter is only
there when you want to *read* it. Spring looks at the parameter list, finds nothing to fill in, and
calls the function with no arguments. With the parameter gone, the signature itself shows which
handlers use the exception and which don't. Robert's first try removed only the name, leaving
`(HttpMessageNotReadableException)`. Kotlin reads that as a parameter *named*
`HttpMessageNotReadableException` with no type: "An explicit type is required on a value parameter".
A parameter is always `name: Type`, in Java too.

### `handleContentTypeMismatch`: 400 instead of Spring's 415

```kotlin
// Contract: paths./inventory/{skuId}.post and paths./inventory/{skuId}/purchase.post - when the content type is not application/json we return a 400 text/plain.

/** Returns a 400 text/plain response with the fixed text Content-Type must be application/json.*/
@ExceptionHandler(HttpMediaTypeNotSupportedException::class)
fun handleContentTypeMismatch():ResponseEntity<String> =

    ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.TEXT_PLAIN)
        .body("Content-Type must be application/json")
```

**Spring:** `@RequestBody` throws `HttpMediaTypeNotSupportedException` when no message converter can
read the request's `Content-Type` into `InventoryQuantity` (only Jackson can, and only for JSON).
Spring's default answer is `415 Unsupported Media Type`: correct HTTP, but the contract lists only
`200` and `400`. This handler overrides it.

Robert's first version returned `HttpStatus.UNSUPPORTED_MEDIA_TYPE` here, so the handler *was*
running but sent `415` on purpose. The status comes from the code, not from the exception.

**Why:** a `415` is arguably the better HTTP answer, but a client written against the contract
only expects `200` or `400`. Sticking to the contract was the README decision (2026-09-28).

**Spring (global reach):** like every handler here, this one applies to every controller. Any future
endpoint with a `@RequestBody` gets the same `400` for a non-JSON request, which is what we want.

**Kotlin:** `():ResponseEntity<String>` with no spaces is legal. Robert's first version also copied
the body from the handler above (`"Invalid request body"`); the test caught the wrong text.

### `handleInsufficientInventory`: the exception comes in as a parameter

```kotlin
// Contract: paths./inventory/{skuId}/purchase.post - when there is insufficient inventory for a purchase call we return 400 text/plain.

/** Returns a 400 text/plain response with the message from the exception: Insufficient inventory for <skuId> */
@ExceptionHandler(InsufficientInventoryException::class)
fun handleInsufficientInventory(ex: InsufficientInventoryException): ResponseEntity<String> =

    ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.TEXT_PLAIN)
        .body(ex.message ?: "Insufficient Inventory")
```

**Spring:** the same shape as [`handleStockOverflow`](#handlestockoverflow-our-exception-our-message):
our own exception, a `400`, and our own message as the body. The body is
`Insufficient inventory for <skuId>`, written in
[InsufficientInventoryException](InsufficientInventoryException.kt.md).

**Spring (why `ex` must be declared):** this handler reads the exception, so it needs a parameter
to receive it. [`handleInvalidRequestBody`](#handleinvalidrequestbody-a-handler-with-no-parameters)
shows the opposite case. There the body is fixed text, so the parameter can be left out. Here the
body includes the SKU, so the handler needs the exception object. Spring decides what to pass by
the parameter's **type**: it sees `ex: InsufficientInventoryException` and passes in the exception
that was thrown. The name `ex` is only a convention and has no special meaning. `e` or `error` would
work just as well.

Robert's versions show the two mistakes to avoid (2026-09-29):
1. **A fixed body, `"Insufficient Inventory"`.** It compiles, but the controller test expects
   `Insufficient inventory for widget`, and the fixed text loses the SKU.
2. **`.body(ex.message ...)` with `()` still empty.** `ex` was never declared in this function, so
   the compiler reports `Unresolved reference 'ex'`. `ex` in the other handlers is each one's
   own parameter. A parameter name exists only inside the function that declares it, as in Java,
   where `void handle(InsufficientInventoryException ex)` is the only way to get an `ex`.

**Kotlin (the fallback):** as in the other handlers, `?:` is there only because `Throwable.message`
is `String?`. The constructor always sets a message, so the fallback never runs. Its capitalization,
`Inventory`, differs from the real message's `inventory`. That's harmless, because it's unreachable.

**Contract:** `paths./inventory/{skuId}/purchase.post`, response `'400'`, `text/plain`. The
contract's own description names this case: "Insufficient inventory or invalid request".
`InventoryControllerTest` checks the status, the content type and the exact body.

## Check your understanding

- Remove `@RestControllerAdvice` and run `GET /inventory/missing`. What status comes back, and why
  does the handler function no longer run?
- A second handler, `@ExceptionHandler(RuntimeException::class)`, is added to this class. Which
  handler answers a missing SKU, and which answers a database failure?
- What would the client receive with `ResponseEntity.notFound().build()` instead of the chain?
- Why does `ex.message` need `?:` even though the message can never be null here?
- `handleInvalidRequestBody` returned `ex.message ?: "Invalid request body"`. What would the client see for `{"quantity": "5"}`, and why does the fallback never appear?
- Delete `handleContentTypeMismatch`. What does a `text/plain` POST get now, and which part of Spring produces it?
- What does `ex.bindingResult.fieldError?.defaultMessage` evaluate to for `{"quantity": 0}`? When would the `?:` fallback be used?
- Why is `ex.message` fine to send for `AddStockOverflowException` but not for `HttpMessageNotReadableException`?
- Why does `handleInsufficientInventory` need a parameter when `handleInvalidRequestBody` doesn't? How does Spring know what to pass in?
- Rename `ex` to `failure` in `handleInsufficientInventory` (in both places). Does anything change?
- Which handlers answer a purchase request, and which handler can a purchase never reach?
