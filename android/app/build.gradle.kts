import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath: String? = System.getenv("GR_KEYSTORE")

android {
    namespace = "tw.yang.ghostroom"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.yang.ghostroom"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEFAULT_SERVER", "\"https://ghostroom-3r4.pages.dev\"")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("fixed") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("GR_KEYSTORE_PASS")
                keyAlias = System.getenv("GR_KEY_ALIAS")
                keyPassword = System.getenv("GR_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("fixed") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
}
