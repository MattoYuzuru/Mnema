plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "Greenfield modular Learning API runtime"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    // S3-compatible direct uploads; the Learning runtime owns its media transport.
    implementation("software.amazon.awssdk:s3:2.55.10") {
        // Only the synchronous S3Client/S3Presigner are used; the async Netty transport is never loaded.
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot {
    buildInfo {
        excludes.set(setOf("time"))
        properties {
            additional.set(
                mapOf(
                    "runtime" to "learning-api",
                    "apiBoundary" to "canonical"
                )
            )
        }
    }
}
