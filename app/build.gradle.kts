plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseVersionName = providers.gradleProperty("releaseVersionName").orElse("0.9").get()
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orElse("1").get().toIntOrNull()
    ?: throw GradleException("releaseVersionCode must be a positive integer.")

val releaseKeystore = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE").orNull
val releaseKeystorePassword = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD").orNull
val baseApplicationId = "com.workeffortwaste"
val debugApplicationId = "$baseApplicationId.debug"
val releaseSigningConfigured = listOf(
    releaseKeystore,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val releaseBuildRequested = gradle.startParameter.taskNames.any { "Release" in it }

if (releaseBuildRequested && !releaseSigningConfigured) {
    throw GradleException(
        "Release builds require ANDROID_RELEASE_KEYSTORE, ANDROID_RELEASE_KEYSTORE_PASSWORD, " +
            "ANDROID_RELEASE_KEY_ALIAS, and ANDROID_RELEASE_KEY_PASSWORD.",
    )
}

android {
    namespace = "com.workeffortwaste"
    compileSdk = 37

    defaultConfig {
        applicationId = baseApplicationId
        // Android 8+ USB Host/MIDI support; newer receiver and theme APIs are guarded at runtime.
        minSdk = 26
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    buildFeatures {
        compose = true
    }
    // Keep Java and Kotlin bytecode targets aligned with the documented JDK 17 build.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystore))
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }
    buildTypes {
        if (releaseSigningConfigured) {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = "androidx.compose:compose-bom:2026.09.00"
    implementation(platform(composeBom))
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    // Protocol tests are plain JVM tests and do not require an emulator or physical FM1.
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform(composeBom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Android 17 removed the hidden InputManager API used by older Espresso releases.
    androidTestImplementation("androidx.test:runner:1.8.0-alpha01")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.8.0-alpha01")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Runs screenshot capture directly (connectedAndroidTest would uninstall the app and delete the PNGs).
val adb = extensions.getByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>()
    .sdkComponents.adb.get().asFile.path
// Generated at the repository root for manual README updates.
val themeScreenshotOutput = rootProject.projectDir.resolve("screenshots")

val captureThemeScreenshots = tasks.register<Exec>("captureThemeScreenshots") {
    dependsOn("installDebug", "installDebugAndroidTest")
    commandLine(
        adb, "shell", "am", "instrument", "-w", "-e", "class",
        "com.workeffortwaste.ScreenshotCapture",
        "$debugApplicationId.test/androidx.test.runner.AndroidJUnitRunner",
    )
}

tasks.register<Exec>("screenshotThemes") {
    group = "verification"
    description = "Captures themed app screens into <repo>/screenshots, replacing any previous images."
    dependsOn(captureThemeScreenshots)
    val outputPath = themeScreenshotOutput.path
    commandLine(
        "sh", "-c",
        "rm -rf '$outputPath' && mkdir -p '$outputPath' && " +
            "'$adb' pull /sdcard/Android/data/$debugApplicationId/files/theme-screenshots/. '$outputPath'",
    )
}
