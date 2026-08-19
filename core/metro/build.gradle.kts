plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.metro)
}

android {
    namespace = "yaku.core.metro"
}

dependencies {
    implementation(libs.metro.runtime)
}
