plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.foldingscreen"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.foldingscreen"
        // 最低兼容 Android 10（API 29）
        minSdk = 29
        // 目标 SDK 34
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // AndroidX 规范：core-ktx 提供前台服务类型启动（mediaProjection）等兼容能力
    implementation("androidx.core:core-ktx:1.12.0")
}
