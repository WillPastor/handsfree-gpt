plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.handsfreegpt"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.handsfreegpt"
        minSdk = 29 // Android 10: apps may share the mic with an accessibility service
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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

    lint {
        abortOnError = true
        // AGP/Gradle are pinned to what the current stable Android Studio supports; don't nag.
        disable += "AndroidGradlePluginVersion"
    }
}

dependencies {
    testImplementation(libs.junit)
}
