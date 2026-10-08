plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.privateentry"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.privateentry"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "1.8.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
