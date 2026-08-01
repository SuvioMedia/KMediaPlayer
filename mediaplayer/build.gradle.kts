@file:OptIn(ExperimentalWasmDsl::class)

import dev.detekt.gradle.Detekt
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import java.io.DataInputStream
import java.util.zip.ZipFile
import javax.inject.Inject

@CacheableTask
abstract class UnpackKMediaWasmRuntimeAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archives: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun unpack() {
        fileSystemOperations.sync {
            from(archives.files.map(archiveOperations::zipTree)) {
                include("kmedia-wasm-runtime/**")
                into("files")
            }
            from(archives.files.map(archiveOperations::zipTree)) {
                include("META-INF/**")
                into("files/kmedia-wasm-runtime")
            }
            into(outputDirectory)
        }
    }
}

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

tasks.matching { it.name == "checkKotlinAbi" }.configureEach {
    mustRunAfter(tasks.matching { it.name == "updateKotlinAbi" })
}

val ref = System.getenv("GITHUB_REF") ?: ""
val tagVersion =
    if (ref.startsWith("refs/tags/")) {
        val tag = ref.removePrefix("refs/tags/")
        if (tag.startsWith("v")) tag.substring(1) else tag
    } else {
        null
    }
val projectVersion =
    providers.gradleProperty("publicationVersion").orNull
        ?: "dev"
val projectGroup = "io.github.shusek"
val releaseStagingMavenRepository = providers.gradleProperty("releaseStagingMavenRepository").orNull
val generatedAndroidColorResources = layout.buildDirectory.dir("generated/androidColor/resources")
val generatedAndroidColorJniLibraries = layout.buildDirectory.dir("generated/androidColor/jniLibs")
val releaseSigningEnabled =
    providers
        .gradleProperty("releaseSigningEnabled")
        .map(String::toBoolean)
        .getOrElse(false)
val wasmBrowserTestBrowser =
    providers
        .gradleProperty("composeMediaPlayer.wasmTestBrowser")
        .orElse("chrome")
        .map(String::lowercase)
        .get()
val kmediaWasmRuntimeAssets =
    configurations.create("kmediaWasmRuntimeAssets") {
        isCanBeConsumed = false
        isCanBeResolved = true
        description = "Pinned KMedia Wasm engine runtime files."
    }
dependencies.add(
    kmediaWasmRuntimeAssets.name,
    "io.github.shusek:kmedia-wasm-engine-runtime-assets:${libs.versions.kmediaWasmEngine.get()}@zip",
)
val generatedKMediaWasmRuntimeResources = layout.buildDirectory.dir("generated/kmediaWasmRuntimeResources")
val unpackKMediaWasmRuntimeAssets =
    tasks.register<UnpackKMediaWasmRuntimeAssets>("unpackKMediaWasmRuntimeAssets") {
        archives.from(kmediaWasmRuntimeAssets)
        outputDirectory.set(generatedKMediaWasmRuntimeResources)
    }

compose.resources {
    customDirectory(
        sourceSetName = "wasmJsMain",
        directoryProvider =
            unpackKMediaWasmRuntimeAssets.flatMap(UnpackKMediaWasmRuntimeAssets::outputDirectory),
    )
}

require(wasmBrowserTestBrowser in setOf("chrome", "firefox", "webkit")) {
    "composeMediaPlayer.wasmTestBrowser must be one of: chrome, firefox, webkit."
}
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

        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        browser {
            testTask {
                useKarma {
                    when (wasmBrowserTestBrowser) {
                        "chrome" -> useChromeHeadless()
                        "firefox" -> useFirefoxHeadless()
                        // The config fragment replaces the legacy Safari launcher with headless WebKit.
                        "webkit" -> useSafari()
                    }
                }
            }
        }
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
        homepage = "https://github.com/SuvioMedia/KMediaPlayer"
        name = "ComposeMediaPlayer"
        ios.deploymentTarget = "16.2"

        framework {
            baseName = "ComposeMediaPlayer"
            isStatic = false
            @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
            transitiveExport = false
            export(project(":mediaplayer-core"))
            export(project(":mediaplayer-extension-api"))
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
            api(project(":mediaplayer-extension-api"))
            implementation(libs.compose.foundation)
            implementation(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain {
            // AGP derives jniLibs as a sibling of each Android resource directory.
            resources.srcDir(generatedAndroidColorResources)
            dependencies {
                implementation(libs.androidcontextprovider)
                implementation(libs.kotlinx.coroutines.android)
                api(libs.androidx.media3.exoplayer)
                implementation(libs.androidx.media3.exoplayer.hls)
                implementation(libs.androidx.media3.effect)
                implementation(libs.androidx.media3.datasource)
                implementation(libs.androidx.media3.database)
                implementation(libs.androidx.media3.ui)
                implementation(libs.androidx.activityCompose)
                implementation(libs.androidx.core)
                implementation(libs.androidx.lifecycle.runtime.ktx)
            }
        }

        named("androidHostTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.robolectric)
            }
        }

        named("androidDeviceTest") {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.androidx.test.runner)
            }
        }

        jvmMain {
            resources.exclude("composemediaplayer/native/darwin-x86-64/**")
            dependencies {
                implementation(libs.compose.ui)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }

        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
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

        wasmJsMain {
            dependencies {
                implementation(libs.kotlinx.browser)
                implementation(libs.compose.ui)
                implementation(libs.kmedia.wasm.engine)
            }
        }

        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(npm("karma-webkit-launcher", "2.6.0"))
            implementation(npm("playwright-webkit", "1.62.1"))
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

val androidSdkDirectory =
    providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        ?: providers.environmentVariable("ANDROID_HOME").orNull
        ?: file(
            when {
                Os.isFamily(Os.FAMILY_MAC) -> "${System.getProperty("user.home")}/Library/Android/sdk"
                Os.isFamily(Os.FAMILY_WINDOWS) -> "${System.getenv("LOCALAPPDATA")}/Android/Sdk"
                else -> "${System.getProperty("user.home")}/Android/Sdk"
            },
        ).absolutePath
val androidNdkDirectory =
    providers.environmentVariable("ANDROID_NDK_HOME").orNull
        ?: providers.environmentVariable("ANDROID_NDK_ROOT").orNull
        ?: file("$androidSdkDirectory/ndk/29.0.14206865").absolutePath
val androidNdkHostTag =
    when {
        Os.isFamily(Os.FAMILY_MAC) -> "darwin-x86_64"
        Os.isFamily(Os.FAMILY_WINDOWS) -> "windows-x86_64"
        else -> "linux-x86_64"
    }
val androidClangSuffix = if (Os.isFamily(Os.FAMILY_WINDOWS)) ".cmd" else ""
val androidColorNativeSource =
    layout.projectDirectory.file("src/androidMain/native/color_surface/AndroidSurfaceDataSpace.c")
val cleanUnsupportedAndroidNativeOutputs =
    tasks.register<Delete>("cleanUnsupportedAndroidNativeOutputs") {
        delete(
            generatedAndroidColorJniLibraries.map { it.dir("x86") },
            generatedAndroidColorJniLibraries.map { it.dir("x86_64") },
        )
    }

fun registerAndroidColorBridgeBuild(
    taskName: String,
    abi: String,
    clangPrefix: String,
) = tasks.register<Exec>(taskName) {
    description = "Builds the Android native surface dataspace bridge for $abi."
    group = "build"
    enabled = !skipNativeBuild
    val outputLibrary =
        generatedAndroidColorJniLibraries
            .map { it.dir(abi).file("libcomposemediaplayer_android_color.so") }
    inputs.file(androidColorNativeSource)
    outputs.file(outputLibrary)
    dependsOn(cleanUnsupportedAndroidNativeOutputs)
    doFirst {
        outputLibrary
            .get()
            .asFile.parentFile
            .mkdirs()
    }
    commandLine(
        "$androidNdkDirectory/toolchains/llvm/prebuilt/$androidNdkHostTag/bin/" +
            "$clangPrefix$androidClangSuffix",
        "-shared",
        "-fPIC",
        "-O2",
        "-std=c11",
        "-Wall",
        "-Wextra",
        "-Werror",
        "-Wl,--no-undefined",
        androidColorNativeSource.asFile.absolutePath,
        "-o",
        outputLibrary.get().asFile.absolutePath,
        "-landroid",
        "-ldl",
    )
}

val androidColorBridgeBuilds =
    listOf(
        registerAndroidColorBridgeBuild(
            taskName = "buildAndroidArmV7ColorBridge",
            abi = "armeabi-v7a",
            clangPrefix = "armv7a-linux-androideabi23-clang",
        ),
        registerAndroidColorBridgeBuild(
            taskName = "buildAndroidArm64ColorBridge",
            abi = "arm64-v8a",
            clangPrefix = "aarch64-linux-android23-clang",
        ),
    )

tasks.matching { it.name == "mergeAndroidMainJniLibFolders" }.configureEach {
    dependsOn(androidColorBridgeBuilds)
}

val verifyAndroidArmNativeMatrix =
    tasks.register("verifyAndroidArmNativeMatrix") {
        group = "verification"
        description = "Verifies that the Android AAR publishes native code for ARM ABIs only."
        dependsOn(tasks.named("bundleAndroidMainAar"))

        val aar = layout.buildDirectory.file("outputs/aar/${project.name}.aar")
        inputs.file(aar)

        doLast {
            val expectedAbis = setOf("arm64-v8a", "armeabi-v7a")
            ZipFile(aar.get().asFile).use { archive ->
                val nativeEntries =
                    archive
                        .entries()
                        .asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                entry.name.startsWith("jni/") &&
                                entry.name.endsWith("/libcomposemediaplayer_android_color.so")
                        }.map { entry -> entry.name.removePrefix("jni/").substringBefore('/') }
                        .toSet()
                check(nativeEntries == expectedAbis) {
                    "Unexpected Android native ABI matrix: expected=$expectedAbis, actual=$nativeEntries"
                }
                val intelEntries =
                    archive
                        .entries()
                        .asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                (entry.name.startsWith("jni/x86/") || entry.name.startsWith("jni/x86_64/"))
                        }.map { it.name }
                        .toList()
                check(intelEntries.isEmpty()) {
                    "The Android AAR contains unsupported Intel native payloads: $intelEntries"
                }
            }
        }
    }

val buildNativeMacOs =
    tasks.register<Exec>("buildNativeMacOs") {
        description = "Compiles the Swift native library into a macOS arm64 dylib."
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
    providers.gradleProperty("kmediaPlayerHdrTestMedia").orNull?.let { mediaPath ->
        systemProperty("composemediaplayer.test.hdrMedia", mediaPath)
    }
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

val verifyJvmMacArmNativeMatrix =
    tasks.register("verifyJvmMacArmNativeMatrix") {
        group = "verification"
        description = "Verifies that the JVM JAR publishes the macOS bridge for arm64 only."

        val jvmJar = tasks.named<Jar>("jvmJar")
        dependsOn(jvmJar)
        val archiveFile = jvmJar.flatMap { it.archiveFile }
        inputs.file(archiveFile)

        doLast {
            ZipFile(inputs.files.singleFile).use { archive ->
                val armPath = "composemediaplayer/native/darwin-arm64/libNativeVideoPlayer.dylib"
                check(archive.getEntry(armPath) != null) {
                    "The JVM JAR is missing the macOS arm64 bridge: $armPath"
                }
                val intelEntries =
                    archive
                        .entries()
                        .asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                entry.name.startsWith("composemediaplayer/native/darwin-x86-64/")
                        }.map { it.name }
                        .toList()
                check(intelEntries.isEmpty()) {
                    "The JVM JAR contains unsupported Intel macOS payloads: $intelEntries"
                }
            }
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
        releaseStagingMavenRepository?.let { repositoryPath ->
            maven {
                name = "releaseStaging"
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
        url.set("https://github.com/SuvioMedia/KMediaPlayer")

        developers {
            developer {
                id.set("Shusek")
                name.set("Shusek")
            }
        }

        licenses {
            license {
                name.set("Internal Use Notice and Limited License")
                url.set("https://github.com/SuvioMedia/KMediaPlayer/blob/master/LICENSE")
                distribution.set("repo")
            }
        }

        scm {
            connection.set("scm:git:https://github.com/SuvioMedia/KMediaPlayer.git")
            developerConnection.set("scm:git:ssh://git@github.com/SuvioMedia/KMediaPlayer.git")
            url.set("https://github.com/SuvioMedia/KMediaPlayer")
        }
    }

    publishToMavenCentral()

    // Local/consumer publications stay unsigned. Release CI provides the in-memory key explicitly.
    if (releaseSigningEnabled) {
        signAllPublications()
    }
}

val hdrPipelineMajorVersion = 2

val validateReleaseVersion =
    tasks.register("validateReleaseVersion") {
        group = "verification"
        description = "Rejects mutable or non-SemVer versions before publishing a remote release."
        inputs.property("releaseVersion", projectVersion)
        inputs.property("releaseGroup", projectGroup)
        inputs.property("hdrPipelineMajorVersion", hdrPipelineMajorVersion)

        doLast {
            val releaseVersion = inputs.properties.getValue("releaseVersion") as String
            val releaseGroup = inputs.properties.getValue("releaseGroup") as String
            val hdrPipelineMajorVersion = inputs.properties.getValue("hdrPipelineMajorVersion") as Int
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
            val releaseMajorVersion = releaseVersion.substringBefore('.').toInt()
            check(releaseMajorVersion >= hdrPipelineMajorVersion) {
                "This branch contains the breaking KMediaPlayer 2.0 API and cannot be published as $releaseVersion."
            }
        }
    }

tasks.configureEach {
    val publishesRemoteRelease =
        name.contains("MavenCentral", ignoreCase = true) ||
            name.contains("ReleaseStaging", ignoreCase = true) ||
            name == "publishAndReleaseToMavenCentral"
    if (publishesRemoteRelease) {
        dependsOn(validateReleaseVersion)
    }
}
