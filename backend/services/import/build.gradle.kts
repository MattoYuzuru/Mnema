plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "Import service for ingesting external decks"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Web / REST
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Security + JWT
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // JPA + PostgreSQL + Flyway
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // DTO validation
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Actuator
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Reactive streams (needed for Spring HTTP clients/actuator reflection)
    implementation("org.reactivestreams:reactive-streams:1.0.4")

    // CSV parsing
    implementation("org.apache.commons:commons-csv:1.12.0")

    // SQLite (apkg)
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    // Tests
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
