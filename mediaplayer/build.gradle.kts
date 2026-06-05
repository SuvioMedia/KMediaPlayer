@file:OptIn(ExperimentalWasmDsl::class)

import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.vannitktech.maven.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlinCocoapods)
    alias(libs.plugins.detekt)
}

detekt {
    config.setFrom(files(rootProject.file("config/detekt/detekt.yml")))
    // KMP detekt tasks derive baseline-<sourceSet>.xml from this base path.
    baseline = rootProject.file("config/detekt/baseline.xml")
    buildUponDefaultConfig = true
}

tasks.named("detekt") {
    dependsOn(
        "detektCommonMainSourceSet",
        "detektAndroidMainSourceSet",
        "detektJvmMainSourceSet",
        "detektIosMainSourceSet",
        "detektWasmJsMainSourceSet",
    )
}

val ref = System.getenv("GITHUB_REF") ?: ""
val isJitPack = System.getenv("JITPACK") == "true"
val tagVersion =
    if (ref.startsWith("refs/tags/")) {
        val tag = ref.removePrefix("refs/tags/")
        if (tag.startsWith("v")) tag.substring(1) else tag
    } else {
        null
    }
val projectVersion =
    providers.gradleProperty("publicationVersion").orNull
        ?: System.getenv("VERSION")
        ?: tagVersion
        ?: "dev"
val projectGroup =
    providers.gradleProperty("publicationGroup").orNull
        ?: if (isJitPack) {
            listOfNotNull(System.getenv("GROUP"), System.getenv("ARTIFACT")).joinToString(".")
        } else {
            "io.github.kdroidfilter"
        }
val githubPagesMavenRepository = providers.gradleProperty("githubPagesMavenRepository").orNull

group = projectGroup

kotlin {
    jvmToolchain(17)
    android {
        namespace = "io.github.kdroidfilter.composemediaplayer"
        compileSdk = 37
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()

        androidResources.enable = true

        withHostTest {
            isIncludeAndroidResources = true
        }

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.compilations.getByName("main") {
            // The default file path is src/nativeInterop/cinterop/<interop-name>.def
            val nskeyvalueobserving by cinterops.creating
        }
    }

    cocoapods {
        version = if (projectVersion == "dev") "0.0.1-dev" else projectVersion
        summary = "A multiplatform video player library for Compose applications"
        homepage = "https://github.com/Shusek/KMediaPlayer"
        name = "ComposeMediaPlayer"

        framework {
            baseName = "ComposeMediaPlayer"
            isStatic = false
            @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
            transitiveExport = false
        }

        // Maps custom Xcode configuration to NativeBuildType
        xcodeConfigurationToNativeBuildType["CUSTOM_DEBUG"] = NativeBuildType.DEBUG
        xcodeConfigurationToNativeBuildType["CUSTOM_RELEASE"] = NativeBuildType.RELEASE
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.kotlinx.coroutines.core)
            api(libs.filekit.core)
            implementation(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.androidcontextprovider)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.database)
            implementation(libs.androidx.media3.ui)
            implementation(libs.androidx.activityCompose)
            implementation(libs.androidx.core)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        named("androidHostTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        jvmMain.dependencies {
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.swing)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("test-junit"))
            implementation(libs.kotlinx.coroutines.test)
        }

        if (Os.isFamily(Os.FAMILY_MAC)) {
            iosMain.dependencies {
            }

            iosTest.dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
            implementation(libs.compose.ui)
            implementation(npm("jassub", "2.5.1"))
        }

        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }

    // https://kotlinlang.org/docs/native-objc-interop.html#export-of-kdoc-comments-to-generated-objective-c-headers
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        compilations["main"].compileTaskProvider.configure {
            compilerOptions {
                freeCompilerArgs.add("-Xexport-kdoc")
            }
        }
    }
}

val nativeResourceDir = layout.projectDirectory.dir("src/jvmMain/resources/composemediaplayer/native")

val buildNativeMacOs by tasks.registering(Exec::class) {
    description = "Compiles the Swift native library into macOS dylibs (arm64 + x64)"
    group = "build"
    enabled = Os.isFamily(Os.FAMILY_MAC)

    val nativeDir = layout.projectDirectory.dir("src/jvmMain/native/macos")
    inputs.dir(nativeDir)
    outputs.dir(nativeResourceDir)
    workingDir(nativeDir)
    commandLine("bash", "build.sh")
}

val buildNativeWindows by tasks.registering(Exec::class) {
    description = "Compiles the C++ native library into Windows DLLs (x64 + ARM64)"
    group = "build"
    val hasPrebuilt =
        nativeResourceDir
            .dir("win32-x86-64")
            .file("NativeVideoPlayer.dll")
            .asFile
            .exists()
    enabled = Os.isFamily(Os.FAMILY_WINDOWS) && !hasPrebuilt

    val nativeDir = layout.projectDirectory.dir("src/jvmMain/native/windows")
    inputs.dir(nativeDir)
    outputs.dir(nativeResourceDir)
    workingDir(nativeDir)
    commandLine("cmd", "/c", nativeDir.file("build.bat").asFile.absolutePath)
}

val buildNativeLinux by tasks.registering(Exec::class) {
    description = "Compiles the C native library into Linux .so (GStreamer + JNI)"
    group = "build"
    val hasPrebuilt =
        nativeResourceDir
            .dir("linux-x86-64")
            .file("libNativeVideoPlayer.so")
            .asFile
            .exists()
    enabled = Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC) && !hasPrebuilt

    val nativeDir = layout.projectDirectory.dir("src/jvmMain/native/linux")
    inputs.dir(nativeDir)
    outputs.dir(nativeResourceDir)
    workingDir(nativeDir)
    commandLine("bash", "build.sh")
}

tasks.named("jvmProcessResources") {
    dependsOn(buildNativeMacOs, buildNativeWindows, buildNativeLinux)
}

publishing {
    repositories {
        githubPagesMavenRepository?.let { repositoryPath ->
            maven {
                name = "githubPages"
                url = uri(repositoryPath)
            }
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = projectGroup,
        artifactId = "composemediaplayer",
        version = projectVersion,
    )

    pom {
        name.set("Compose Media Player")
        description.set("A multiplatform video player library for Compose applications.")
        inceptionYear.set("2025")
        url.set("https://github.com/Shusek/KMediaPlayer")

        developers {
            developer {
                id.set("Shusek")
                name.set("Shusek")
            }
        }

        licenses {
            license {
                name.set("Internal Use Notice and Limited License")
                url.set("https://github.com/Shusek/KMediaPlayer/blob/master/LICENSE")
                distribution.set("repo")
            }
        }

        scm {
            connection.set("scm:git:https://github.com/Shusek/KMediaPlayer.git")
            developerConnection.set("scm:git:ssh://git@github.com/Shusek/KMediaPlayer.git")
            url.set("https://github.com/Shusek/KMediaPlayer")
        }
    }

    publishToMavenCentral()

    // Only sign publications in CI environments to avoid requiring local GPG signing setup.
    if (System.getenv("CI") != null && githubPagesMavenRepository == null) {
        signAllPublications()
    }
}
