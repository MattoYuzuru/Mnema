allprojects {
    group = "app.mnema"
    version = "0.0.1-SNAPSHOT"

    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "org.jetbrains.kotlin.plugin.jpa")
    apply(plugin = "io.spring.dependency-management")

    java {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } // LTS
    }

    tasks.withType<Test> { useJUnitPlatform() }

    // Общие kotlin-настройки
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        }
    }

    // Общие зависимости
    dependencies {
        "implementation"("com.fasterxml.jackson.module:jackson-module-kotlin")
        "implementation"("org.jetbrains.kotlin:kotlin-reflect")

        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testImplementation"("org.springframework.security:spring-security-test")
        "testImplementation"("io.mockk:mockk:1.13.+")
    }
}
