plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oyzamil.openclicker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.oyzamil.openclicker"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.7"
    }
    signingConfigs {
        getByName("debug") {
            // Fixed key committed to the repo so every CI build has the SAME signature.
            // Updates then install over the old build and keep the accessibility permission.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
