import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Read from local.properties, which is gitignored, so the key never reaches the repo.
 *
 * This does bake the key into the APK, which is fine for a personal build and wrong for a
 * published one — a shipped app should call a backend that holds the key instead. Absent key
 * simply means no task planner, and Nova behaves as it did before Phase 3.
 */
private val localProperties: Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

val openAiApiKey: String = localProperties.getProperty("openai.apiKey").orEmpty().trim()

/** Optional. Absent simply means web search is unavailable; everything else works. */
val apifyToken: String = localProperties.getProperty("apify.token").orEmpty().trim()

/**
 * An OpenAI-compatible server on the local network — LM Studio, Ollama, llama.cpp's server.
 *
 * Base URL only, e.g. `http://192.168.1.2:1234`. Absent falls back to the on-device model and
 * then to OpenAI, so this is purely additive. A machine on the same WiFi can hold a model far
 * larger than a phone has memory for, which is the whole point.
 */
val llmServerUrl: String = localProperties.getProperty("llm.serverUrl").orEmpty().trim()

android {
    namespace = "com.nova.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nova.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // On-device tests are the only way to exercise this app on the target phone: MIUI
        // refuses shell input injection, so the UI cannot be driven from adb.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")
        buildConfigField("String", "APIFY_TOKEN", "\"$apifyToken\"")
        buildConfigField("String", "LLM_SERVER_URL", "\"$llmServerUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:agent"))
    implementation(project(":core:llm"))
    implementation(project(":core:search"))
    implementation(project(":core:speech"))
    implementation(project(":feature:device"))
    implementation(project(":feature:accessibility"))
    implementation(project(":feature:localllm"))
    implementation(project(":feature:memory"))
    implementation(project(":feature:routines"))
    implementation(project(":feature:notifications"))
    implementation(project(":feature:vision"))
    implementation(project(":feature:comms"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
