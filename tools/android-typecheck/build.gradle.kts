// Type-checks the Android app's Kotlin sources (main and unit tests) on the JVM when Google Maven is not
// reachable: Compose Multiplatform (desktop) stands in for Jetpack Compose, Robolectric's android-all jar
// for the framework, and small stubs for AndroidX libraries that are only published on Google Maven.
// It is a compile check only; nothing here runs, and it is no substitute for building the real app.
// Run from the repository root:  ./gradlew -p tools/android-typecheck compileTestKotlin
plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

val aar: Configuration by configurations.creating { isTransitive = false }

// Google-only groups (lifecycle, savedstate, collection, annotation) are not reachable here; the classes the
// app uses from them are stubbed in ./stubs with the same names and signatures.
configurations.all {
    for (g in listOf("androidx.annotation", "androidx.collection", "androidx.lifecycle", "androidx.savedstate", "androidx.arch.core",
        "org.jetbrains.androidx.lifecycle", "org.jetbrains.androidx.savedstate", "org.jetbrains.compose.annotation-internal")) exclude(group = g)
}

dependencies {
    implementation("ir.melkcrm:core")
    implementation("ir.melkcrm:client")
    implementation("org.jetbrains.compose.runtime:runtime-desktop:1.8.2")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:1.8.2")
    implementation("org.jetbrains.compose.animation:animation-desktop:1.8.2")
    implementation("org.jetbrains.compose.material3:material3-desktop:1.8.2")
    implementation("org.jetbrains.compose.material:material-icons-extended-desktop:1.7.3")
    implementation("org.jetbrains.compose.ui:ui-desktop:1.8.2")
    implementation("io.ktor:ktor-client-android:3.6.0")
    compileOnly("org.robolectric:android-all:15-robolectric-12650502")
    aar("org.osmdroid:osmdroid-android:6.1.20@aar")
    compileOnly(files(layout.buildDirectory.file("aar/osmdroid.jar")))

    testImplementation(testFixtures("ir.melkcrm:client"))
    testCompileOnly("org.robolectric:android-all:15-robolectric-12650502")
    testCompileOnly(files(layout.buildDirectory.file("aar/osmdroid.jar")))
}

val unpackAar by tasks.registering(Copy::class) {
    from({ aar.map { zipTree(it) } }) { include("classes.jar"); rename { "osmdroid.jar" } }
    into(layout.buildDirectory.dir("aar"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.FlowPreview",
        )
    }
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

sourceSets {
    main {
        kotlin.srcDir("../../android/app/src/main/kotlin")
        kotlin.srcDir("stubs")
        kotlin.srcDir(layout.buildDirectory.dir("generated/r"))
    }
    test {
        kotlin.srcDir("../../android/app/src/test/kotlin")
        kotlin.srcDir("test-stubs")
    }
}

// R class from the generated resources (strings) and the resource folders.
val generateR by tasks.registering {
    val res = file("../../android/app/src/main/res")
    val out = layout.buildDirectory.file("generated/r/ir/melkcrm/app/R.kt")
    inputs.dir(res)
    outputs.file(out)
    doLast {
        val strings = Regex("<string name=\"([a-zA-Z0-9_]+)\"").findAll(File(res, "values/strings.xml").readText()).map { it.groupValues[1] }.toList()
        fun names(dir: String): List<String> = res.listFiles().orEmpty().filter { f: File -> f.isDirectory && (f.name == dir || f.name.startsWith("$dir-")) }
            .flatMap { d: File -> d.listFiles().orEmpty().toList() }.map { f: File -> f.nameWithoutExtension }.distinct()
        val sb = StringBuilder("package ir.melkcrm.app\n\nobject R {\n")
        fun block(name: String, keys: List<String>) {
            sb.append("    object $name {\n")
            keys.forEachIndexed { i, k -> sb.append("        const val $k: Int = ${0x7f000000 + i}\n") }
            sb.append("    }\n")
        }
        block("string", strings)
        for (d in listOf("drawable", "mipmap", "font", "xml", "raw")) block(d, names(d))
        sb.append("}\n")
        out.get().asFile.apply { parentFile.mkdirs(); writeText(sb.toString()) }
    }
}
tasks.named("compileKotlin") { dependsOn(generateR, unpackAar) }
