import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(17)

    target {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    dependencies {
        implementation(project(":sample:composeApp"))
        implementation(project(":mediaplayer"))
        implementation(libs.androidx.activityCompose)
        implementation(libs.androidx.core)
        implementation(libs.filekit.dialogs.compose)
        debugImplementation(libs.compose.ui.tooling)
    }
}

android {
    namespace = "sample.app"
    compileSdk = 37

    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk = 37

        applicationId = "sample.app.androidApp"
        versionCode = 1
        versionName = "1.0.0"
    }
}
