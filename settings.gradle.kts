pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "melk-crm"

// Pure-JVM build: shared domain logic and the backend. Needs only Maven Central.
// :client holds the app logic that does not need Android (storage abstraction, sync, matching runs).
// The Android app is a separate Gradle build in ./android that includes this build.
include(":core")
include(":client")
include(":backend")
