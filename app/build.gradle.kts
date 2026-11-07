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
        versionCode = 5
        versionName = "0.4.0"
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
        // UI copy lives in Kotlin while the app is English-only (localisation is on the roadmap).
        disable += "SetTextI18n"
    }
}

dependencies {
    compileOnly(libs.androidx.annotation) // @RequiresApi only: nothing ships in the APK
    testImplementation(libs.junit)
}
