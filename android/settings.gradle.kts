// settings.gradle.kts: the Android build: the core, and the app over it.
//
// History
//   2026-08-08  A. Sigdel  Created as one module, an app being a directory with
//                          nothing in it until there was one.
//   2026-08-08  A. Sigdel  Split into :core and :app, there now being one.
//
// The core stays a library rather than the app absorbing it. It is what an
// instrumented test loads, and it is what a second surface (a share target, a
// quick-settings tile, an accessibility service in its own process) links
// without also taking on the app's manifest and its permissions.
//
// No Gradle wrapper. A wrapper is a jar, and this repository does not track
// binaries it cannot review; `just android` reports the Gradle it will use, the
// way scripts/test-ios.sh reports the xcodegen it needs.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    // Versions here rather than in each module, and so no root build file whose
    // only job is to hold three lines. `com.android.application` and
    // `com.android.library` come from one artefact, and a version declared in
    // two subprojects puts it on the classpath twice, and Gradle then resolves the
    // second as "already present with an unknown version" and fails naming
    // neither module.
    //
    // AGP 9 rather than 8: Homebrew's Gradle is 9.7, and AGP 8.13 relies on a
    // Gradle internal removed in 9.6. The Compose compiler version is not free
    // to choose: it must be the Kotlin AGP bundles. AGP bundling Kotlin does
    // not bring that plugin with it, and `compose = true` without it fails at
    // configuration time saying so.
    //
    // 9.3.1 rather than 9.0.0 since #357, which had cost two workarounds. The
    // bump is not free: AGP 9.3 answers a decorated type from the `sourceSets`
    // accessor that the old interface cannot be cast to, so the four srcDirs
    // calls in the two module files had to go. Three were redundant, since Kotlin
    // source roots are defaults now, and the fourth moved the library into
    // AGP's own jniLibs location, which needs no accessor.
    //
    // Gradle 9.7 is enough for it, which was the thing to check before bumping:
    // AGP 9.3 needing a newer Gradle would have moved ci.yml too.
    plugins {
        id("com.android.application") version "9.4.1"
        id("com.android.library") version "9.4.1"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "wattrouter"

include(":core")
include(":app")
