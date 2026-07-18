import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

val publicationGroup = "io.github.shusek"
val publicationVersion = providers.gradleProperty("publicationVersion").orElse("dev")

kotlin {
    jvmToolchain(25)

    android {
        namespace = "io.github.kdroidfilter.composemediaplayer.consumer.mpv"
        compileSdk = 37
        minSdk = 28
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("$publicationGroup:composemediaplayer-mpv:${publicationVersion.get()}")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}
