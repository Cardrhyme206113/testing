plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cardrhyme.screentranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cardrhyme.screentranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(project(":ppocr-sdk"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.mlkit:translate:17.0.3")
}
