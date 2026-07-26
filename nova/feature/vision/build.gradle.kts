plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nova.feature.vision"
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
    api(project(":core:agent"))
    implementation(project(":feature:accessibility"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Bundled rather than the Play-Services-delivered variant: it adds a few megabytes to the
    // APK but works offline on first run, which is the whole point of choosing it.
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
}
