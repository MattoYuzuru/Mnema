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
    implementation("software.amazon.awssdk:s3:2.55.11") {
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

tasks.withType<Test>().configureEach {
    // The JDK HttpClient ignores Basic credentials for a CONNECT tunnel unless this is cleared before its classes load; the egress
    // proxy test (EgressProxyTunnelTest) needs it, and it must be a JVM property (java.net.http module docs, "System properties").
    systemProperty("jdk.http.auth.tunneling.disabledSchemes", "")
}

// The golden eval of the AI layer (issue #300): opt-in and never part of `check` or `quality` (the root build leaves it out of both).
// GoldenEvalRunner is skipped unless MNEMA_AI_EVAL=live|stub is set; a live run needs MNEMA_AI_DEEPSEEK_API_KEY and
// MNEMA_AI_OPENROUTER_API_KEY in the environment (docs/deploy/selfhost-local.md, "AI provider layer"). It reruns every time: the
// environment is not a Gradle input, so an up-to-date result would hide a changed prompt, route or key.
tasks.register<Test>("goldenEval") {
    group = "verification"
    description = "Runs the golden eval (GoldenEvalRunner) when MNEMA_AI_EVAL=live|stub is set; not part of check or quality."
    val tests = sourceSets.test.get()
    testClassesDirs = tests.output.classesDirs
    classpath = tests.runtimeClasspath
    filter { includeTestsMatching("*GoldenEvalRunner") }
    outputs.upToDateWhen { false }
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = false }
}
