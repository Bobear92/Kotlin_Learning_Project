# application.yml

> Source: [src/main/resources/application.yml](../../../../../src/main/resources/application.yml) · Last synced with code: 2026-09-28

## Role

Spring Boot's configuration file. Spring loads it from the classpath at startup, and anything it
doesn't set keeps its auto-configured default.

## Walkthrough

### What isn't here

```yaml
# Why: no spring.datasource.* here. Docker Compose support (bootRun) and Testcontainers'
# @ServiceConnection (tests) supply the connection details, so no credentials live in config.
```

**Spring:** auto-configuration builds a `DataSource` from *connection details*. Usually those come
from `spring.datasource.url`, `username` and `password`. Docker Compose support and
`@ServiceConnection` both register a `JdbcConnectionDetails` bean that takes priority over those
properties, so the database is wherever the container is.

**Why:** this means no hard-coded port and no password in a committed file.

**Spring: property sources.** This file is only one layer of configuration. Spring reads several
sources, and a higher one overrides a lower one:

```text
1. Command-line arguments      java -jar inventory.jar --spring.datasource.url=...
2. Environment variables       SPRING_DATASOURCE_URL=...
3. application.yml             (inside the jar)
4. Spring Boot's defaults
```

So production never edits this file. It sets environment variables, and Spring maps
`SPRING_DATASOURCE_URL` to `spring.datasource.url` (uppercase to lowercase, `_` to `.`), which
is called **relaxed binding**. The same jar runs everywhere; only the environment changes (the
"config" rule of the twelve-factor app), and passwords come from the server's secret store
instead of git.

Try it: `$env:SPRING_APPLICATION_NAME = "hello-override"; ./gradlew bootRun` makes every log
line say `[hello-override]`, even though this file says `inventory`.

### Strict JSON (the jackson block)

```yaml
spring:
  # Why: Jackson's lenient defaults silently turn "5" into 5 and 1.5 into 1. Stock counts
  # must be exact, so those bodies are a 400 instead.
  jackson:
    deserialization:
      accept-float-as-int: false
    mapper:
      allow-coercion-of-scalars: false
```

**YAML:** each level of indentation is one more segment of the property name, so this block is
the same as two flat properties:

```text
spring.jackson.deserialization.accept-float-as-int=false
spring.jackson.mapper.allow-coercion-of-scalars=false
```

`deserialization:` and `mapper:` are not settings on their own. They are **map properties**: Spring
Boot binds everything under them into a `Map<DeserializationFeature, Boolean>` and a
`Map<MapperFeature, Boolean>`, and applies each entry to the `JsonMapper` it auto-configures. The
key is a Jackson enum constant written in kebab-case: relaxed binding (see [property sources](#what-isnt-here))
turns `accept-float-as-int` into `ACCEPT_FLOAT_AS_INT`. Careful with symbols: `*` in YAML is an
alias reference, not a wildcard, and a stray one stops the file from parsing.

**Spring:** Spring MVC reads a `@RequestBody` with this same `JsonMapper`, so the settings apply to
every JSON request body in the app, not just `POST /inventory/{skuId}`.

**Why:** what each body does, checked against Jackson 3 with a `data class` holding `val quantity: Int`
(2026-09-28):

| Body | Jackson 3 defaults | With this block |
| --- | --- | --- |
| `{"quantity": "5"}` | **accepted as 5** (`ALLOW_COERCION_OF_SCALARS`) | rejected |
| `{"quantity": 1.5}` | **accepted as 1**, silently truncated (`ACCEPT_FLOAT_AS_INT`) | rejected |
| `{}`, `{"quantity": null}` | rejected: missing / null for a non-null `Int` | rejected |
| `[5]`, `{`, `{"quantity": 3000000000}` | rejected: not an object / malformed / out of `Int` range | rejected |

Jackson 3 is already stricter than Jackson 2 (`FAIL_ON_NULL_FOR_PRIMITIVES` is now on by default),
so only the two coercions above needed turning off. That strictness relies on the DTO declaring
`quantity: Int`, not `Int?`: with a nullable type, `{}` and `null` would be read as `null`.
Every rejection surfaces as Spring's `HttpMessageNotReadableException`, which the exception handler
maps to `400 Invalid request body` (README "Contract gaps (POST)").

**Contract:** `quantity` is `type: integer`. A string or a decimal isn't one, so refusing it is
the literal reading; coercing it would accept requests the contract doesn't describe.

This is the first of two checks. Jackson decides "is it an `Int` at all?"; Bean Validation's
`@Min(1)` on the DTO then decides "is it at least 1?", with its own message. `{"quantity": 0}`
passes this block and fails `@Min`.

### The application name

```yaml
spring:
  application:
    name: inventory
```

It appears in every log line (`--- [inventory]`) and identifies the service to tools such as
metrics and tracing.

**Why YAML instead of `.properties`:** they're equivalent (`spring.application.name=inventory`).
YAML groups nested keys, which reads better once there are several `spring.*` settings.

## Check your understanding

- Which of these bodies reach Bean Validation, and which stop in Jackson: `{"quantity": 0}`, `{"quantity": "2"}`, `{}`, `{"quantity": -1}`?
- The DTO's `quantity` is changed to `Int?`. Which unreadable-body tests would now fail, and with what response?

- What would you change so the app connects to an existing Postgres on `db.example.com` in production, without editing this file?
