plugins {
    // Declared without `apply`, so the plugin classloader is not loaded again in every subproject.
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
}

// Coordinates under which a consuming build substitutes the modules when it uses this repository
// via `includeBuild` (e.g. `implementation("eu.tintera.ai:ai-core")`).
subprojects {
    group = "eu.tintera.ai"
}
