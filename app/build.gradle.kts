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
        versionCode = 5
        versionName = "0.1.0"
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
    // 외부 광고/분석 SDK나 불필요한 서드파티 라이브러리를 일절 포함하지 않는 순수 네이티브 구성입니다.
}
