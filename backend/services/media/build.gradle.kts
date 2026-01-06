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


    // JPA + PostgreSQL + Flyway
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // DTO validation
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // S3 Support
    implementation("software.amazon.awssdk:s3:2.41.1")

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
