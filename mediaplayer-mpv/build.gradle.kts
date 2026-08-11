import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import java.io.DataInputStream
import java.util.zip.ZipFile
import org.gradle.jvm.tasks.Jar as JvmJar

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.vannitktech.maven.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlinCocoapods)
}

val projectVersion =
    providers.gradleProperty("publicationVersion").orNull
        ?: "dev"
val projectGroup = "io.github.shusek"
val kmediaMpvVersion = libs.versions.kmediaMpv.get()
val kmediaFfmpegRuntimeVersion = "0.1.0-rc.7"
val windowsMpvRuntimeVerification =
    configurations.create("windowsMpvRuntimeVerification") {
        description = "Resolves the complete published MPV runtime graph for Windows DLL verification."
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = true
    }
dependencies.add(
    windowsMpvRuntimeVerification.name,
    "io.github.shusek:kmedia-mpv-runtime-desktop:$kmediaMpvVersion",
)
val releaseStagingMavenRepository = providers.gradleProperty("releaseStagingMavenRepository").orNull
val releaseSigningEnabled =
    providers
        .gradleProperty("releaseSigningEnabled")
        .map(String::toBoolean)
        .getOrElse(false)
val nativeJvmTestResources =
    providers.gradleProperty("composeMediaPlayerMpvTestNativeResources")
val kmediaMpvPodDirectory =
    providers
        .gradleProperty("kmediaMpvPodDirectory")
        .map(rootProject::file)
        .orElse(layout.buildDirectory.dir("kmediaMpvPod").map { it.asFile })
val kmediaFfmpegRuntimePodDirectory =
    providers
        .gradleProperty("kmediaFfmpegRuntimePodDirectory")
        .map(rootProject::file)
        .orElse(layout.buildDirectory.dir("kmediaFfmpegRuntimePod").map { it.asFile })
val kmediaMpvAssRuntimePodDirectory =
    providers
        .gradleProperty("kmediaMpvAssRuntimePodDirectory")
        .map(rootProject::file)
        .orElse(layout.buildDirectory.dir("kmediaAssRuntimePod").map { it.asFile })
val appleMpvNativeDirectory = layout.projectDirectory.dir("native/apple")
val macMpvNativeDirectory = layout.projectDirectory.dir("src/jvmMain/native/macos")
val macMpvNativeResources = layout.buildDirectory.dir("generated/mpvMacNative/resources")
val iosArm64MpvBridge = layout.buildDirectory.dir("generated/appleMpvBridge/ios-arm64")
val iosSimulatorArm64MpvBridge =
    layout.buildDirectory.dir("generated/appleMpvBridge/ios-simulator-arm64")
val skipAppleMpvBridgeBuild =
    providers
        .gradleProperty("composeMediaPlayer.skipMpvAppleNativeBuild")
        .orElse(providers.gradleProperty("composeMediaPlayer.skipNativeBuild"))
        .orElse(providers.environmentVariable("COMPOSE_MEDIA_PLAYER_SKIP_NATIVE_BUILD"))
        .map { it.equals("true", ignoreCase = true) }
        .getOrElse(false)
val canBuildAppleMpvBridge = Os.isFamily(Os.FAMILY_MAC) && !skipAppleMpvBridgeBuild
// Local runtime pods are release inputs and must not be required by a JVM-only IDEA import.
val skipAppleInteropDuringIdeaSync =
    providers
        .systemProperty("idea.sync.active")
        .map(String::toBoolean)
        .getOrElse(false)

val javaToolchains = extensions.getByType<JavaToolchainService>()
val macMpvNativeJdk =
    javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
val buildMacMpvNative =
    tasks.register<Exec>("buildMacMpvNative") {
        description = "Builds the embedded macOS libmpv OpenGL/EDR and macvk host surfaces."
        group = "build"
        enabled = Os.isFamily(Os.FAMILY_MAC)
        workingDir(layout.projectDirectory)
        commandLine(
            "bash",
            macMpvNativeDirectory.file("build.sh").asFile.absolutePath,
            macMpvNativeJdk
                .get()
                .metadata.installationPath.asFile.absolutePath,
            macMpvNativeResources.get().asFile.absolutePath,
        )
        inputs.file(macMpvNativeDirectory.file("build.sh"))
        inputs.file(macMpvNativeDirectory.file("MpvMacVideoBridge.m"))
        outputs.file(
            macMpvNativeResources.map {
                it.file("composemediaplayer/native/darwin-arm64/libComposeMediaPlayerMpvMac.dylib")
            },
        )
    }

fun registerAppleMpvBridgeBuild(
    taskName: String,
    target: String,
    outputDirectory: Provider<Directory>,
) = tasks.register<Exec>(taskName) {
    description = "Builds the libmpv dynamic-loader bridge for $target."
    group = "build"
    enabled = canBuildAppleMpvBridge
    workingDir(layout.projectDirectory)
    commandLine(
        "bash",
        appleMpvNativeDirectory.file("build-bridge.sh").asFile.absolutePath,
        target,
        outputDirectory.get().asFile.absolutePath,
    )
    inputs.file(appleMpvNativeDirectory.file("build-bridge.sh"))
    inputs.file(appleMpvNativeDirectory.file("ComposeMediaPlayerMpvBridge.c"))
    inputs.file(appleMpvNativeDirectory.file("include/ComposeMediaPlayerMpvBridge.h"))
    outputs.file(outputDirectory.map { it.file("libcomposemediaplayer_mpv_bridge.a") })
}

val buildIosArm64MpvBridge =
    registerAppleMpvBridgeBuild(
        taskName = "buildIosArm64MpvBridge",
        target = "ios-arm64",
        outputDirectory = iosArm64MpvBridge,
    )
val buildIosSimulatorArm64MpvBridge =
    registerAppleMpvBridgeBuild(
        taskName = "buildIosSimulatorArm64MpvBridge",
        target = "ios-simulator-arm64",
        outputDirectory = iosSimulatorArm64MpvBridge,
    )

group = projectGroup
version = projectVersion

kotlin {
    jvmToolchain(25)

    cocoapods {
        version = if (projectVersion == "dev") "0.0.1-dev" else projectVersion
        summary = "Optional MPV backend for Compose Media Player"
        homepage = "https://github.com/SuvioMedia/KMediaPlayer"
        name = "ComposeMediaPlayerMpv"
        ios.deploymentTarget = "16.2"
        pod(
            name = "KMediaAssRuntime",
            version = kmediaFfmpegRuntimeVersion,
            path = kmediaMpvAssRuntimePodDirectory.get(),
            linkOnly = true,
        )
        pod(
            name = "KMediaFfmpegRuntime",
            version = kmediaFfmpegRuntimeVersion,
            path = kmediaFfmpegRuntimePodDirectory.get(),
            linkOnly = true,
        )
        pod(
            name = "KMediaMpv",
            version = kmediaMpvVersion,
            path = kmediaMpvPodDirectory.get(),
            linkOnly = true,
        )

        framework {
            baseName = "ComposeMediaPlayerMpv"
            isStatic = false
            export(project(":mediaplayer-core"))
        }
    }

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        keepLocallyUnsupportedTargets.set(false)
    }

    android {
        namespace = "io.github.kdroidfilter.composemediaplayer.mpv"
        compileSdk = 37
        minSdk = 28
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    listOf(
        iosArm64() to iosArm64MpvBridge,
        iosSimulatorArm64() to iosSimulatorArm64MpvBridge,
    ).forEach { (target, bridgeOutput) ->
        target.compilations.getByName("main") {
            cinterops.create("appleMpv") {
                defFile(project.file("src/nativeInterop/cinterop/appleMpv.def"))
                includeDirs.headerFilterOnly(appleMpvNativeDirectory.dir("include").asFile)
                extraOpts(
                    "-libraryPath",
                    bridgeOutput.get().asFile.absolutePath,
                )
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":mediaplayer-core"))
            implementation(libs.compose.foundation)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidcontextprovider)
            implementation(libs.kotlinx.coroutines.android)
            api("io.github.shusek:kmedia-mpv-runtime-android:$kmediaMpvVersion") {
                version { strictly(kmediaMpvVersion) }
            }
        }
        named("androidHostTest") {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
        jvmMain.dependencies {
            api(project(":mediaplayer-desktop-tao"))
            implementation(libs.compose.ui)
            compileOnly(libs.graalvm.nativeimage)
            api("io.github.shusek:kmedia-mpv-runtime-desktop:$kmediaMpvVersion") {
                version { strictly(kmediaMpvVersion) }
            }
        }
        jvmTest.dependencies {
            implementation(project(":mediaplayer"))
            implementation(project(":mediaplayer-kmediabridge"))
            implementation(compose.desktop.currentOs)
            implementation(kotlin("test-junit"))
        }
        iosMain.dependencies {
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        iosTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

nativeJvmTestResources.orNull?.let { resourcesDirectory ->
    tasks.named<ProcessResources>("jvmTestProcessResources") {
        from(resourcesDirectory)
    }
}
tasks.named<ProcessResources>("jvmProcessResources") {
    dependsOn(buildMacMpvNative)
    from(macMpvNativeResources)
}
tasks.named<ProcessResources>("jvmTestProcessResources") {
    from(rootProject.file("mediaplayer-kmediabridge/src/jvmTest/resources"))
}

tasks.matching { it.name.startsWith("link") && it.name.endsWith("IosArm64") }.configureEach {
    dependsOn(buildIosArm64MpvBridge)
}
tasks.matching { it.name.startsWith("link") && it.name.endsWith("IosSimulatorArm64") }.configureEach {
    dependsOn(buildIosSimulatorArm64MpvBridge)
}
tasks.matching { it.name == "cinteropAppleMpvIosArm64" }.configureEach {
    dependsOn(buildIosArm64MpvBridge)
}
tasks.matching { it.name == "cinteropAppleMpvIosSimulatorArm64" }.configureEach {
    dependsOn(buildIosSimulatorArm64MpvBridge)
}

if (skipAppleInteropDuringIdeaSync) {
    val appleInteropPreparationTasks =
        setOf(
            "iosArm64Cinterop-appleMpvKlib",
            "iosSimulatorArm64Cinterop-appleMpvKlib",
            "podspec",
            "podInstall",
            "podImport",
        )
    tasks.matching { it.name in appleInteropPreparationTasks }.configureEach {
        enabled = false
    }
}

val java25ClassFileVersion = 69
val verifyJvm25Bytecode =
    tasks.register("verifyJvm25Bytecode") {
        group = "verification"
        description = "Verifies that every MPV adapter JVM class targets Java 25."

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
                            classFile.readUnsignedShort()
                            val majorVersion = classFile.readUnsignedShort()
                            check(majorVersion == expectedClassFileVersion) {
                                "${entry.name} targets classfile $majorVersion; expected $expectedClassFileVersion."
                            }
                            verifiedClasses++
                        }
                    }
            }
            check(verifiedClasses > 0) { "The MPV adapter JVM publication contains no classes." }
        }
    }

tasks.named("check") {
    dependsOn(verifyJvm25Bytecode)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    providers.gradleProperty("composeMediaPlayerLegacyTestMedia").orNull?.let { mediaPath ->
        systemProperty("composemediaplayer.legacyTestMedia", mediaPath)
    }
    providers.gradleProperty("composeMediaPlayerWmaProTestMedia").orNull?.let { mediaPath ->
        systemProperty("composemediaplayer.wmaProTestMedia", mediaPath)
    }
    providers.gradleProperty("composeMediaPlayerMpvLibraryPath").orNull?.let { libraryPath ->
        systemProperty("composemediaplayer.mpvLibraryPath", libraryPath)
    }
    providers.gradleProperty("composeMediaPlayerKMediaBridgeTestRuntimeDirectory").orNull?.let { runtimeDirectory ->
        systemProperty("composemediaplayer.test.kMediaBridgeRuntimeDirectory", runtimeDirectory)
    }
    providers.gradleProperty("composeMediaPlayerNativeSurfaceTestMedia").orNull?.let { mediaPath ->
        systemProperty("composemediaplayer.nativeSurfaceTestMedia", mediaPath)
    }
    providers.gradleProperty("composeMediaPlayerLoopbackHttpTestSource").orNull?.let { source ->
        systemProperty("composemediaplayer.test.loopbackHttpSource", source)
    }
    providers.gradleProperty("composeMediaPlayerDirectHttpTestSource").orNull?.let { source ->
        systemProperty("composemediaplayer.test.directHttpSource", source)
    }
}
tasks.named<Test>("jvmTest") {
    // Keep process-global native loader and libmpv lifecycle state from leaking
    // between otherwise isolated integration tests.
    forkEvery = 1
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
        artifactId = "composemediaplayer-mpv",
        version = projectVersion,
    )

    pom {
        name.set("Compose Media Player MPV Backend")
        description.set("Optional MPV backend for Compose Media Player on Android, iOS, Linux, macOS, and Windows.")
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
    if (releaseSigningEnabled) {
        signAllPublications()
    }
}

val validateReleaseVersion =
    tasks.register("validateReleaseVersion") {
        group = "verification"
        description = "Rejects mutable or non-SemVer versions before publishing the MPV adapter remotely."
        inputs.property("releaseVersion", projectVersion)

        doLast {
            val releaseVersion = inputs.properties.getValue("releaseVersion") as String
            val semver =
                Regex(
                    "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                        "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?" +
                        "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
                )
            check(semver.matches(releaseVersion)) {
                "Release version '$releaseVersion' is not a valid immutable SemVer version."
            }
            check(releaseVersion.substringBefore('.').toInt() >= 4) {
                "The Tao MPV adapter belongs to KMediaPlayer 4.x and cannot be published as $releaseVersion."
            }
        }
    }

val stageWindowsMpvRuntimeForVerification =
    tasks.register<Sync>("stageWindowsMpvRuntimeForVerification") {
        group = "verification"
        description = "Stages the complete published MPV runtime graph for Windows PE verification."
        from(windowsMpvRuntimeVerification)
        into(layout.buildDirectory.dir("runtime-verification/windows-mpv"))
        include(
            "kmedia-mpv-runtime-desktop-*.jar",
            "kmedia-ffmpeg-runtime-desktop-*.jar",
            "kmedia-ass-runtime-desktop-*.jar",
        )
        rename { fileName ->
            when {
                fileName.startsWith("kmedia-mpv-runtime-desktop-") ->
                    "kmedia-mpv-runtime-desktop.jar"
                fileName.startsWith("kmedia-ffmpeg-runtime-desktop-") ->
                    "kmedia-ffmpeg-runtime-desktop.jar"
                fileName.startsWith("kmedia-ass-runtime-desktop-") ->
                    "kmedia-ass-runtime-desktop.jar"
                else -> fileName
            }
        }
        doLast {
            val expected =
                setOf(
                    "kmedia-mpv-runtime-desktop.jar",
                    "kmedia-ffmpeg-runtime-desktop.jar",
                    "kmedia-ass-runtime-desktop.jar",
                )
            val actual =
                destinationDir
                    .listFiles()
                    ?.filter { it.isFile }
                    ?.map { it.name }
                    ?.toSet()
                    .orEmpty()
            check(actual == expected) {
                "Staged Windows MPV runtime graph differs: expected=$expected, actual=$actual"
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

tasks
    .withType<JvmJar>()
    .matching {
        it.name == "sourcesJar" ||
            (it.name.startsWith("ios", ignoreCase = true) && it.name.endsWith("SourcesJar"))
    }.configureEach {
        from(layout.projectDirectory.dir("src/commonMain/resources/META-INF")) {
            into("META-INF")
        }
        from(appleMpvNativeDirectory) {
            into("native/apple")
        }
        from(layout.projectDirectory.dir("src/nativeInterop/cinterop")) {
            into("nativeInterop/cinterop")
        }
    }
