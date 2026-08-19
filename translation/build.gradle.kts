plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "yaku.translation"
}

dependencies {
    implementation(projects.core.common)

    implementation(libs.onnxruntime)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp.core)
    implementation(libs.okio)
    implementation(libs.injekt)
    implementation(libs.logcat)
}
