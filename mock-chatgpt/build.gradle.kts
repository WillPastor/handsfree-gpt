// Test double for the ChatGPT app, used only by scripts/e2e.sh on an emulator. Never ship it:
// it deliberately reuses ChatGPT's package name so HandsFree GPT treats it as the real thing.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.openai.chatgpt"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openai.chatgpt"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "mock-1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        // Polish checks that don't matter for a test fixture.
        disable += listOf("AndroidGradlePluginVersion", "DataExtractionRules", "MissingApplicationIcon", "SetTextI18n")
    }
}
