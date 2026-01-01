plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "S3 service for handling media content"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Web / REST (Spring MVC)
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Actuator
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Security + JWT
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Если вместо этого сервис должен быть OAuth2 client (client_credentials/authorization_code), то поменяй на:
    // implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    // JPA + PostgreSQL + Flyway
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // (опционально, но обычно нужно) DTO validation
    // implementation("org.springframework.boot:spring-boot-starter-validation")

    // Tests
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // (опционально) если используешь Testcontainers как в core
    // testImplementation("org.testcontainers:junit-jupiter")
    // testImplementation("org.testcontainers:postgresql")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
