// Several AGP Variant API members used below (outputFileName, artifacts.get,
// onVariants/selector for this variant-configuration style) are still
// marked @Incubating - meaning they work correctly today but the API
// surface could change in a future AGP release, not that anything here is
// broken. This is the standard, conventional way to suppress that specific
// warning category for the whole build script.
@file:Suppress("UnstableApiUsage")

import com.android.build.api.artifact.SingleArtifact

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.odiousapps.z2mdash"
    compileSdk = 37

    // Disables the "Dependency metadata" signing block AGP embeds by
    // default for Google Play Console's own dependency tracking. This has
    // to live directly in source (not just an F-Droid prebuild step),
    // since F-Droid's reproducible-build verification also scans whatever
    // binary is published at the Binaries: URL - the same GitHub Release
    // APK this repo's own build produces - so that artifact needs this
    // disabled too, not just what F-Droid builds from source itself.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.odiousapps.z2mdash"
        minSdk = 26
        targetSdk = 37
        versionCode = 18
        versionName = "0.0.18"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            excludes += "/META-INF/*.RSA"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/services/reactor.blockhound.integration.BlockHoundIntegration"
        }
    }
}

// Renames the release .aab from AGP's default "app-release.aab" to
// "<appName>-<versionName>.aab", in place, within app/release/ (already
// gitignored) - which turns out to already be this project's actual bundle
// output location (confirmed by an AGP validation error naming that exact
// path as a declared input elsewhere), not a separate custom destination to
// copy into as originally assumed. Critically, this must run *after* AGP's
// own internal "produce...BundleIdeListingFile" task, which declares the
// bundle at its default name as one of its own inputs - renaming (or
// deleting) it any earlier fails that task's input validation with "file
// doesn't exist", which is what happened when this was ordered the other
// way around.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val appName = "Z2mDash"
        val versionName = variant.outputs.first().versionName
        val variantNameCapitalized = variant.name.replaceFirstChar { it.uppercase() }
        val ideListingTaskName = "produce${variantNameCapitalized}BundleIdeListingFile"

        // APK variant outputs support a directly settable filename, unlike
        // the bundle (AAB) case above - no separate rename/copy task needed.
        variant.outputs.forEach { output ->
            output.outputFileName.set("$appName-${versionName.get()}.apk")
        }

        val renameBundle = tasks.register("renameBundle$variantNameCapitalized") {
            group = "build"
            description = "Renames the $variantNameCapitalized .aab in place to $appName-<versionName>.aab"
            mustRunAfter(ideListingTaskName)
            doLast {
                val bundleFile = variant.artifacts.get(SingleArtifact.BUNDLE).get().asFile
                if (bundleFile.exists()) {
                    val renamedFile = File(bundleFile.parentFile, "$appName-${versionName.get()}.aab")
                    bundleFile.copyTo(renamedFile, overwrite = true)
                    bundleFile.delete()
                } else {
                    println("Expected bundle file not found at $bundleFile - skipping rename")
                }
            }
        }
        // Hooks the rename onto the standard "bundle" task graph, so it also
        // runs automatically from Android Studio's Build > Generate Signed
        // App Bundle flow (which invokes bundleRelease directly), not just
        // when this task is run explicitly by name.
        afterEvaluate {
            tasks.named("bundle$variantNameCapitalized") {
                finalizedBy(renameBundle)
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")

    // XML theme resources (Theme.Material3.*) used by AndroidManifest, separate from
    // the Compose Material3 Kotlin artefact below.
    implementation("com.google.android.material:material:1.14.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.10.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // MQTT client. Actively maintained, MQTT 3.1.1 + 5.0, robust TCP/SSL/WS/WSS
    // transports with real automatic-reconnect handling - this is what replaces
    // Paho's flaky websocket keepalive.
    implementation("com.hivemq:hivemq-mqtt-client:1.4.0")
    implementation("io.netty:netty-common:4.1.133.Final")
    implementation("io.netty:netty-handler:4.1.133.Final")
    implementation("io.netty:netty-codec:4.1.133.Final")
    implementation("io.netty:netty-codec-http:4.1.133.Final")
    implementation("io.netty:netty-transport:4.1.133.Final")
    implementation("io.netty:netty-buffer:4.1.133.Final")
    implementation("io.netty:netty-resolver:4.1.133.Final")
}
