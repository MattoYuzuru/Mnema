plugins {
    id("org.springframework.boot") apply false
    id("io.spring.dependency-management") apply false
    id("jacoco")
}

allprojects {
    group = "app.mnema"
    version = "0.0.1-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "jacoco")
    // Boot 4.1.1 manages Tomcat 11.0.24; 11.0.25 and 11.0.26 carry Apache security fixes
    // (https://tomcat.apache.org/security-11.html). Remove once the Boot BOM catches up.
    extra["tomcat.version"] = "11.0.26"

    // Boot 4.1.1 manages Jackson 3.1.5; 3.1.7 fixes the core/databind DoS findings
    // recorded in docs/operations/production-image-inventory.md. Align the whole Jackson 3 BOM;
    // remove this override when Boot manages >=3.1.7 and the release scans pass.
    extra["jackson-bom.version"] = "3.1.7"

    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoPluginExtension> {
        toolVersion = "0.8.15"
    }

    // Mockito's inline mock maker needs its agent at JVM start; loading it dynamically is
    // deprecated since JDK 21 and prints a warning (https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3).
    val mockitoAgent = configurations.create("mockitoAgent")
    dependencies {
        add("mockitoAgent", "org.mockito:mockito-core") { isTransitive = false }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:${mockitoAgent.asPath}") })
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

tasks.register("quality") {
    group = "verification"
    description = "Runs backend compilation, tests, and aggregate coverage reporting."
    dependsOn(
        subprojects.flatMap { project ->
            project.tasks.matching { it.name in setOf("compileJava", "compileTestJava") }.toList()
        },
        subprojects.flatMap { it.tasks.withType<Test>() },
        subprojects.flatMap { project ->
            project.tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().toList()
        },
        "coverageBaselineCheck",
        "jacocoRootReport"
    )
}

tasks.register<Exec>("coverageBaselineCheck") {
    group = "verification"
    description = "Checks backend per-service line coverage against the current baseline."
    dependsOn(
        subprojects.flatMap { project ->
            project.tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().toList()
        },
        "jacocoRootReport"
    )
    workingDir = projectDir
    commandLine("python3", "scripts/check_coverage.py")
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
