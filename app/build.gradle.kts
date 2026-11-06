plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.lovja.prompter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lovja.prompter"
        // Dış ekrana içerik gösterme Samsung'da Android 14 (API 34) ister.
        minSdk = 34
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.2.$runNumber"
    }

    signingConfigs {
        // Sabit anahtar: yeni sürümler eskisinin üstüne kurulabilsin diye.
        create("lovja") {
            storeFile = file("lovja-debug.keystore")
            storePassword = "lovjaprompter"
            keyAlias = "lovja"
            keyPassword = "lovjaprompter"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("lovja")
        }
        debug {
            signingConfig = signingConfigs.getByName("lovja")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val camerax = "1.4.1"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.window:window:1.4.0")
    implementation("androidx.window:window-core:1.4.0")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
