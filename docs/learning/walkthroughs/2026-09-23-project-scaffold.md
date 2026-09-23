# Walkthrough: project scaffold

- Date: 2026-09-23
- Covers: 6cf784f..cf86d35 (plus uncommitted: yes, note improvements made during this session)
- Files: settings.gradle.kts, build.gradle.kts, compose.yaml, src/main/resources/application.yml,
  InventoryApplication.kt, support/TestcontainersConfiguration.kt, InventoryApplicationTests.kt

## Concepts covered

- Settings vs build file: settings decides *which* projects exist (`include(...)` for modules), build decides *how* each is built.
- Gradle Kotlin DSL is Kotlin: trailing lambdas, lambdas with receiver (type-safe builders), infix `version`, `=` on lazy properties.
- Final by default: Kotlin classes/methods are final; Spring proxies subclass and override, so `plugin.spring` opens them.
- Final is not immutable: `open`/`final` = subclassing; `val`/`var` = mutability.
- Dependency configurations: `implementation` vs `runtimeOnly` vs `developmentOnly`; compile vs runtime classpath.
- Platform types and `-Xjsr305=strict`: Java nullability annotations become real Kotlin types; a missed null check is a compile error.
- `param-property` annotation target: where `@Min(1)` on a constructor `val` lands (and why it could be silently ignored otherwise).
- Reified type parameters: `withType<Test>`, `runApplication<InventoryApplication>`.
- Docker Compose support: Spring starts the container, reads DB/user/password from compose.yaml and the port from Docker.
- Loopback port binding (`127.0.0.1::5432`) and why Docker's default is a security risk.
- Connection details: Compose and Testcontainers supply them, overriding `spring.datasource.*`.
- Property source precedence: command line > environment variables > application.yml > defaults; relaxed binding.
- Twelve-factor config: same jar everywhere, environment set by the infrastructure (k8s manifests, ECS task definitions).
- `@SpringBootApplication` = `@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@ComponentScan`.
- Top-level functions (`InventoryApplicationKt`), importing a function, `Array<String>`, spread operator `*`.
- `@TestConfiguration` + `@Import`, `@ServiceConnection`, container lifecycle tied to the Spring context, context caching, Ryuk.
- `@SpringBootTest` finds `@SpringBootConfiguration` by searching up packages; smoke test vs slices.

## Questions Robert asked

- Q: Wouldn't you need to update application.yml with the production database info?
  A: No. Spring layers property sources; environment variables (set by the infrastructure) override
  application.yml, so the repo never holds environment-specific values or passwords.
- Q: So environment variables are set at the infrastructure level and the service just reads them?
  A: Yes. Whatever launches the process sets them (k8s Deployment + Secret, ECS task definition,
  `docker run -e`, systemd). `.env` files are a local convenience and are gitignored.
- Q: Seth (Nuuly architect) said they chose Spring over Micronaut because environment-variable
  handling across environments was painful and a related bug kept them out of production for
  3 days. Does that relate, and is it a real advantage despite Micronaut's faster startup?
  A: It relates directly to config layering. Possible Micronaut culprits (speculative): automatic
  environment detection, looser env-var-to-property mapping, compile-time decisions. Startup speed
  matters most for serverless/scale-from-zero; predictability matters constantly. Suggested asking
  Seth which mechanism it was rather than claiming to know.
- (Environment) `./gradlew bootRun` failed with "JAVA_HOME is not set": VS Code was opened before
  Java was installed, so its terminals have a stale environment. Fix: restart VS Code fully.

## Understanding checks

- Splitting into modules: which file changes? → settings.gradle.kts with `include(...)`. Correct.
- Remove `plugin.spring`, add `@Transactional`: what breaks? → Correct on final vs open, but said a
  final class is "immutable". Clarified final ≠ immutable, that proxies wrap method calls, and that a
  final *method* fails silently. Added to the build notes.
- `import org.postgresql.PGConnection` with `runtimeOnly`? → "a dependency issue". Sharpened: it
  fails to *compile* (not on the compile classpath); `runtimeOnly` enforces the java.sql-only design.
- `@Nullable` + `result.length` with and without `-Xjsr305=strict`? → Correct. Added: without the flag
  it's only a warning; showed `?.` and `?:`.
- How does bootRun know the DB without application.yml settings? → "it's all in compose.yaml".
  Mostly right; the port comes from Docker, not the file.
- Deploy to db.example.com without editing files? → Unsure. Re-explained with property source
  precedence and relaxed binding; added to the application.yml notes. Follow-up
  (CLI vs env vs yml) → "cli-wins", correct.
- Controller in sibling package `com.robertwoolbright.web`? → "won't be found". Correct; added that the
  symptom is a silent 404 with no startup error.
- Who starts/stops the test container? → Spring, on bean creation / context close. Correct; added that
  with caching that means end of test run, and Ryuk as a safety net.
- Why keep `contextLoads`? → startup issues: dependencies, config, migrations. Correct; added that
  slices never assemble the full app.

## Follow-ups

- Restart VS Code so terminals see JAVA_HOME and Docker, then try the experiments:
  `docker ps` during bootRun; `$env:SPRING_APPLICATION_NAME = "hello-override"`; comment out
  `@Import` in InventoryApplicationTests and predict the failure.
- `?.` and `?:` were previewed only; cover properly when they first appear in real code.
- Optional: add Seth's Micronaut outage story to the README decision record (Robert's call).
