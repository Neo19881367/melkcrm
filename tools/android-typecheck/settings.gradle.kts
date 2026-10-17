pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "android-typecheck"

// Same substitutions as android/settings.gradle.kts.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("ir.melkcrm:core")).using(project(":core"))
        substitute(module("ir.melkcrm:client")).using(project(":client"))
    }
}
