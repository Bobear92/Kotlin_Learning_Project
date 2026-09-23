# application.yml

> Source: [src/main/resources/application.yml](../../../../../src/main/resources/application.yml) · Last synced with code: 2026-09-23

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

- What would you change so the app connects to an existing Postgres on `db.example.com` in production, without editing this file?
