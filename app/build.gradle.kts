plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kz.vyrez.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "kz.vyrez.phone"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "vyrez123"
            keyAlias = "vyrez"
            keyPassword = "vyrez123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources { noCompress += listOf("tflite") }
}

dependencies {
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
