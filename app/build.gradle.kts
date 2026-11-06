plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.autotype"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.autotype"
        minSdk = 28          // BluetoothHidDevice needs Android 9 (API 28)+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party libraries needed: the app uses only the Android platform APIs.
dependencies {
}
