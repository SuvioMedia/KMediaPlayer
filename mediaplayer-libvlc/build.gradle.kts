import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.vannitktech.maven.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlinCocoapods)
}

val projectVersion = providers.gradleProperty("publicationVersion").orNull ?: "dev"
val releaseSigningEnabled =
    providers.gradleProperty("releaseSigningEnabled").map(String::toBoolean).getOrElse(false)
val kmediaVlcVersion = libs.versions.kmediaVlc.get()
val kmediaVlcPodVersion =
    providers
        .gradleProperty("kmediaVlcPodVersion")
        .orElse(kmediaVlcVersion.removeSuffix("-SNAPSHOT"))
val kmediaVlcPodDirectory =
    providers
        .gradleProperty("kmediaVlcPodDirectory")
        .map(rootProject::file)
        .orElse(layout.projectDirectory.dir("native/apple/compile-only-kmediavlc-pod").asFile)
val appleVlcNativeDirectory = layout.projectDirectory.dir("native/apple")
val iosArm64VlcBridge = layout.buildDirectory.dir("generated/appleVlcBridge/ios-arm64")
val iosSimulatorArm64VlcBridge =
    layout.buildDirectory.dir("generated/appleVlcBridge/ios-simulator-arm64")
val skipAppleInteropDuringIdeaSync =
    providers
        .systemProperty("idea.sync.active")
        .map(String::toBoolean)
        .getOrElse(false)
val canBuildAppleVlcBridge = Os.isFamily(Os.FAMILY_MAC)

fun registerAppleVlcBridgeBuild(
    taskName: String,
    target: String,
    outputDirectory: Provider<Directory>,
) = tasks.register<Exec>(taskName) {
    description = "Builds the bundled-libVLC dynamic-loader bridge for $target."
    group = "build"
    enabled = canBuildAppleVlcBridge
    workingDir(layout.projectDirectory)
    commandLine(
        "bash",
        appleVlcNativeDirectory.file("build-bridge.sh").asFile.absolutePath,
        target,
        outputDirectory.get().asFile.absolutePath,
    )
    inputs.file(appleVlcNativeDirectory.file("build-bridge.sh"))
    inputs.file(appleVlcNativeDirectory.file("ComposeMediaPlayerLibVlcBridge.c"))
    inputs.file(appleVlcNativeDirectory.file("include/ComposeMediaPlayerLibVlcBridge.h"))
    inputs.file(appleVlcNativeDirectory.file("include/kmediavlc_client.h"))
    outputs.file(outputDirectory.map { it.file("libcomposemediaplayer_libvlc_bridge.a") })
}

val buildIosArm64VlcBridge =
    registerAppleVlcBridgeBuild(
        taskName = "buildIosArm64VlcBridge",
        target = "ios-arm64",
        outputDirectory = iosArm64VlcBridge,
    )
val buildIosSimulatorArm64VlcBridge =
    registerAppleVlcBridgeBuild(
        taskName = "buildIosSimulatorArm64VlcBridge",
        target = "ios-simulator-arm64",
        outputDirectory = iosSimulatorArm64VlcBridge,
    )

group = "io.github.shusek"
version = projectVersion

kotlin {
    jvmToolchain(25)

    cocoapods {
        version = if (projectVersion == "dev") "0.0.1-dev" else projectVersion
        summary = "Optional bundled libVLC 4 backend for Compose Media Player"
        homepage = "https://github.com/SuvioMedia/KMediaPlayer"
        name = "ComposeMediaPlayerLibVlc"
        ios.deploymentTarget = "16.2"
        pod(
            name = "KMediaVlc",
            version = kmediaVlcPodVersion.get(),
            path = kmediaVlcPodDirectory.get(),
            linkOnly = true,
        )

        framework {
            baseName = "ComposeMediaPlayerLibVlc"
            isStatic = false
            export(project(":mediaplayer-core"))
        }
    }

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        keepLocallyUnsupportedTargets.set(false)
    }

    android {
        namespace = "io.github.kdroidfilter.composemediaplayer.libvlc"
        compileSdk = 37
        minSdk = 28
        withHostTest {}
        compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
    }

    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
    }

    listOf(
        iosArm64() to iosArm64VlcBridge,
        iosSimulatorArm64() to iosSimulatorArm64VlcBridge,
    ).forEach { (target, bridgeOutput) ->
        target.compilations.getByName("main") {
            cinterops.create("appleVlc") {
                defFile(project.file("src/nativeInterop/cinterop/appleVlc.def"))
                includeDirs.headerFilterOnly(appleVlcNativeDirectory.dir("include").asFile)
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
            api(libs.kmedia.vlc.runtime.android)
        }
        named("androidHostTest") {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
        jvmMain.dependencies {
            api(project(":mediaplayer-desktop-tao"))
            api(libs.kmedia.vlc.runtime.desktop)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
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

tasks.matching { it.name.startsWith("link") && it.name.endsWith("IosArm64") }.configureEach {
    dependsOn(buildIosArm64VlcBridge)
}
tasks.matching { it.name.startsWith("link") && it.name.endsWith("IosSimulatorArm64") }.configureEach {
    dependsOn(buildIosSimulatorArm64VlcBridge)
}
tasks.matching { it.name == "cinteropAppleVlcIosArm64" }.configureEach {
    dependsOn(buildIosArm64VlcBridge)
}
tasks.matching { it.name == "cinteropAppleVlcIosSimulatorArm64" }.configureEach {
    dependsOn(buildIosSimulatorArm64VlcBridge)
}

if (skipAppleInteropDuringIdeaSync) {
    val appleInteropPreparationTasks =
        setOf(
            "iosArm64Cinterop-appleVlcKlib",
            "iosSimulatorArm64Cinterop-appleVlcKlib",
            "podspec",
            "podInstall",
            "podImport",
        )
    tasks.matching { it.name in appleInteropPreparationTasks }.configureEach {
        enabled = false
    }
}

publishing {
    repositories {
        maven {
            name = "consumerSmoke"
            url = uri(rootProject.layout.buildDirectory.dir("consumer-repository"))
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = "io.github.shusek",
        artifactId = "composemediaplayer-libvlc",
        version = projectVersion,
    )
    pom {
        name.set("Compose Media Player libVLC 4 Backend")
        description.set("Optional bundled libVLC 4 backend for desktop, Android, and iOS.")
        inceptionYear.set("2026")
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
    if (releaseSigningEnabled) signAllPublications()
}

val validateReleaseVersion =
    tasks.register("validateReleaseVersion") {
        group = "verification"
        inputs.property("releaseVersion", projectVersion)
        doLast {
            val value = inputs.properties.getValue("releaseVersion") as String
            check(Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z.-]+)?$").matches(value)) {
                "The libVLC adapter requires an immutable SemVer publication version."
            }
        }
    }

tasks.configureEach {
    if (name.contains("MavenCentral", ignoreCase = true) || name == "publishAndReleaseToMavenCentral") {
        dependsOn(validateReleaseVersion)
    }
}
