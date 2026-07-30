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

android {
    namespace = "com.nova.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nova.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")
        buildConfigField("String", "APIFY_TOKEN", "\"$apifyToken\"")
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
}
