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

    plugins.withId("java") {
        val sourceSets = extensions.getByType<org.gradle.api.tasks.SourceSetContainer>()
        sourceSets["test"].java.srcDir(rootProject.file("test-support/java"))
        tasks.register<JavaExec>("testInventory") {
            group = "verification"
            description = "Discovers all JUnit test classes without running tests or starting fixtures."
            dependsOn("testClasses")
            classpath = sourceSets["test"].runtimeClasspath
            mainClass.set("app.mnema.testing.TestInventory")
            maxHeapSize = "256m"
            args(layout.buildDirectory.file("test-inventory.json").get().asFile.absolutePath)
            args(sourceSets["test"].output.classesDirs.files.map { it.absolutePath })
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        dependsOn("testInventory")
        // Each fork owns its static PostgreSQL/HTTP fixtures. Methods remain serial inside it.
        // Keep a bounded default on the shared workstation; CI shards may choose one fork.
        maxParallelForks = providers.gradleProperty("testForks").map {
            it.toInt().also { forks -> require(forks in 1..2) { "testForks must be 1 or 2" } }
        }.getOrElse(2)
        maxHeapSize = "512m"
        systemProperty("junit.jupiter.execution.parallel.enabled", "false")
        val shard = providers.gradleProperty("testShard")
        inputs.property("testShard", shard.getOrElse("all"))
        if (shard.isPresent && name != "goldenEval") {
            val parts = shard.get().split("/").map(String::toInt)
            require(parts.size == 2 && parts[1] in 1..4 && parts[0] in 1..parts[1]) { "testShard must be index/count, count <= 4" }
            // Partition every compiled top-level class, independent of naming/annotations. Nested
            // tests stay with their outer class; the unchanged JUnit engine discovers the tests.
            doFirst {
                val roots = testClassesDirs.files
                val selected = testClassesDirs.asFileTree.files.filter { file ->
                    if (file.extension != "class") false else {
                        val root = roots.first { file.toPath().startsWith(it.toPath()) }
                        val className = file.relativeTo(root).invariantSeparatorsPath
                            .removeSuffix(".class").substringBefore('$').replace('/', '.')
                        Math.floorMod(className.hashCode(), parts[1]) == parts[0] - 1
                    }
                }
                require(selected.isNotEmpty()) { "Empty test shard" }
                selected.forEach { file ->
                    val root = roots.first { file.toPath().startsWith(it.toPath()) }
                    include(file.relativeTo(root).invariantSeparatorsPath)
                }
            }
        }
        jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:${mockitoAgent.asPath}") })
        finalizedBy("jacocoTestReport")
    }

    tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach {
        val coverageShards = providers.gradleProperty("coverageShards").map(String::toInt).getOrElse(0)
        require(coverageShards in 0..4) { "coverageShards must be 1..4, or absent" }
        executionData.setFrom(if (coverageShards == 0) listOf(layout.buildDirectory.file("jacoco/test.exec"))
            else (1..coverageShards).map { layout.buildDirectory.file("jacoco/test-shard-$it.exec") })
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
        // goldenEval is the opt-in AI eval (services/learning): it needs provider keys for a live run and is not a quality gate.
        subprojects.flatMap { it.tasks.withType<Test>() }.filter { it.name != "goldenEval" },
        subprojects.flatMap { project ->
            project.tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().toList()
        },
        "coverageBaselineCheck",
        "testInventoryCheck",
        "jacocoRootReport"
    )
}

tasks.register<Exec>("testInventoryCheck") {
    group = "verification"
    description = "Requires every discovered JUnit class and only inventoried opt-in skips."
    dependsOn(subprojects.flatMap { it.tasks.withType<Test>() }.filter { it.name != "goldenEval" })
    workingDir = rootDir
    commandLine("python3", "../scripts/backend_test_evidence.py", "verify-local")
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

        dependsOn(coverageProjects.flatMap { it.tasks.withType<Test>() }.filter { it.name != "goldenEval" })

        executionData.from(
            coverageProjects.map { project ->
                project.layout.buildDirectory.asFileTree.matching {
                    val count = providers.gradleProperty("coverageShards").map(String::toInt).getOrElse(0)
                    if (count == 0) include("jacoco/test.exec")
                    else (1..count).forEach { include("jacoco/test-shard-$it.exec") }
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
