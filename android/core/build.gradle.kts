// build.gradle.kts: the core, as an Android library.
//
// History
//   2026-08-08  A. Sigdel  Created.
//   2026-08-08  A. Sigdel  Moved under core/ when the app arrived beside it.
//
// What this module is: `Core.kt` and the shared object it declares. The library
// itself is built by `just android-core` from the same crate iOS links, and is
// not built by Gradle, since cargo already knows how, and an NDK build driven from
// here would be a second way to produce one artefact.
//
// So `jniLibs.srcDirs` points at what that script wrote. A build with nothing
// there produces an AAR that compiles and fails to load, which is why
// `just android` runs the core build first.
//
// The source layout is `src/main/kotlin` rather than Gradle's `src/main/java`,
// which is the repository's convention and also load-bearing: router/src/jni.rs
// reads Core.kt by path for its symbol-parity test, so the path is part of a
// contract rather than a preference. Moving it edits the Rust in the same
// commit.

plugins {
    // AGP 9 brings Kotlin support with it, and applying
    // org.jetbrains.kotlin.android alongside is an error rather than a
    // duplicate. The version lives in settings.gradle.kts, once for both
    // modules, and why is written there.
    id("com.android.library")
}

android {
    namespace = "com.getlora.wattrouter"

    // Matching the app rather than trailing it. A library compiled against an
    // older platform than its consumer is legal and warns, and the warning is
    // the kind people learn to scroll past.
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        // 29 rather than lower, and #229 is why: since API 29 an app cannot
        // exec a file it wrote into its own data directory, which decides how
        // the terminal in #233's item 5 is built. Supporting a phone where that
        // is not true is supporting a different app.
        minSdk = 29

        // arm64-v8a only, matching scripts/build-android-core.sh. A second ABI
        // here without a second .so is an AAR that installs and cannot load.
        ndk { abiFilters += "arm64-v8a" }

        // Instrumented tests are the only ones that can load the library: it is
        // built for aarch64-linux-android and a JVM test runs on the host.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Only the jniLibs line is still needed. src/main/kotlin and its two
    // siblings are Kotlin source roots by default now that AGP bundles Kotlin;
    // declaring them again through `sourceSets[...].java` is what broke on AGP
    // 9.3, which returns a decorated type the old interface cannot be cast to.
    // Removed on the AGP 9.3 bump: the sourceSets accessor answers a decorated
    // type the old interface cannot be cast to, and the library is now written
    // straight into the default location instead. scripts/build-android-core.sh
    // puts it in src/main/jniLibs, which AGP finds without being told.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The runtime only, and no compiler plugin. `@Serializable` would need one,
    // and the encoding here is hand-written anyway: what a message leaves out
    // is a decision rather than a default, so `buildJsonObject` is the honest
    // shape and the plugin would be a version to keep in step with AGP for
    // nothing.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

    // A cold Flow is the seam a model's answer arrives through: nothing is
    // requested until something collects, cancelling the collector cancels the
    // request, and a failure surfaces where it is read rather than as a second
    // parameter every caller has to remember. `api` rather than
    // `implementation` because Inference.complete returns one, so the app
    // module needs the type on its own compile classpath.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.12.0")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.12.0")

    // JUnit by explicit coordinate rather than kotlin("test"): AGP 9 bundles
    // Kotlin and does not expose its version to that helper, so the helper
    // resolves to nothing and every @Test is an unresolved reference.
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}


