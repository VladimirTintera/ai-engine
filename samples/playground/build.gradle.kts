plugins {
    // Declared once without `apply`, so the plugin classloader is shared by the modules.
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidDynamicFeature) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinCompose) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(playground.plugins.compose.multiplatform) apply false
}
