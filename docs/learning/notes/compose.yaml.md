# compose.yaml

> Source: [compose.yaml](../../../compose.yaml) · Last synced with code: 2026-09-23

## Role

Defines the Postgres container used when you run the app locally with `./gradlew bootRun`.
Tests don't use it; they use Testcontainers (see
[TestcontainersConfiguration notes](test/support/TestcontainersConfiguration.kt.md)).

## Walkthrough

### Who runs this file

```yaml
# Local Postgres for `./gradlew bootRun`. Spring Boot's Docker Compose support starts and stops
# it automatically. Tests don't use this file; they use Testcontainers.
```

**Spring:** you never run `docker compose up` yourself. The `spring-boot-docker-compose`
dependency (`developmentOnly` in the build) finds `compose.yaml` at startup, starts it, and waits
until the container is running and its mapped port accepts TCP connections. (That's Spring's
default readiness check. It would use a compose `healthcheck` if we defined one.) It then reads the container's image, environment and mapped port
and builds the DataSource connection details from them (a JDBC URL, user and password). On
shutdown it stops the container. You can see this in the `bootRun` log: `Container ... Started`,
then Flyway connecting to `jdbc:postgresql://127.0.0.1:<port>/inventory`.

If the services are already running (say you ran `docker compose up` yourself), Spring uses
them as they are and leaves them running when the app shuts down. It only stops what it started.

### Services

```yaml
services:
  postgres:
```

A compose file lists *services*, i.e. containers to run. `postgres` is just the service's name
(it also names the container: `nuuly_inventory_assessment-postgres-1`). Spring doesn't care about
the name. It recognizes the service by its **image**: a `postgres` image tells it this is a
Postgres database and that it should create JDBC connection details for it.

### Image version

```yaml
image: 'postgres:18'
```

**Why:** pinned to a major version instead of `latest`, so a new Postgres major release can't
change behavior without us noticing. `18` still receives the 18.x patch releases. Tests
use the same tag, so local runs and tests behave the same.

### Credentials

```yaml
environment:
  - 'POSTGRES_DB=inventory'
  - 'POSTGRES_USER=inventory'
  - 'POSTGRES_PASSWORD=inventory'
```

The official Postgres image creates this database and user on first start. These are throwaway,
local-only values. Spring reads them from here, so they aren't repeated in `application.yml`.

### Ports

```yaml
ports:
  # Why: random free host port (no clash with a local Postgres), bound to 127.0.0.1 only so the
  # dev database isn't reachable from the network. Plain '5432' would bind on all interfaces.
  - '127.0.0.1::5432'
```

The format is `host-ip:host-port:container-port`, and here the host port is left empty.

**Why:** the host port is empty, so Docker picks a random free one (e.g. `53106`). A fixed
`'5432:5432'` would fail if you already had Postgres running locally. Spring asks Docker which
port it got, so nothing needs to know the number in advance.

**Why `127.0.0.1`:** by default Docker publishes ports on *all* network interfaces. Other
machines on your Wi-Fi could then reach a database whose password is `inventory`. Binding to
the loopback address keeps it on this machine.

## Check your understanding

- If you run `docker compose up` yourself and then `bootRun`, what do you expect Spring to do?
- Why doesn't `application.yml` need a `spring.datasource.url`?
