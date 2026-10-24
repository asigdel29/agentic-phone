// build.gradle.kts: the app the core is reached through.
//
// History
//   2026-08-08  A. Sigdel  Created, empty of everything but a screen. The
//                          credential, the turn loop and the tools follow.
//   2026-08-09  A. Sigdel  A release build, and the one R8 rule without which
//                          it installs and dies at the first native call.
//   2026-08-13  A. Sigdel  Reads the signing variables the way a daemon cannot
//                          make stale, #514.
//
// The two SDK levels below are decisions, not defaults, and both are the kind
// somebody tidies up. Each carries its reason at the line.

plugins {
    id("com.android.application")

    // Required whenever `compose = true`, and separate from the Kotlin AGP 9
    // bundles. Version in settings.gradle.kts.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.getlora.wattrouter.app"
    // 37 because Compose 1.12 and later require it. Two properties for one
    // platform: Android ships minor platform releases now and the SDK installs
    // this one as `android-37.1`, so `compileSdk = 37` alone fails with "Failed
    // to find target with hash string 'android-37'" while the directory sits
    // there.
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.getlora.wattrouter"
        minSdk = 29

        // 35 while the phone-driving layer is built. Targeting 36 opts into
        // Android 16's enforced edge-to-edge, which cannot be turned off, and
        // that puts node bounds in *driven* apps under the system bars: a tap
        // at an element's centre can land on the navigation bar. Nothing forces
        // the bump: this app is sideloaded and has no store deadline.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // arm64-v8a only, matching the core. A second ABI without a second .so
        // is an APK that installs and cannot load.
        ndk { abiFilters += "arm64-v8a" }

        // Where the app asks a model to answer. Build-time rather than a
        // setting, and #513 says why: a field that repoints the endpoint is a
        // field the agent can repoint, because the agent can drive the screen.
        // The environment is read once, here, and a build that says nothing
        // gets the provider.
        //
        // The mirror of WATTROUTER_UPSTREAM in router/src/config.rs, spelled
        // the same on purpose: one idea, two places it can be set.
        //
        // providers.environmentVariable rather than System.getenv, and the
        // difference is not style. A build script runs inside a long-lived
        // daemon and System.getenv reads *that process's* environment, which
        // was fixed when the daemon started, so exporting a variable and
        // rebuilding gets the previous value, silently, for as long as the
        // daemon survives. This form is an input Gradle tracks, so changing it
        // invalidates the task instead of being ignored by it.
        buildConfigField(
            "String",
            "UPSTREAM_BASE_URL",
            "\"${providers.environmentVariable("WATTROUTER_UPSTREAM")
                .getOrElse("https://api.neuralwatt.com/v1")}\"",
        )
    }

    // From the environment, and the keystore is never tracked. A repository
    // that will not track a Gradle wrapper jar is not going to track a signing
    // key. Absent the environment there is no config, the release build
    // assembles unsigned, and `just android-release` says which happened.
    //
    // providers.environmentVariable throughout, for the reason spelled out
    // twenty lines above and applied to UPSTREAM but not to these four: a
    // daemon's System.getenv is its environment as it was when it started. #514
    // is what that costs here rather than there. Export the four into a shell
    // that reuses an older daemon and there is no signing config, so the
    // release assembles unsigned, and `just android-release` then reads the
    // variables from its own shell and says the opposite: signed with, over an
    // APK that is not. A recipe reporting the state of the wrong process is
    // worse than a build that quietly did nothing.
    val store = providers.environmentVariable("WATTROUTER_KEYSTORE").orNull
    if (store != null) {
        signingConfigs {
            create("release") {
                storeFile = file(store)
                storePassword =
                    providers.environmentVariable("WATTROUTER_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("WATTROUTER_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("WATTROUTER_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            // On, which is the point of this build type: it is what makes the
            // JNI keep rule in proguard-rules.pro load-bearing rather than
            // decorative.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true

        // Off by default in AGP 8 and later, and buildConfigField above is
        // silently dropped without it: the generated class simply has no such
        // member and the reference fails to compile, naming the field rather
        // than the flag.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))

    // One version for every Compose artefact, which is what the bill of
    // materials is for: the compiler and the runtime disagreeing is a class of
    // failure that reports itself as a missing method at runtime.
    val compose = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(compose)
    androidTestImplementation(compose)

    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")



    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
