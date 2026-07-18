import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
}

val publicationGroup = "io.github.shusek"
val publicationVersion = providers.gradleProperty("publicationVersion").orElse("dev")

kotlin {
    jvmToolchain(25)

    android {
        namespace = "io.github.kdroidfilter.composemediaplayer.consumer.extensions"
        compileSdk = 37
        minSdk = 23
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
            implementation("$publicationGroup:composemediaplayer-ass:${publicationVersion.get()}")
            implementation("$publicationGroup:composemediaplayer-dolbyvision:${publicationVersion.get()}")
        }
        androidMain.dependencies {
            implementation("$publicationGroup:composemediaplayer-kmediabridge:${publicationVersion.get()}")
        }
        jvmMain.dependencies {
            implementation("$publicationGroup:composemediaplayer-kmediabridge:${publicationVersion.get()}")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}
