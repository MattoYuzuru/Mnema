plugins {
    id("org.springframework.boot") apply false
    id("io.spring.dependency-management") apply false
    kotlin("jvm") apply false
    kotlin("plugin.spring") apply false
    kotlin("plugin.jpa") apply false
    id("jacoco")
}

allprojects {
    group = "app.mnema"
    version = "0.0.1-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "jacoco")

    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoPluginExtension> {
        toolVersion = "0.8.11"
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        finalizedBy("jacocoTestReport")
    }

    tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}

tasks.register<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoRootReport") {
    group = "verification"
    description = "Generates an aggregate JaCoCo report for all backend modules."

    jacocoClasspath = configurations["jacocoAnt"]

    reports {
        xml.required.set(true)
        html.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoRootReport/jacocoRootReport.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/jacocoRootReport/html"))
    }
}

gradle.projectsEvaluated {
    tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoRootReport") {
        val coverageProjects = subprojects.filter { it.extensions.findByType<org.gradle.api.tasks.SourceSetContainer>() != null }

        dependsOn(coverageProjects.flatMap { it.tasks.withType<Test>() })

        executionData.from(
            coverageProjects.map { project ->
                project.layout.buildDirectory.asFileTree.matching {
                    include("jacoco/test.exec", "jacoco/test*.exec")
                }
            }
        )

        classDirectories.from(
            coverageProjects.map { project ->
                project.extensions.getByType<org.gradle.api.tasks.SourceSetContainer>()["main"].output
            }
        )

        sourceDirectories.from(
            coverageProjects.map { project ->
                project.extensions.getByType<org.gradle.api.tasks.SourceSetContainer>()["main"].allSource.srcDirs
            }
        )
    }
}
