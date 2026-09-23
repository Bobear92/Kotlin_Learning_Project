# settings.gradle.kts

> Source: [settings.gradle.kts](../../../settings.gradle.kts) · Last synced with code: 2026-09-23

## Role

The first file Gradle reads. It decides *which projects* make up the build; `build.gradle.kts`
then decides *how* each one is built.

## Walkthrough

### The project name

```kotlin
rootProject.name = "inventory"
```

**Kotlin:** `.kts` means Kotlin script: real Kotlin, compiled and type-checked, so the IDE
autocompletes it. Older Gradle builds use Groovy (`settings.gradle`) instead.

**Why:** a single-module build. A multi-module build would add `include("api", "domain")` here,
but four endpoints don't justify splitting into modules. The name also becomes the jar's name
(`inventory-0.0.1-SNAPSHOT.jar`).

## Check your understanding

- If we later split the code into an `api` module and a `domain` module, which file changes first, and how?
