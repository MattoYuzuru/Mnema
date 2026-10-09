plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "Greenfield Identity & Account runtime"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server")
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
    implementation("software.amazon.awssdk:s3:2.55.11") {
        // Only the synchronous S3Client/S3Presigner are used; the async Netty transport is never loaded.
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
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
                    "runtime" to "identity-account",
                    "identityBoundary" to "unified"
                )
            )
        }
    }
}

tasks.register<JavaExec>("accountTransfer") {
    group = "application"
    description = "Run the disposable account-only export/import/reconciliation tool"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.mnema.identityaccount.transfer.AccountTransferCli")
}
