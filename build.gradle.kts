plugins {
    alias(libs.plugins.multiplatform).apply(false)
    alias(libs.plugins.android.library).apply(false)
    alias(libs.plugins.android.multiplatform.library).apply(false)
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.kotlinCocoapods).apply(false)
    alias(libs.plugins.dokka).apply(false)
    alias(libs.plugins.vannitktech.maven.publish).apply(false)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

// Code quality
detekt {
    config.setFrom(files("config/detekt/detekt.yml"))
    // KMP detekt tasks derive baseline-<sourceSet>.xml from this base path.
    baseline = file("config/detekt/baseline.xml")
    buildUponDefaultConfig = true
}

ktlint {
    baseline.set(file("config/ktlint/baseline.xml"))
    ignoreFailures.set(false)
}

subprojects {
    if (name == "composeApp") return@subprojects
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    ktlint {
        debug.set(false)
        verbose.set(true)
        android.set(false)
        outputToConsole.set(true)
        baseline.set(rootProject.file("config/ktlint/baseline.xml"))
        ignoreFailures.set(false)
        enableExperimentalRules.set(true)
        filter {
            exclude("**/generated/**")
            include("**/kotlin/**")
        }
    }
}
