// Study notes: docs/learning/notes/build.gradle.kts.md
plugins {
    kotlin("jvm") version "2.3.21"
    // Opens Spring-annotated classes: Kotlin classes are final by default, Spring needs to subclass some.
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    // Supplies versions from Spring Boot's BOM, so most dependencies below have none.
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.robertwoolbright"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // Why: tests run against real Postgres, not H2; oversell protection depends on Postgres behavior.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // Checks MockMvc responses against inventory.yaml (the contract-validation test).
    testImplementation("com.atlassian.oai:openapi-request-validator-mockmvc:3.0.0")
    // Property-based testing (generators + checkAll) inside ordinary JUnit tests; not Kotest's runner.
    testImplementation("io.kotest:kotest-property:6.2.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Why: the load test runs for a fixed time (20 s by default), too slow for every build, so it has
// its own task. Considered always running it: that would make every `./gradlew build` slower.
tasks.test {
    useJUnitPlatform { excludeTags("load") }
}

// Runs only the @Tag("load") tests: ./gradlew loadTest [-Dload.seconds=60 -Dload.clients=100]
tasks.register<Test>("loadTest") {
    description = "Runs the HTTP load test against the real app."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("load") }
    shouldRunAfter(tasks.test)
    // Why: a load test is a measurement; rerun it every time instead of reusing the last result.
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
    for (key in listOf("load.seconds", "load.clients")) {
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
