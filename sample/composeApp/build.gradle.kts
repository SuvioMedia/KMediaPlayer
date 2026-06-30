@file:OptIn(ExperimentalWasmDsl::class)

import io.github.kdroidfilter.nucleus.desktop.application.dsl.CompressionLevel
import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.nucleus)
}

kotlin {
    jvmToolchain(25)

    android {
        namespace = "sample.app.shared"
        compileSdk = 37
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }
    wasmJs {
        outputModuleName.set("composeApp")
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    // Serve sources to debug inside browser
                    static(rootDirPath)
                    static(projectDirPath)
                }
            }
        }
        binaries.executable()
    }
    if (Os.isFamily(Os.FAMILY_MAC)) {
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
        ).forEach {
            it.binaries.framework {
                baseName = "ComposeApp"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.compose.material.icons.extended)
            implementation(project(":mediaplayer"))
            implementation(libs.filekit.dialogs.compose)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.nucleus.graalvm.runtime)
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}

nucleus.application {
    mainClass = "sample.app.MainKt"

    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb)
        packageName = "Compose Media Player"
        description = "A Kotlin Multiplatform media player built with Compose"
        vendor = "KDroidFilter"
        cleanupNativeLibs = true
        packageVersion = "1.0.0"
        compressionLevel = CompressionLevel.Maximum
        windows {
            shortcut = true
        }
    }

    graalvm {
        isEnabled = true
        imageName = "compose-media-player"
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.BELLSOFT
        buildArgs.addAll(
            "-H:+AddAllCharsets",
            "-Djava.awt.headless=false",
            "--enable-url-protocols=http,https"
        )
    }
}
