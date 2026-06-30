import com.android.build.api.variant.HostTestBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(25)

    target {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.androidTestEnabled = false
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
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

android {
    namespace = "sample.app"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

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
