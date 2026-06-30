plugins { id("com.android.application") }

android {
    namespace = "com.example.blockhost"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.blockhost"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
