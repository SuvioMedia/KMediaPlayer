@file:OptIn(ExperimentalWasmDsl::class)

import dev.detekt.gradle.Detekt
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import java.io.DataInputStream
import java.util.zip.ZipFile

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
    dependsOn(tasks.withType<Detekt>().matching { it.name.endsWith("SourceSet") })
}

val projectVersion =
    providers.gradleProperty("publicationVersion").orNull
        ?: "dev"
val projectGroup = "io.github.shusek"
val githubPagesMavenRepository = providers.gradleProperty("githubPagesMavenRepository").orNull
val releaseSigningEnabled =
    providers
        .gradleProperty("releaseSigningEnabled")
        .map(String::toBoolean)
        .getOrElse(false)

group = projectGroup
version = projectVersion

kotlin {
    jvmToolchain(25)

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        // Unsupported-target inference can misclassify Wasm-only declarations on non-macOS hosts.
        keepLocallyUnsupportedTargets.set(false)
    }

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
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

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
            cinterops.create("nskeyvalueobserving")
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
            export(project(":mediaplayer-core"))
        }

        // Maps custom Xcode configuration to NativeBuildType
        xcodeConfigurationToNativeBuildType["CUSTOM_DEBUG"] = NativeBuildType.DEBUG
        xcodeConfigurationToNativeBuildType["CUSTOM_RELEASE"] = NativeBuildType.RELEASE
    }

    sourceSets.configureEach {
        languageSettings.optIn(
            "io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi",
        )
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":mediaplayer-core"))
            implementation(libs.compose.foundation)
            implementation(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.androidcontextprovider)
            implementation(libs.kotlinx.coroutines.android)
            api(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.exoplayer.hls)
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
                implementation(libs.robolectric)
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
            implementation(npm("hls.js", "1.6.16"))
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
val skipNativeBuild =
    providers
        .gradleProperty("composeMediaPlayer.skipNativeBuild")
        .map { it.equals("true", ignoreCase = true) }
        .getOrElse(false)

val buildNativeMacOs =
    tasks.register<Exec>("buildNativeMacOs") {
        description = "Compiles the Swift native library into macOS dylibs (arm64 + x64)"
        group = "build"
        enabled = !skipNativeBuild && Os.isFamily(Os.FAMILY_MAC)

        val nativeDir = layout.projectDirectory.dir("src/jvmMain/native/macos")
        inputs.dir(nativeDir)
        outputs.dir(nativeResourceDir)
        workingDir(nativeDir)
        commandLine("bash", "build.sh")
    }

val buildNativeWindows =
    tasks.register<Exec>("buildNativeWindows") {
        description = "Compiles the C++ native library into Windows DLLs (x64 + ARM64)"
        group = "build"
        enabled = !skipNativeBuild && Os.isFamily(Os.FAMILY_WINDOWS)

        val nativeDir = layout.projectDirectory.dir("src/jvmMain/native/windows")
        inputs.dir(nativeDir)
        outputs.dir(nativeResourceDir)
        workingDir(nativeDir)
        commandLine("cmd", "/c", nativeDir.file("build.bat").asFile.absolutePath)
    }

val buildNativeLinux =
    tasks.register<Exec>("buildNativeLinux") {
        description = "Compiles the C native library into Linux .so (GStreamer + JNI)"
        group = "build"
        enabled = !skipNativeBuild && Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC)

        val nativeDir = layout.projectDirectory.dir("src/jvmMain/native/linux")
        inputs.dir(nativeDir)
        outputs.dir(nativeResourceDir)
        workingDir(nativeDir)
        commandLine("bash", "build.sh")
    }

tasks.named("jvmProcessResources") {
    dependsOn(buildNativeMacOs, buildNativeWindows, buildNativeLinux)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val java25ClassFileVersion = 69

val verifyJvm25Bytecode =
    tasks.register("verifyJvm25Bytecode") {
        group = "verification"
        description = "Verifies that every class in the published JVM JAR targets Java 25 (classfile 69)."

        val jvmJar = tasks.named<Jar>("jvmJar")
        dependsOn(jvmJar)
        val archiveFile = jvmJar.flatMap { it.archiveFile }
        inputs.file(archiveFile)
        inputs.property("expectedClassFileVersion", java25ClassFileVersion)

        doLast {
            val expectedClassFileVersion = inputs.properties.getValue("expectedClassFileVersion") as Int
            var verifiedClasses = 0
            ZipFile(inputs.files.singleFile).use { archive ->
                archive
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { entry ->
                        DataInputStream(archive.getInputStream(entry)).use { classFile ->
                            check(classFile.readInt() == 0xCAFEBABE.toInt()) {
                                "Invalid classfile header in ${entry.name}"
                            }
                            classFile.readUnsignedShort() // minor version
                            val majorVersion = classFile.readUnsignedShort()
                            check(majorVersion == expectedClassFileVersion) {
                                "${entry.name} targets classfile $majorVersion; expected Java 25 " +
                                    "(classfile $expectedClassFileVersion)."
                            }
                            verifiedClasses++
                        }
                    }
            }
            check(verifiedClasses > 0) { "The JVM publication JAR contains no class files." }
            logger.lifecycle("Verified Java 25 bytecode for $verifiedClasses JVM classes.")
        }
    }

tasks.named("check") {
    dependsOn(verifyJvm25Bytecode)
}

val consumerSmokeRepository = rootProject.layout.buildDirectory.dir("consumer-repository")

publishing {
    repositories {
        maven {
            name = "consumerSmoke"
            url = uri(consumerSmokeRepository)
        }
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

    // Local/consumer publications stay unsigned. Release CI provides the in-memory key explicitly.
    if (releaseSigningEnabled) {
        signAllPublications()
    }
}

val validateReleaseVersion =
    tasks.register("validateReleaseVersion") {
        group = "verification"
        description = "Rejects mutable or non-SemVer versions before publishing a remote release."
        inputs.property("releaseVersion", projectVersion)
        inputs.property("releaseGroup", projectGroup)

        doLast {
            val releaseVersion = inputs.properties.getValue("releaseVersion") as String
            val releaseGroup = inputs.properties.getValue("releaseGroup") as String
            val semverRegex =
                Regex(
                    "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                        "(?:-(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)" +
                        "(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*)?" +
                        "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
                )
            check(semverRegex.matches(releaseVersion)) {
                "Release version '$releaseVersion' is not a valid immutable SemVer version. " +
                    "Use -PpublicationVersion=<major.minor.patch>."
            }
            check(releaseGroup == "io.github.shusek") {
                "Release group must remain 'io.github.shusek', but was '$releaseGroup'."
            }
        }
    }

tasks.configureEach {
    val publishesRemoteRelease =
        name.contains("MavenCentral", ignoreCase = true) ||
            name.contains("GithubPages", ignoreCase = true) ||
            name == "publishAndReleaseToMavenCentral"
    if (publishesRemoteRelease) {
        dependsOn(validateReleaseVersion)
    }
}
