plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nova.feature.localllm"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    api(project(":core:llm"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // MediaPipe's LLM Inference API. Ships large native libraries — it is the reason the APK
    // grows substantially — but it is the supported path for running Gemma on Android and
    // avoids hand-rolling an NDK build of llama.cpp.
    implementation(libs.mediapipe.tasks.genai)

    testImplementation(libs.junit)
}
