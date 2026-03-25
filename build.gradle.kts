plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.chaquopy) apply false
}

// Library modules that don't define the "store" dimension need a default
// so they can consume core:ssh (which has foss/full variants).
subprojects {
    afterEvaluate {
        extensions.findByType<com.android.build.gradle.LibraryExtension>()?.apply {
            if (flavorDimensionList.none { it == "store" }) {
                defaultConfig {
                    missingDimensionStrategy("store", "full")
                }
            }
        }
    }
}
