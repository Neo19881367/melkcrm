pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "melk-crm-android"

include(":app")

// From the parent build: the shared domain module (Jalali, money, matching engine, permissions,
// sync codec) and the platform-independent app logic (repositories, sync engine, session, reports),
// both tested there on the JVM. This build adds Room storage, settings, workers and the Compose UI.
includeBuild("..") {
    dependencySubstitution {
        substitute(module("ir.melkcrm:core")).using(project(":core"))
        substitute(module("ir.melkcrm:client")).using(project(":client"))
    }
}
