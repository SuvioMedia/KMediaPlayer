@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import dev.detekt.gradle.Detekt
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.vannitktech.maven.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
}

detekt {
    config.setFrom(files(rootProject.file("config/detekt/detekt.yml")))
    baseline = rootProject.file("config/detekt/baseline.xml")
    buildUponDefaultConfig = true
}

tasks.named("detekt") {
    dependsOn(tasks.withType<Detekt>().matching { it.name.endsWith("SourceSet") })
}

tasks.matching { it.name == "checkKotlinAbi" }.configureEach {
    mustRunAfter(tasks.matching { it.name == "updateKotlinAbi" })
}

val projectVersion =
    providers.gradleProperty("publicationVersion").orNull
        ?: System.getenv("VERSION")
        ?: "dev"

group = "io.github.shusek"
version = projectVersion
val githubPagesMavenRepository = providers.gradleProperty("githubPagesMavenRepository").orNull

val generatedJvmNativeResources = layout.buildDirectory.dir("generated/libdoviJvmResources")
val generatedWasmResources = layout.buildDirectory.dir("generated/libdoviWasmResources")
val generatedAndroidResources = layout.buildDirectory.dir("generated/libdoviAndroid/resources")
val generatedAndroidArmV7JniLibraries = layout.buildDirectory.dir("generated/libdoviAndroid/jniLibs/armeabi-v7a")
val generatedAndroidArm64JniLibraries = layout.buildDirectory.dir("generated/libdoviAndroid/jniLibs/arm64-v8a")
val libDoviNativeDirectory = layout.projectDirectory.dir("native/libdovi")
val skipNativeBuild =
    providers
        .gradleProperty("composeMediaPlayer.skipLibDoviNativeBuild")
        .orElse(providers.gradleProperty("composeMediaPlayer.skipNativeBuild"))
        .orElse(providers.environmentVariable("COMPOSE_MEDIA_PLAYER_SKIP_NATIVE_BUILD"))
        .map { it.equals("true", ignoreCase = true) }
        .getOrElse(false)

val buildIosArm64LibDovi =
    tasks.register<Exec>("buildIosArm64LibDovi") {
        description = "Builds the pinned static libdovi shim for iOS arm64."
        group = "build"
        enabled = !skipNativeBuild && Os.isFamily(Os.FAMILY_MAC)
        workingDir(libDoviNativeDirectory)
        environment("IPHONEOS_DEPLOYMENT_TARGET", "16.2")
        commandLine("cargo", "build", "--release", "--locked", "--target", "aarch64-apple-ios")
        inputs.file(libDoviNativeDirectory.file("Cargo.toml"))
        inputs.file(libDoviNativeDirectory.file("Cargo.lock"))
        inputs.dir(libDoviNativeDirectory.dir("src"))
        outputs.file(libDoviNativeDirectory.file("target/aarch64-apple-ios/release/libcomposemediaplayer_libdovi.a"))
    }

val buildIosSimulatorArm64LibDovi =
    tasks.register<Exec>("buildIosSimulatorArm64LibDovi") {
        description = "Builds the pinned static libdovi shim for the iOS arm64 simulator."
        group = "build"
        enabled = !skipNativeBuild && Os.isFamily(Os.FAMILY_MAC)
        workingDir(libDoviNativeDirectory)
        environment("IPHONEOS_DEPLOYMENT_TARGET", "16.2")
        commandLine("cargo", "build", "--release", "--locked", "--target", "aarch64-apple-ios-sim")
        inputs.file(libDoviNativeDirectory.file("Cargo.toml"))
        inputs.file(libDoviNativeDirectory.file("Cargo.lock"))
        inputs.dir(libDoviNativeDirectory.dir("src"))
        outputs.file(
            libDoviNativeDirectory.file(
                "target/aarch64-apple-ios-sim/release/libcomposemediaplayer_libdovi.a",
            ),
        )
    }

kotlin {
    jvmToolchain(25)

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        keepLocallyUnsupportedTargets.set(false)
    }

    android {
        namespace = "io.github.kdroidfilter.composemediaplayer.dolbyvision"
        compileSdk = 37
        minSdk = 23
        androidResources.enable = true
        withHostTest {}
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compilerOptions { jvmTarget.set(JvmTarget.JVM_25) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_25) }
    }
    wasmJs {
        browser()
    }
    listOf(
        iosArm64() to "aarch64-apple-ios",
        iosSimulatorArm64() to "aarch64-apple-ios-sim",
    ).forEach { (target, rustTarget) ->
        target.compilations.getByName("main") {
            cinterops.create("libdovi") {
                defFile(project.file("src/nativeInterop/cinterop/libdovi.def"))
                includeDirs.headerFilterOnly(libDoviNativeDirectory.dir("include").asFile)
                extraOpts(
                    "-libraryPath",
                    libDoviNativeDirectory.file("target/$rustTarget/release").asFile.absolutePath,
                )
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":mediaplayer-extension-api"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain {
            // AGP derives jniLibs as a sibling of each Android resource directory.
            resources.srcDir(generatedAndroidResources)
            dependencies {
                implementation(libs.androidcontextprovider)
                implementation(libs.androidx.core)
                implementation(libs.androidx.media3.datasource)
                implementation(libs.androidx.media3.exoplayer.hls)
            }
        }
        jvmMain {
            resources.srcDir(generatedJvmNativeResources)
            resources.exclude("composemediaplayer/dolbyvision/native/darwin-x86-64/**")
        }
        wasmJsMain {
            resources.srcDir(generatedWasmResources)
        }
        named("androidHostTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
            }
        }
        named("androidDeviceTest") {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.androidx.test.runner)
            }
        }
    }
}

val hostRustTarget =
    when {
        Os.isFamily(Os.FAMILY_MAC) && System.getProperty("os.arch") in setOf("aarch64", "arm64") ->
            "aarch64-apple-darwin"
        Os.isFamily(Os.FAMILY_MAC) -> null
        Os.isFamily(Os.FAMILY_WINDOWS) && System.getProperty("os.arch") in setOf("aarch64", "arm64") ->
            "aarch64-pc-windows-msvc"
        Os.isFamily(Os.FAMILY_WINDOWS) -> "x86_64-pc-windows-msvc"
        Os.isFamily(Os.FAMILY_UNIX) && System.getProperty("os.arch") in setOf("aarch64", "arm64") ->
            "aarch64-unknown-linux-gnu"
        Os.isFamily(Os.FAMILY_UNIX) -> "x86_64-unknown-linux-gnu"
        else -> null
    }
val hostResourceClassifier =
    when {
        Os.isFamily(Os.FAMILY_MAC) && System.getProperty("os.arch") in setOf("aarch64", "arm64") ->
            "darwin-aarch64"
        Os.isFamily(Os.FAMILY_MAC) -> null
        Os.isFamily(Os.FAMILY_WINDOWS) && System.getProperty("os.arch") in setOf("aarch64", "arm64") ->
            "windows-aarch64"
        Os.isFamily(Os.FAMILY_WINDOWS) -> "windows-x86-64"
        Os.isFamily(Os.FAMILY_UNIX) && System.getProperty("os.arch") in setOf("aarch64", "arm64") ->
            "linux-aarch64"
        Os.isFamily(Os.FAMILY_UNIX) -> "linux-x86-64"
        else -> null
    }
val generatedJvmHostNativeResources =
    layout.buildDirectory.dir(
        "generated/libdoviJvmResources/composemediaplayer/dolbyvision/native/" +
            (hostResourceClassifier ?: "unsupported"),
    )
val rustLibraryName =
    when {
        Os.isFamily(Os.FAMILY_MAC) -> "libcomposemediaplayer_libdovi.dylib"
        Os.isFamily(Os.FAMILY_WINDOWS) -> "composemediaplayer_libdovi.dll"
        else -> "libcomposemediaplayer_libdovi.so"
    }
val packagedLibraryName =
    when {
        Os.isFamily(Os.FAMILY_MAC) -> "libcomposemediaplayer_dovi.dylib"
        Os.isFamily(Os.FAMILY_WINDOWS) -> "composemediaplayer_dovi.dll"
        else -> "libcomposemediaplayer_dovi.so"
    }
val stagedHostJvmLibrary =
    layout.projectDirectory.file(
        "src/jvmMain/resources/composemediaplayer/dolbyvision/native/" +
            "${hostResourceClassifier ?: "unsupported"}/$packagedLibraryName",
    )
val shouldBuildHostLibDovi =
    !skipNativeBuild &&
        hostRustTarget != null &&
        hostResourceClassifier != null &&
        !stagedHostJvmLibrary.asFile.isFile

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
val cleanUnsupportedAndroidNativeOutputs =
    tasks.register<Delete>("cleanUnsupportedAndroidNativeOutputs") {
        delete(
            layout.buildDirectory.dir("generated/libdoviAndroid/jniLibs/x86"),
            layout.buildDirectory.dir("generated/libdoviAndroid/jniLibs/x86_64"),
        )
    }

fun registerAndroidLibDoviBuild(
    taskName: String,
    rustTarget: String,
    linkerEnvironmentName: String,
    clangPrefix: String,
) = tasks.register<Exec>(taskName) {
    description = "Builds the pinned libdovi shim for Android $rustTarget."
    group = "build"
    enabled = !skipNativeBuild
    dependsOn(cleanUnsupportedAndroidNativeOutputs)
    workingDir(libDoviNativeDirectory)
    commandLine("cargo", "build", "--release", "--locked", "--target", rustTarget)
    environment(
        linkerEnvironmentName,
        "$androidNdkDirectory/toolchains/llvm/prebuilt/$androidNdkHostTag/bin/$clangPrefix$androidClangSuffix",
    )
    inputs.file(libDoviNativeDirectory.file("Cargo.toml"))
    inputs.file(libDoviNativeDirectory.file("Cargo.lock"))
    inputs.dir(libDoviNativeDirectory.dir("src"))
    outputs.file(libDoviNativeDirectory.file("target/$rustTarget/release/libcomposemediaplayer_libdovi.so"))
}

val buildAndroidArm64LibDovi =
    registerAndroidLibDoviBuild(
        taskName = "buildAndroidArm64LibDovi",
        rustTarget = "aarch64-linux-android",
        linkerEnvironmentName = "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER",
        clangPrefix = "aarch64-linux-android23-clang",
    )
val buildAndroidArmV7LibDovi =
    registerAndroidLibDoviBuild(
        taskName = "buildAndroidArmV7LibDovi",
        rustTarget = "armv7-linux-androideabi",
        linkerEnvironmentName = "CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER",
        clangPrefix = "armv7a-linux-androideabi23-clang",
    )
val packageAndroidArm64LibDovi =
    tasks.register<Copy>("packageAndroidArm64LibDovi") {
        dependsOn(buildAndroidArm64LibDovi)
        from(libDoviNativeDirectory.file("target/aarch64-linux-android/release/libcomposemediaplayer_libdovi.so"))
        into(generatedAndroidArm64JniLibraries)
    }
val packageAndroidArmV7LibDovi =
    tasks.register<Copy>("packageAndroidArmV7LibDovi") {
        dependsOn(buildAndroidArmV7LibDovi)
        from(libDoviNativeDirectory.file("target/armv7-linux-androideabi/release/libcomposemediaplayer_libdovi.so"))
        into(generatedAndroidArmV7JniLibraries)
    }
val buildHostLibDovi =
    tasks.register<Exec>("buildHostLibDovi") {
        description = "Builds the pinned libdovi shim for the current JVM host."
        group = "build"
        enabled = shouldBuildHostLibDovi
        workingDir(layout.projectDirectory.dir("native/libdovi"))
        commandLine("cargo", "build", "--release", "--locked", "--target", hostRustTarget ?: "unsupported")
        inputs.file(layout.projectDirectory.file("native/libdovi/Cargo.toml"))
        inputs.file(layout.projectDirectory.file("native/libdovi/Cargo.lock"))
        inputs.dir(layout.projectDirectory.dir("native/libdovi/src"))
        outputs.file(
            layout.projectDirectory.file(
                "native/libdovi/target/${hostRustTarget ?: "unsupported"}/release/$rustLibraryName",
            ),
        )
    }

val packageHostLibDovi =
    tasks.register<Copy>("packageHostLibDovi") {
        description = "Packages the current-host libdovi shim as a JVM resource."
        group = "build"
        enabled = shouldBuildHostLibDovi
        dependsOn(buildHostLibDovi)
        from(
            layout.projectDirectory.file(
                "native/libdovi/target/${hostRustTarget ?: "unsupported"}/release/$rustLibraryName",
            ),
        )
        into(generatedJvmHostNativeResources)
        rename(rustLibraryName, packagedLibraryName)
    }

val buildWasmLibDovi =
    tasks.register<Exec>("buildWasmLibDovi") {
        description = "Builds the pinned libdovi shim for wasm32-unknown-unknown."
        group = "build"
        enabled = !skipNativeBuild
        workingDir(libDoviNativeDirectory)
        commandLine("cargo", "build", "--release", "--locked", "--target", "wasm32-unknown-unknown")
        inputs.file(libDoviNativeDirectory.file("Cargo.toml"))
        inputs.file(libDoviNativeDirectory.file("Cargo.lock"))
        inputs.dir(libDoviNativeDirectory.dir("src"))
        outputs.file(
            libDoviNativeDirectory.file(
                "target/wasm32-unknown-unknown/release/composemediaplayer_libdovi.wasm",
            ),
        )
    }

val packageWasmLibDovi =
    tasks.register<Copy>("packageWasmLibDovi") {
        description = "Packages the browser libdovi WebAssembly module."
        group = "build"
        enabled = !skipNativeBuild
        dependsOn(buildWasmLibDovi)
        from(
            libDoviNativeDirectory.file(
                "target/wasm32-unknown-unknown/release/composemediaplayer_libdovi.wasm",
            ),
        )
        into(generatedWasmResources)
    }

tasks.named("jvmProcessResources") {
    dependsOn(packageHostLibDovi)
}
tasks.named("wasmJsProcessResources") {
    dependsOn(packageWasmLibDovi)
}

tasks.matching { it.name == "mergeAndroidMainJniLibFolders" }.configureEach {
    dependsOn(packageAndroidArmV7LibDovi, packageAndroidArm64LibDovi)
}

val verifyAndroidArmNativeMatrix =
    tasks.register("verifyAndroidArmNativeMatrix") {
        group = "verification"
        description = "Verifies that the Android libdovi AAR publishes ARM ABIs only."
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
                                entry.name.endsWith("/libcomposemediaplayer_libdovi.so")
                        }.map { entry -> entry.name.removePrefix("jni/").substringBefore('/') }
                        .toSet()
                check(nativeEntries == expectedAbis) {
                    "Unexpected Android libdovi ABI matrix: expected=$expectedAbis, actual=$nativeEntries"
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
                    "The Android libdovi AAR contains unsupported Intel payloads: $intelEntries"
                }
            }
        }
    }

tasks.matching { it.name.startsWith("link") && it.name.endsWith("IosArm64") }.configureEach {
    dependsOn(buildIosArm64LibDovi)
}
tasks.matching { it.name.startsWith("link") && it.name.endsWith("IosSimulatorArm64") }.configureEach {
    dependsOn(buildIosSimulatorArm64LibDovi)
}
tasks.matching { it.name == "cinteropLibdoviIosArm64" }.configureEach {
    dependsOn(buildIosArm64LibDovi)
}
tasks.matching { it.name == "cinteropLibdoviIosSimulatorArm64" }.configureEach {
    dependsOn(buildIosSimulatorArm64LibDovi)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val verifyJvmMacArmNativeMatrix =
    tasks.register("verifyJvmMacArmNativeMatrix") {
        group = "verification"
        description = "Verifies that the libdovi JVM JAR publishes macOS arm64 only."

        val jvmJar = tasks.named<Jar>("jvmJar")
        dependsOn(jvmJar)
        val archiveFile = jvmJar.flatMap { it.archiveFile }
        inputs.file(archiveFile)

        doLast {
            ZipFile(inputs.files.singleFile).use { archive ->
                val armPath =
                    "composemediaplayer/dolbyvision/native/darwin-aarch64/" +
                        "libcomposemediaplayer_dovi.dylib"
                check(archive.getEntry(armPath) != null) {
                    "The libdovi JVM JAR is missing the macOS arm64 payload."
                }
                val intelEntries =
                    archive
                        .entries()
                        .asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                entry.name.startsWith(
                                    "composemediaplayer/dolbyvision/native/darwin-x86-64/",
                                )
                        }.map { it.name }
                        .toList()
                check(intelEntries.isEmpty()) {
                    "The libdovi JVM JAR contains unsupported Intel macOS payloads: $intelEntries"
                }
            }
        }
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
        groupId = "io.github.shusek",
        artifactId = "composemediaplayer-dolbyvision",
        version = projectVersion,
    )
    pom {
        name.set("Compose Media Player Dolby Vision")
        description.set("Optional bounded Dolby Vision Profile 7 to 8.1 conversion bridge for Compose Media Player.")
        inceptionYear.set("2026")
        url.set("https://github.com/Shusek/KMediaPlayer")
        developers {
            developer {
                id.set("Shusek")
                name.set("Shusek")
            }
        }
        licenses {
            license {
                name.set("MIT (libdovi); repository license applies to the Kotlin bridge")
                url.set("https://github.com/quietvoid/dovi_tool/blob/main/LICENSE")
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
    if (!System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey").isNullOrBlank()) {
        signAllPublications()
    }
}

val hdrPipelineMajorVersion = 2

val validateReleaseVersion =
    tasks.register("validateReleaseVersion") {
        group = "verification"
        description = "Rejects mutable or non-SemVer versions before publishing a remote release."
        inputs.property("releaseVersion", projectVersion)
        inputs.property("hdrPipelineMajorVersion", hdrPipelineMajorVersion)

        doLast {
            val releaseVersion = inputs.properties.getValue("releaseVersion") as String
            val hdrPipelineMajorVersion = inputs.properties.getValue("hdrPipelineMajorVersion") as Int
            val semverRegex =
                Regex(
                    "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                        "(?:-(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)" +
                        "(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*)?" +
                        "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
                )
            check(semverRegex.matches(releaseVersion)) {
                "Release version '$releaseVersion' is not valid immutable SemVer."
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
            name.contains("GithubPages", ignoreCase = true) ||
            name == "publishAndReleaseToMavenCentral"
    if (publishesRemoteRelease) dependsOn(validateReleaseVersion)
}
