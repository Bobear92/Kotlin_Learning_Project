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

**Why:** this means no hard-coded port and no password in a committed file. In production you'd
set `SPRING_DATASOURCE_URL` and friends as environment variables; Spring maps environment
variables onto these properties automatically ("relaxed binding").

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
