import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing details live in keystore.properties (project root, kept out of version control),
// so the password is never written into this file.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.hcrdevelopers.spotshield"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.hcrdevelopers.spotshield"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Signed with the SPoT Shield release key when keystore.properties is present.
            signingConfigs.findByName("release")?.let { signingConfig = it }

            // Do not set packageScope here: limiting R8 to androidx/kotlin packages made the
            // release build crash on startup (IllegalAccessError in kotlin.collections).
            optimization {
                enable = true
            }
            isShrinkResources = true
        }

        // Same optimized code as release, signed with the debug key and installable next to the
        // debug app. Use it to judge real speed on a phone; never publish it.
        create("releaseTest") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".releasetest"
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    lint {
        // The app has no Fragment dependency at all, yet this check fires on every
        // registerForActivityResult call and fails release builds (lint "vital").
        disable += "InvalidFragmentVersionForActivityResult"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.compose.animation:animation")
    implementation("com.android.billingclient:billing-ktx:9.1.0")

    // Installs the baseline profile (src/main/baseline-prof.txt) so release builds start and scroll faster.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Debug only: reports leaked Activities and other objects while the app is used.
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

    testImplementation(libs.junit)
    // Real org.json for JVM unit tests (the Android stubs return nothing).
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}