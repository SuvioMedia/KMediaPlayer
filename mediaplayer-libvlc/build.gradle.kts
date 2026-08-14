import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
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
}

val projectVersion = providers.gradleProperty("publicationVersion").orNull ?: "dev"
val releaseStagingMavenRepository = providers.gradleProperty("releaseStagingMavenRepository").orNull
val releaseSigningEnabled =
    providers.gradleProperty("releaseSigningEnabled").map(String::toBoolean).getOrElse(false)
val kmediaVlcIosVersion = libs.versions.kmediaVlcIos.get()
val kmediaVlcIosRuntime =
    configurations.create("kmediaVlcIosRuntime") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
val appleVlcNativeDirectory = layout.projectDirectory.dir("native/apple")
val prepareIosRuntimeScript = appleVlcNativeDirectory.file("prepare-ios-runtime.py")
val runIosSimulatorIntegrationScript = appleVlcNativeDirectory.file("run-ios-simulator-maven-integration-test.sh")
val iosArm64VlcBridge = layout.buildDirectory.dir("generated/appleVlcBridge/ios-arm64")
val iosSimulatorArm64VlcBridge =
    layout.buildDirectory.dir("generated/appleVlcBridge/ios-simulator-arm64")
val iosSimulatorRuntime = layout.buildDirectory.dir("kmediaVlcIosRuntime/iphonesimulator")
val iosSimulatorIntegrationWork = layout.buildDirectory.dir("iosSimulatorLibVlcIntegration")
val skipAppleInteropDuringIdeaSync =
    providers
        .systemProperty("idea.sync.active")
        .map(String::toBoolean)
        .getOrElse(false)
val canBuildAppleVlcBridge = Os.isFamily(Os.FAMILY_MAC)
val vrAcceptanceRuntimeDirectory = providers.gradleProperty("kmediaVlcVrRuntimeDirectory")
val vrAcceptanceBridge = providers.gradleProperty("kmediaVlcVrBridgePath")
val vrAcceptanceFixture = providers.gradleProperty("kmediaVlcVrFixture")
val vrAcceptanceInputs =
    listOf(
        vrAcceptanceRuntimeDirectory.isPresent,
        vrAcceptanceBridge.isPresent,
        vrAcceptanceFixture.isPresent,
    )
val vrAcceptanceConfigured = vrAcceptanceInputs.all { it }
require(vrAcceptanceInputs.none { it } || vrAcceptanceConfigured) {
    "The libVLC VR acceptance test requires its runtime directory, bridge, and fixture together."
}

dependencies {
    add(kmediaVlcIosRuntime.name, "${libs.kmedia.vlc.runtime.ios.get()}@zip")
}

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

val prepareKMediaVlcIosSimulatorRuntime =
    tasks.register<Exec>("prepareKMediaVlcIosSimulatorRuntime") {
        group = "verification"
        description = "Resolves KMediaVlc iOS from Maven Central and verifies its simulator frameworks."
        enabled = canBuildAppleVlcBridge
        inputs.file(prepareIosRuntimeScript)
        inputs.file(appleVlcNativeDirectory.file("include/kmediavlc_client.h"))
        inputs.files(kmediaVlcIosRuntime)
        inputs.property("kmediaVlcIosVersion", kmediaVlcIosVersion)
        outputs.dir(iosSimulatorRuntime)
        doFirst {
            val output = iosSimulatorRuntime.get().asFile
            delete(output)
            check(output.parentFile.mkdirs() || output.parentFile.isDirectory) {
                "Cannot create the KMediaVlc iOS runtime output directory."
            }
            commandLine(
                "python3",
                prepareIosRuntimeScript.asFile.absolutePath,
                "--archive",
                kmediaVlcIosRuntime.singleFile.absolutePath,
                "--platform",
                "iphonesimulator",
                "--expected-version",
                kmediaVlcIosVersion,
                "--client-header",
                appleVlcNativeDirectory.file("include/kmediavlc_client.h").asFile.absolutePath,
                "--output",
                output.absolutePath,
            )
        }
    }

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

tasks.register<Exec>("iosSimulatorArm64LibVlcIntegrationTest") {
    group = "verification"
    description = "Runs real KMediaPlayer playback with the Maven-delivered KMediaVlc iOS runtime."
    enabled = canBuildAppleVlcBridge
    dependsOn("linkDebugTestIosSimulatorArm64", prepareKMediaVlcIosSimulatorRuntime)
    val testExecutable = layout.buildDirectory.file("bin/iosSimulatorArm64/debugTest/test.kexe")
    inputs.file(runIosSimulatorIntegrationScript)
    inputs.file(appleVlcNativeDirectory.file("run-ios-simulator-integration-test.sh"))
    inputs.file(testExecutable)
    inputs.dir(iosSimulatorRuntime)
    doFirst {
        val work = iosSimulatorIntegrationWork.get().asFile
        delete(work)
        check(work.parentFile.mkdirs() || work.parentFile.isDirectory) {
            "Cannot create the iOS simulator integration-test directory."
        }
        commandLine(
            "bash",
            runIosSimulatorIntegrationScript.asFile.absolutePath,
            testExecutable.get().asFile.absolutePath,
            iosSimulatorRuntime
                .get()
                .dir("Frameworks")
                .asFile.absolutePath,
            work.absolutePath,
        )
    }
}

tasks.named<Test>("jvmTest") {
    if (vrAcceptanceConfigured) {
        val runtimeDirectory = rootProject.file(vrAcceptanceRuntimeDirectory.get())
        val bridge = rootProject.file(vrAcceptanceBridge.get())
        val fixture = rootProject.file(vrAcceptanceFixture.get())
        inputs.dir(runtimeDirectory)
        inputs.file(bridge)
        inputs.file(fixture)
        systemProperty("kmediavlc.vr.runtimeDirectory", runtimeDirectory.absolutePath)
        systemProperty("kmediavlc.vr.bridgePath", bridge.absolutePath)
        systemProperty("kmediavlc.vr.fixture", fixture.absolutePath)
    }
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

val java25ClassFileVersion = 69
val verifyJvm25Bytecode =
    tasks.register("verifyJvm25Bytecode") {
        group = "verification"
        description = "Verifies that every libVLC adapter JVM class targets Java 25."

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
            check(verifiedClasses > 0) { "The libVLC adapter JVM publication contains no classes." }
        }
    }

tasks.named("check") {
    dependsOn(verifyJvm25Bytecode)
}

publishing {
    repositories {
        maven {
            name = "consumerSmoke"
            url = uri(rootProject.layout.buildDirectory.dir("consumer-repository"))
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
    configureBasedOnAppliedPlugins(
        javadocJar =
            com.vanniktech.maven.publish.JavadocJar
                .Empty(),
        sourcesJar =
            com.vanniktech.maven.publish.SourcesJar
                .Empty(),
    )
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
                name.set("Suvio Proprietary Component License")
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
