@file:Suppress("unused", "UnstableApiUsage", "RedundantSuppression")

import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

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
        versionCode = 33
        versionName = "0.0.33"
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
        jniLibs {
            // androidx.graphics:graphics-path ships this prebuilt without an NDK strip tool
            // available to match it, so the strip task can't touch it anyway - telling AGP to
            // keep its debug symbols outright stops it from trying (and logging the warning).
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
        }
    }
}

// Copies the release .aab from AGP's default build output location
// (app/build/outputs/bundle/release/app-release.aab) to
// app/dist/<appName>-<versionName>.aab (already gitignored) -- a
// separate, deliberately-chosen destination outside the build/ directory,
// so it survives a clean build. (Originally this copied to app/release/
// instead: don't rename it back to that. app/release/ turned out to
// collide with Android Studio's own "Generate Signed Bundle" wizard,
// which independently remembers/defaults to a <module>/release/
// destination of its own and writes its own app-release.aab there on
// every signed-bundle build -- completely unrelated to this task, but
// landing in the exact same folder, which made it look like this task
// wasn't deleting the original when actually a second, IDE-driven copy
// was reappearing after each build. dist/ doesn't collide with anything.)
// Critically, this must run *after* AGP's own internal
// "produce...BundleIdeListingFile" task, which declares the bundle at
// its default name/location as one of its own inputs - deleting it any
// earlier fails that task's input validation with "file doesn't exist".
//
// Defined as a proper typed task class (not a closure passed to
// tasks.register) with Provider/Property-typed inputs: an earlier version
// captured the whole AGP `variant` object inside a doLast {} closure, and
// resolved variant.artifacts.get(SingleArtifact.BUNDLE) at execution time
// from within that closure. `variant` internally holds live references to
// Project, Configuration, and other Task objects (JavaCompile, etc.) --
// none of which the configuration cache is able to serialize, so every
// build failed to cache with errors naming exactly those types. Declaring
// bundleFile as a RegularFileProperty and wiring it from
// variant.artifacts.get(...) (itself a Provider<RegularFile>) at
// configuration time means only the resolved file path is ever captured --
// the task action itself never touches `variant` at all.
abstract class RenameBundleTask : DefaultTask() {
    @get:InputFile
    abstract val bundleFile: RegularFileProperty

    @get:OutputFile
    abstract val destinationFile: RegularFileProperty

    @TaskAction
    fun rename() {
        val file = bundleFile.get().asFile
        logger.lifecycle("renameBundle: source bundle at $file (exists=${file.exists()})")
        if (file.exists()) {
            val destination = destinationFile.get().asFile
            destination.parentFile.mkdirs()
            file.copyTo(destination, overwrite = true)
            logger.lifecycle("renameBundle: copied to $destination")
            // File.delete() never throws on failure, it just returns false --
            // check it explicitly so a failed delete (e.g. something else
            // still has the source file open/locked at this point) shows up
            // in the log instead of silently leaving the original behind
            // with no indication why.
            if (file.delete()) {
                logger.lifecycle("renameBundle: removed original $file")
            } else {
                logger.warn("renameBundle: could not delete original $file after copying -- it may be locked by another process; the copy at $destination is still correct")
            }
        } else {
            logger.lifecycle("renameBundle: expected bundle file not found at $file - skipping rename")
        }
    }
}

// Copies the release APK(s) from AGP's default build output location
// (app/build/outputs/apk/release/) to app/dist/ too, alongside the
// renamed bundle above. Unlike RenameBundleTask, this doesn't delete the
// originals -- there's no equivalent reason to (no known collision with
// anything else that writes to the APK output directory), so this is a
// plain copy, not a move.
//
// APK artifacts are exposed via SingleArtifact.APK: despite the name, this
// resolves to a *directory* (marked Artifact.ContainsMany in AGP's own
// docs), since a variant can in principle produce more than one APK
// (per-ABI splits, etc.), even though this project's release variant only
// ever produces one. Copying every .apk file found in that directory
// handles both cases without needing to special-case one vs. many, and
// without needing AGP's BuiltArtifactsLoader machinery (which exists for
// reading the accompanying metadata file precisely -- not needed here
// since a plain file-extension filter already skips it).
abstract class CopyApkTask : DefaultTask() {
    @get:InputFiles
    abstract val apkDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val destinationDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val srcDir = apkDirectory.get().asFile
        val destDir = destinationDirectory.get().asFile
        destDir.mkdirs()

        val apkFiles = srcDir.listFiles { f -> f.extension == "apk" } ?: emptyArray()
        logger.lifecycle("copyApk: found ${apkFiles.size} apk file(s) in $srcDir")
        apkFiles.forEach { apk ->
            val dest = File(destDir, apk.name)
            apk.copyTo(dest, overwrite = true)
            logger.lifecycle("copyApk: copied to $dest")
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        // NOTE: was "MX3ButtonMapper" (leftover from the sibling project) -- corrected.
        val appName = "z2mdash"
        val versionName = variant.outputs.first().versionName
        val variantNameCapitalized = variant.name.replaceFirstChar { it.uppercase() }
        val ideListingTaskName = "produce${variantNameCapitalized}BundleIdeListingFile"

        // outputFileName only renames the file within AGP's default output
        // directory (app/build/outputs/apk/release/) -- getting it into
        // app/dist/ too still needs the separate copyApk task below, same
        // as the bundle.
        variant.outputs.forEach { output ->
            output.outputFileName.set("$appName-${versionName.get()}.apk")
        }

        val renameBundle = tasks.register("renameBundle$variantNameCapitalized", RenameBundleTask::class.java) {
            group = "build"
            description = "Copies the $variantNameCapitalized .aab to app/dist/$appName-<versionName>.aab"
            mustRunAfter(ideListingTaskName)
            bundleFile.set(variant.artifacts.get(SingleArtifact.BUNDLE))
            destinationFile.set(layout.projectDirectory.file("dist/$appName-${versionName.get()}.aab"))
        }

        val copyApk = tasks.register("copyApk$variantNameCapitalized", CopyApkTask::class.java) {
            group = "build"
            description = "Copies the $variantNameCapitalized apk(s) to app/dist/"
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            destinationDirectory.set(layout.projectDirectory.dir("dist"))
        }

        // Hooks both onto their standard task graphs, so they also run
        // automatically from Android Studio's Build menu flows (which
        // invoke bundleRelease/assembleRelease directly), not just when
        // run explicitly by name.
        afterEvaluate {
            tasks.named("bundle$variantNameCapitalized") {
                finalizedBy(renameBundle)
            }
            tasks.named("assemble$variantNameCapitalized") {
                finalizedBy(copyApk)
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    // ProcessLifecycleOwner - drives connect/disconnect off whether the app as a whole (not just
    // one Activity) is actually foregrounded, so e.g. an Activity recreation on rotation doesn't
    // get mistaken for the app being backgrounded. See Z2mDashApplication's lifecycle observer.
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")

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

    // TV-flavoured chrome (focus rings/scale, nav rail) for the small set of
    // places this app branches on isTelevision() - see ui/tv/TvSupport.kt.
    // Deliberately not androidx.tv:tv-foundation: that artefact is deprecated,
    // its lazy-list D-pad/focus-search functionality having been folded into
    // plain androidx.compose.foundation (already pulled in via compose-bom
    // above) since Compose Foundation 1.7.0.
    implementation("androidx.tv:tv-material:1.1.0")

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

    // QR generation for sharing a broker's credentials via MX3Launcher's
    // credential relay (see data/CredentialShareClient.kt) - same library
    // and version MX3Launcher's own app already uses for its TV-pairing QR.
    implementation("com.google.zxing:core:3.5.4")
}
