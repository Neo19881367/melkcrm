import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-independent app logic shared by the Android app: local storage contract, repositories,
// sync engine, matching runs, reports, reminders and the HTTP client for the backend.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // LocalStoreContract lives in testFixtures so the Android app runs the same tests against Room.
    `java-test-fixtures`
}

group = "ir.melkcrm"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.core)
    api(libs.ktor.client.core)

    testFixturesApi(libs.kotlin.test)
    testFixturesApi(libs.junit)
    testFixturesApi(libs.kotlinx.coroutines.test)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
