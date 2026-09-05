plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "Greenfield Identity & Account runtime"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-authorization-server")
    implementation("org.springframework.session:spring-session-jdbc")
    implementation("software.amazon.awssdk:s3:2.41.1")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
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
