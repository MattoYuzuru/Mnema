pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.springframework.boot") version "3.5.16"
        id("io.spring.dependency-management") version "1.1.7"
        kotlin("jvm") version "2.4.20"
        kotlin("plugin.spring") version "2.4.20"
        kotlin("plugin.jpa") version "2.4.20"
    }
}

rootProject.name = "mnema"
include(
    "services:core",
    "services:media",
    "services:import",
    "services:ai",
    "services:learning",
    "services:identity-account"
)
