plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

// Library modules that don't define the "store" dimension need a default
// so they can consume core:ssh (which has foss/full variants).
//
// Also applies a project-wide lint tweak: skip `MissingTranslation`.
// Haven ships ~10 locales maintained by community PRs, and new strings
// necessarily land before every locale catches up. Having this check
// block CI would either force strings to ship alongside 10 translation
// updates or delay features indefinitely. The rest of the default
// lint set stays strict so genuine regressions still fail the build.
subprojects {
    afterEvaluate {
        extensions.findByType<com.android.build.gradle.LibraryExtension>()?.apply {
            if (flavorDimensionList.none { it == "store" }) {
                defaultConfig {
                    missingDimensionStrategy("store", "full")
                }
            }
            lint.disable += "MissingTranslation"
        }
        extensions.findByType<com.android.build.gradle.AppExtension>()?.apply {
            lintOptions.disable.add("MissingTranslation")
        }
    }
}
