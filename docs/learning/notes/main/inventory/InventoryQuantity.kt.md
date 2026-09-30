# InventoryQuantity.kt

> Source: [src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryQuantity.kt](../../../../../src/main/kotlin/com/robertwoolbright/inventory/inventory/InventoryQuantity.kt) · Last synced with code: 2026-09-29

## Role

The request body for `POST /inventory/{skuId}`, written by Robert. It's the first class in the
project that data flows **into** from the outside. `InventoryItem` only ever goes out as JSON; this
one comes in. Jackson builds it from the request's JSON, and Bean Validation checks it before the
[controller](InventoryController.kt.md) function runs. The controller then passes on only the
`Int` inside it, so the [service](InventoryService.kt.md) and
[repository](InventoryRepository.kt.md) never see this class.

It is also the request body for `POST /inventory/{skuId}/purchase`: the contract uses the same
`InventoryQuantity` schema for both. (The class's KDoc still names only `POST /inventory/{skuId}`.)

## Walkthrough

### The import

```kotlin
import jakarta.validation.constraints.Min
```

**Spring / Jakarta:** `@Min` is part of **Bean Validation** (the `jakarta.validation` API), not
Spring itself. It's on the classpath through `spring-boot-starter-validation`, which also brings
Hibernate Validator, the engine that runs the checks (see
[build.gradle.kts](../../build.gradle.kts.md#app-dependencies)). `jakarta.*` is the new name of
`javax.*` since Jakarta EE 9; Spring Boot 3 and later only use `jakarta`.

### The class

```kotlin
/** One inventory quantity; used as the request body for POST /inventory/{skuId} and POST /inventory/{skuId}/purchase */
data class InventoryQuantity(
    // Contract: quantity has minimum: 1 in inventory.yaml.
    @Min(1, message = "quantity must be at least 1") val quantity: Int
    )
```

**Kotlin (`data class`):** the same kind of class as `InventoryItem`, explained in full in
[InventoryItem.kt](InventoryItem.kt.md#the-kdoc-and-the-class). Here the generated `toString()`
(`InventoryQuantity(quantity=5)`) is the most useful part: it's what shows up in logs and debugger
views. A plain `class` would have worked for Jackson too.

**Kotlin (`val quantity: Int`, non-null):** the type is doing real work here. Because `Int` has no
`?`, it compiles to a JVM primitive `int`, and Jackson 3 refuses `{}` and `{"quantity": null}` for a
primitive (`FAIL_ON_NULL_FOR_PRIMITIVES` is on by default in Jackson 3). In Jackson 2, where it was
off, `{}` would silently have become `quantity = 0`. With `Int?`, both would be read as `null`, and
`@Min` skips nulls (most constraints, including `@Min`, treat `null` as valid; `@NotNull`, `@NotEmpty`
and `@NotBlank` are the ones that don't), so a missing quantity would reach the service.
See the table in [application.yml](../resources/application.yml.md#strict-json-the-jackson-block).

**Spring (reading JSON into it):** `@RequestBody` hands the body to Jackson. The class has no
no-argument constructor and no setters, so `jackson-module-kotlin` calls the **primary constructor**,
matching the JSON key `quantity` to the constructor parameter `quantity` by name. So the Kotlin name
is the JSON field name, in both directions. It's the reverse of how `InventoryItem` is
[written as JSON](InventoryItem.kt.md#the-kdoc-and-the-class).

**Bean Validation (`@Min`):**
- `@Min(1, ...)`: the first argument is the annotation's `value`, the smallest allowed number. For
  a Java annotation like `@Min`, Kotlin lets you pass `value` by position; every other argument must
  be named, like `message = ...`.
- `message` replaces the default text ("must be greater than or equal to 1"). The exception handler
  sends this text to the client word for word, so the message lives only here. Robert chose this
  (option A on 2026-09-28) over a fixed string in the handler.
- `@Min` does nothing on its own. It's a label. Something has to run the check: that's `@Valid` on
  the controller parameter. Without `@Valid`, `{"quantity": 0}` would go straight through.

**Kotlin (where the annotation lands):** `val quantity` is a constructor parameter, a property and a
backing field at once, so an annotation on it could go on any of them. The build's
`-Xannotation-default-target=param-property` option puts `@Min` on the parameter and the field,
where Hibernate Validator finds it (see
[compiler options](../../build.gradle.kts.md#compiler-options)).

**Contract:** `components.schemas.InventoryQuantity`: an object, `required: [quantity]`,
`quantity` is `type: integer` with `minimum: 1`. Each rule is enforced in a different place:

| Contract rule | Enforced by | Error |
| --- | --- | --- |
| an object with `quantity` (`required`) | non-null `Int` + Jackson | `400 Invalid request body` |
| `type: integer` | `Int` + strict Jackson settings | `400 Invalid request body` |
| `minimum: 1` | `@Min(1)` + `@Valid` | `400 quantity must be at least 1` |

**Why the `// Contract:` sits inside the parentheses:** it's on the line above the parameter it
explains, where the rule is. Robert first put it above the KDoc (the third time comment placement
came up); a comment between KDoc and `data class` would have detached the KDoc.

**Naming:** the class name matches the contract's schema name, `InventoryQuantity`. The file was
first named `ItemQuantity.kt`; Kotlin allows a file name that differs from its class, but the
convention is to match them when a file holds one class.

### Predicting the response (walkthrough, 2026-09-29)

Two gates stand between the JSON and the controller function. **Gate 1 (Jackson) asks "is this the
right type?"; Gate 2 (Bean Validation) asks "is the value allowed?"** Gate 2 can only run once Gate 1
has built an object, so the `@Min` message is never sent for a body that fails Gate 1.

| Body | Stopped at | Response |
| --- | --- | --- |
| `{"quantity": null}` | Gate 1: a non-null `Int` can't hold `null`, so Jackson can't build the object | `400 Invalid request body` |
| `{"quantity": "7"}` | Gate 1: a string, and `allow-coercion-of-scalars` is off | `400 Invalid request body` |
| `{"quantity": 0}` | Gate 2: 0 is an `Int`, so the object is built; then `@Min(1)` fails | `400 quantity must be at least 1` |
| `{"quantity": 7}` | passes both | `200` with the new total as JSON |

In Robert's words: "Quantity of 0 gets sent to our handler and `handleValidationFailure` is called
with the message below. Quantity of null: Jackson can't build the object." To be precise about the
second case, Jackson **throws** `HttpMessageNotReadableException`, and Spring **calls**
`handleInvalidRequestBody` to answer it. Exceptions are thrown; handlers are called.

**A common slip:** a client *can* send `null`, but the missing `?` is what makes Kotlin and Jackson
**refuse** it. The type isn't saying "null never arrives"; it's saying "if null arrives, reject the request".

## Check your understanding

- What happens to `{"quantity": 0}` if `@Valid` is removed from the controller? And to `{}`?
- If `quantity` were `Int?`, which of `{}`, `{"quantity": null}` and `{"quantity": 0}` would still be rejected, and by what?
- Where does the text "quantity must be at least 1" live, and how does it reach the response body?
