plugins { id("com.android.application") }

android {
    namespace = "com.example.blockhost"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.blockhost"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "0.6.1"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared") }
        }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging { jniLibs.useLegacyPackaging = true }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
