plugins {
    id("com.android.application")
}

android {
    namespace = "com.model90.metronome"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.model90.metronome"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Pure native configuration with zero ad/analytics SDKs or third-party dependencies.
}
