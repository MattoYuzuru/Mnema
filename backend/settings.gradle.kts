pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.springframework.boot") version "3.5.7"
        id("io.spring.dependency-management") version "1.1.7"
        kotlin("jvm") version "2.1.10"
        kotlin("plugin.spring") version "2.1.10"
        kotlin("plugin.jpa") version "2.1.10"
    }
}
rootProject.name = "mnema"
include("services:auth", "services:user")
