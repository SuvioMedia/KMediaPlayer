@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)

import io.github.kdroidfilter.nucleus.desktop.application.dsl.CompressionLevel
import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
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

val desktopSampleMainClass = "sample.app.MainKt"

// Keep the desktop sample's documented -Dsample.app.* launch controls available through Gradle's
// forked run task. Reading only this allow-list avoids forwarding unrelated JVM properties.
val desktopSampleSystemProperties =
    listOf(
        "composemediaplayer.fallbackBackend",
        "composemediaplayer.hlsFallbackBackend",
        "sample.app.videoUrl",
        "sample.app.demoSubtitle",
        "sample.app.windowX",
        "sample.app.windowY",
        "sample.app.windowWidth",
        "sample.app.windowHeight",
        "sample.app.initialFullscreen",
        "sample.app.loop",
        "sample.app.dynamicRangePolicy",
        "sample.app.dolbyVisionPolicy",
        "sample.app.desktopVideoBackend",
        "sample.app.playbackBackend",
        "sample.app.sourceAdapter",
        "sample.app.kMediaBridgeRuntimeDirectory",
        "sample.app.mpvLibraryPath",
        "sample.app.projectionType",
        "sample.app.colorSelfTestSeconds",
        "sample.app.colorSelfTestStartTimeoutSeconds",
        "sample.app.colorSelfTestResultFile",
        "sample.app.colorSelfTestExpectedSource",
        "sample.app.colorSelfTestExpectedOutput",
        "sample.app.colorSelfTestRequireAudioSync",
    )

tasks.withType<JavaExec>().configureEach {
    // libdovi is loaded through JNA by the desktop Dolby Vision source extension.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    desktopSampleSystemProperties.forEach { propertyName ->
        providers.systemProperty(propertyName).orNull?.let { propertyValue ->
            systemProperty(propertyName, propertyValue)
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    providers.gradleProperty("kmediaPlayerHdrTestMedia").orNull?.let { mediaPath ->
        systemProperty("composemediaplayer.test.hdrMedia", mediaPath)
    }
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
        mainRun {
            mainClass.set(desktopSampleMainClass)
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
            implementation(project(":mediaplayer-ass"))
            implementation(project(":mediaplayer-dolbyvision"))
            implementation(project(":mediaplayer-kmediabridge"))
            implementation(project(":mediaplayer-mpv"))
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(kotlin("test"))
            implementation(kotlin("test-junit"))
        }
        iosMain.dependencies {
            implementation(project(":mediaplayer-ass"))
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
            implementation(project(":mediaplayer-ass"))
        }
    }
}

nucleus.application {
    mainClass = desktopSampleMainClass

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
