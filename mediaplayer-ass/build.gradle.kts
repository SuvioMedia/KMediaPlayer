@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import dev.detekt.gradle.Detekt
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

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
        ?: System
            .getenv("GITHUB_REF")
            ?.takeIf { it.startsWith("refs/tags/") }
            ?.removePrefix("refs/tags/")
            ?.removePrefix("v")
        ?: "dev"
val projectGroup = "io.github.shusek"
val assRuntimeVersion = "0.1.0-rc.4"
val releaseStagingMavenRepository = providers.gradleProperty("releaseStagingMavenRepository").orNull
val appleNativeDirectory = layout.projectDirectory.dir("native/apple")
val assRuntimeAppleOutputs =
    providers
        .gradleProperty("kmediaAssRuntimeAppleOutputs")
        .map(rootProject::file)
        .orElse(layout.buildDirectory.dir("kmediaAssRuntimeAppleOutputs").map { it.asFile })
val assRuntimePodDirectory =
    providers
        .gradleProperty("kmediaAssRuntimePodDirectory")
        .map(rootProject::file)
        .orElse(layout.buildDirectory.dir("kmediaAssRuntimePod").map { it.asFile })
val iosArm64AppleNative = layout.buildDirectory.dir("generated/appleAssIos/ios-arm64")
val iosSimulatorArm64AppleNative =
    layout.buildDirectory.dir("generated/appleAssIos/ios-simulator-arm64")
val skipAppleNativeBuild =
    providers
        .gradleProperty("composeMediaPlayer.skipAssAppleNativeBuild")
        .orElse(providers.gradleProperty("composeMediaPlayer.skipNativeBuild"))
        .orElse(providers.environmentVariable("COMPOSE_MEDIA_PLAYER_SKIP_NATIVE_BUILD"))
        .map { it.equals("true", ignoreCase = true) }
        .getOrElse(false)
val canBuildAppleNative = Os.isFamily(Os.FAMILY_MAC) && !skipAppleNativeBuild
// IDEA schedules Apple cinterops during every project import. The runtime SDK is a release input,
// so JVM-only development must not require it merely to synchronize the Gradle model.
val skipAppleInteropDuringIdeaSync =
    providers
        .systemProperty("idea.sync.active")
        .map(String::toBoolean)
        .getOrElse(false)

fun registerAppleAssBuild(
    taskName: String,
    target: String,
    outputDirectory: Provider<Directory>,
) = tasks.register<Exec>(taskName) {
    description = "Builds the thin ASS renderer client for $target."
    group = "build"
    enabled = canBuildAppleNative
    workingDir(layout.projectDirectory)
    commandLine(
        "bash",
        appleNativeDirectory.file("build.sh").asFile.absolutePath,
        target,
        outputDirectory.get().asFile.absolutePath,
        assRuntimeAppleOutputs.get().resolve(target).absolutePath,
    )
    inputs.file(appleNativeDirectory.file("build.sh"))
    inputs.file(appleNativeDirectory.file("KMediaAssRenderer.c"))
    inputs.file(appleNativeDirectory.file("include/KMediaAssRenderer.h"))
    inputs.dir(layout.projectDirectory.dir("native/common"))
    inputs.dir(assRuntimeAppleOutputs.map { it.resolve(target) })
    outputs.dir(outputDirectory)
}

val buildIosArm64AppleAss =
    registerAppleAssBuild(
        taskName = "buildIosArm64AppleAss",
        target = "ios-arm64",
        outputDirectory = iosArm64AppleNative,
    )
val buildIosSimulatorArm64AppleAss =
    registerAppleAssBuild(
        taskName = "buildIosSimulatorArm64AppleAss",
        target = "ios-simulator-arm64",
        outputDirectory = iosSimulatorArm64AppleNative,
    )
group = projectGroup
version = projectVersion

kotlin {
    jvmToolchain(25)

    cocoapods {
        version = if (projectVersion == "dev") "0.0.1-dev" else projectVersion
        summary = "Optional ASS/SSA renderer for Compose Media Player"
        homepage = "https://github.com/SuvioMedia/KMediaPlayer"
        name = "ComposeMediaPlayerAss"
        ios.deploymentTarget = "16.2"
        pod(
            name = "KMediaAssRuntime",
            version = assRuntimeVersion,
            path = assRuntimePodDirectory.get(),
            linkOnly = true,
        )

        framework {
            baseName = "ComposeMediaPlayerAss"
            isStatic = false
            export(project(":mediaplayer-extension-api"))
        }
    }

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        keepLocallyUnsupportedTargets.set(false)
    }

    android {
        namespace = "io.github.kdroidfilter.composemediaplayer.ass"
        compileSdk = 37
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        androidResources.enable = true

        withHostTest { isIncludeAndroidResources = true }
        withDeviceTest { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }

        optimization {
            consumerKeepRules.apply {
                publish = true
                file("src/androidMain/aarKeepRules/kmediaass.pro")
            }
        }

        compilerOptions { jvmTarget.set(JvmTarget.JVM_25) }
    }

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_25) }
    }

    wasmJs {
        browser()
        binaries.executable()
    }

    listOf(
        iosArm64() to
            Triple(
                iosArm64AppleNative,
                buildIosArm64AppleAss,
                "ios-arm64",
            ),
        iosSimulatorArm64() to
            Triple(
                iosSimulatorArm64AppleNative,
                buildIosSimulatorArm64AppleAss,
                "ios-simulator-arm64",
            ),
    ).forEach { (target, configuration) ->
        val (nativeOutput, _, runtimeTarget) = configuration
        val runtimeFrameworkDirectory =
            assRuntimeAppleOutputs
                .get()
                .resolve(runtimeTarget)
                .resolve("Frameworks")
                .absolutePath

        target.binaries.all {
            linkerOpts("-F$runtimeFrameworkDirectory")
        }
        target.binaries.getTest(NativeBuildType.DEBUG).linkerOpts(
            "-rpath",
            runtimeFrameworkDirectory,
        )

        target.compilations.getByName("main") {
            cinterops.create("appleAss") {
                defFile(project.file("src/nativeInterop/cinterop/appleAss.def"))
                includeDirs.headerFilterOnly(appleNativeDirectory.dir("include").asFile)
                extraOpts(
                    "-libraryPath",
                    nativeOutput.get().asFile.absolutePath,
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
        }
        androidMain.dependencies {
            api("io.github.shusek:kmedia-ass-runtime-android:$assRuntimeVersion") {
                version { strictly(assRuntimeVersion) }
            }
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.core)
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.exoplayer)
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
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain {
            dependencies {
                api("io.github.shusek:kmedia-ass-runtime-desktop:$assRuntimeVersion") {
                    version { strictly(assRuntimeVersion) }
                }
            }
        }
        iosMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        iosTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(npm("jassub", "2.5.14"))
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val verifyJvmMacArmNativeMatrix =
    tasks.register("verifyJvmMacArmNativeMatrix") {
        group = "verification"
        description = "Verifies that composemediaplayer-ass does not embed a private macOS ASS stack."

        val jvmJar = tasks.named<Jar>("jvmJar")
        dependsOn(jvmJar)
        val archiveFile = jvmJar.flatMap { it.archiveFile }
        inputs.file(archiveFile)

        doLast {
            ZipFile(inputs.files.singleFile).use { archive ->
                val nativeEntries =
                    archive
                        .entries()
                        .asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                entry.name.startsWith("composemediaplayer/ass/native/")
                        }.map { it.name }
                        .toList()
                check(nativeEntries.isEmpty()) {
                    "The ASS JVM JAR embeds a private native text stack: $nativeEntries"
                }
            }
        }
    }

val verifyJvmDesktopNativeMatrix =
    tasks.register("verifyJvmDesktopNativeMatrix") {
        group = "verification"
        description = "Verifies that the JVM adapter delegates its native text stack."

        val jvmJar = tasks.named<Jar>("jvmJar")
        dependsOn(jvmJar)
        val archiveFile = jvmJar.flatMap { it.archiveFile }
        inputs.file(archiveFile)

        doLast {
            ZipFile(inputs.files.singleFile).use { archive ->
                val forbidden =
                    archive
                        .entries()
                        .asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                (
                                    entry.name.startsWith("composemediaplayer/ass/native/") ||
                                        entry.name.endsWith(".dll") ||
                                        entry.name.endsWith(".dylib") ||
                                        entry.name.endsWith(".so")
                                )
                        }.map { it.name }
                        .toList()
                check(forbidden.isEmpty()) {
                    "The JVM adapter must receive libass transitively from kmedia-ass-runtime-desktop: $forbidden"
                }
            }
        }
    }

tasks.matching { it.name.startsWith("link") && it.name.endsWith("IosArm64") }.configureEach {
    dependsOn(buildIosArm64AppleAss)
}
tasks.matching { it.name.startsWith("link") && it.name.endsWith("IosSimulatorArm64") }.configureEach {
    dependsOn(buildIosSimulatorArm64AppleAss)
}
tasks.matching { it.name == "cinteropAppleAssIosArm64" }.configureEach {
    dependsOn(buildIosArm64AppleAss)
}
tasks.matching { it.name == "cinteropAppleAssIosSimulatorArm64" }.configureEach {
    dependsOn(buildIosSimulatorArm64AppleAss)
}

if (skipAppleInteropDuringIdeaSync) {
    val appleInteropPreparationTasks =
        setOf(
            "buildIosArm64AppleAss",
            "buildIosSimulatorArm64AppleAss",
            "cinteropAppleAssIosArm64",
            "cinteropAppleAssIosSimulatorArm64",
            "commonizeCInterop",
            "copyCommonizeCInteropForIde",
            "iosArm64Cinterop-appleAssKlib",
            "iosSimulatorArm64Cinterop-appleAssKlib",
            "podspec",
            "podInstall",
            "podImport",
        )
    tasks.matching { it.name in appleInteropPreparationTasks }.configureEach {
        enabled = false
    }
}

val verifyCoreAndroidAarHasNoAssPayload =
    tasks.register("verifyCoreAndroidAarHasNoAssPayload") {
        group = "verification"
        description = "Verifies that the core Android AAR does not contain the optional ASS backend."

        dependsOn(":mediaplayer:bundleAndroidMainAar")
        val coreAar = project(":mediaplayer").layout.buildDirectory.file("outputs/aar/mediaplayer.aar")
        inputs.file(coreAar)

        doLast {
            val forbiddenNativeNames =
                setOf(
                    "libkmediaass.so",
                    "libkmediaasscore.so",
                    "libkmediafribidi.so",
                    "libass.so",
                    "libfribidi.so",
                )
            val forbiddenClassPrefix =
                "io/github/kdroidfilter/composemediaplayer/subtitle/AndroidAss"
            val legalResourcePrefix = "META-INF/kmediaplayer/android-ass/"

            ZipFile(coreAar.get().asFile).use { archive ->
                val forbiddenNativeEntries =
                    archive
                        .entries()
                        .asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                entry.name.startsWith("jni/") &&
                                entry.name.substringAfterLast('/') in forbiddenNativeNames
                        }.map { it.name }
                        .toList()
                check(forbiddenNativeEntries.isEmpty()) {
                    "The core Android AAR still contains optional ASS native libraries: $forbiddenNativeEntries"
                }

                archive.getEntry("proguard.txt")?.let { proguardEntry ->
                    val proguard =
                        archive.getInputStream(proguardEntry).bufferedReader().use { it.readText() }
                    check("AndroidAssNativeBridge" !in proguard && "kmediaass" !in proguard.lowercase()) {
                        "The core Android AAR still publishes ASS-specific consumer rules."
                    }
                }

                val classesEntry = checkNotNull(archive.getEntry("classes.jar")) { "Core AAR has no classes.jar" }
                val classesBytes = archive.getInputStream(classesEntry).use { input -> input.readBytes() }
                val forbiddenClassEntries = mutableListOf<String>()
                ZipInputStream(ByteArrayInputStream(classesBytes)).use { jar ->
                    while (true) {
                        val entry = jar.nextEntry ?: break
                        if (
                            !entry.isDirectory &&
                            (entry.name.startsWith(forbiddenClassPrefix) || entry.name.startsWith(legalResourcePrefix))
                        ) {
                            forbiddenClassEntries += entry.name
                        }
                    }
                }
                check(forbiddenClassEntries.isEmpty()) {
                    "The core Android AAR still contains optional ASS classes or legal resources: " +
                        forbiddenClassEntries
                }
            }
            logger.lifecycle("Verified that the core Android AAR contains no optional ASS payload.")
        }
    }

val verifyAndroidAssAar =
    tasks.register("verifyAndroidAssAar") {
        group = "verification"
        description = "Verifies the thin Android ASS bridge and delegated shared runtime."

        dependsOn(tasks.named("bundleAndroidMainAar"), verifyCoreAndroidAarHasNoAssPayload)
        val aar = layout.buildDirectory.file("outputs/aar/${project.name}.aar")
        val checksums = layout.projectDirectory.file("src/androidMain/native/ass/CHECKSUMS.sha256")
        inputs.file(aar)
        inputs.file(checksums)

        doLast {
            fun java.io.InputStream.sha256(): String {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }

            val expected =
                buildMap {
                    checksums.asFile
                        .readLines()
                        .filter(String::isNotBlank)
                        .forEach { line ->
                            val parts = line.trim().split(Regex("\\s+"), limit = 2)
                            check(parts.size == 2 && Regex("[0-9a-f]{64}").matches(parts[0])) {
                                "Malformed Android ASS checksum line: $line"
                            }
                            val previous = put(parts[1], parts[0])
                            check(previous == null) {
                                "Duplicate Android ASS checksum path: ${parts[1]}"
                            }
                        }
                }
            val androidAbis = setOf("arm64-v8a", "armeabi-v7a")
            val expectedNative =
                expected.filterKeys { path -> path.substringBefore('/') in androidAbis && path.endsWith(".so") }
            check(expectedNative.size == androidAbis.size) {
                "Expected one thin Android ASS bridge for every ABI."
            }
            check(expected.keys == expectedNative.keys) {
                "The Android ASS checksum manifest must contain only the thin JNI bridge matrix."
            }

            ZipFile(aar.get().asFile).use { archive ->
                val actualNative =
                    archive
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("jni/") && it.name.endsWith(".so") }
                        .map { it.name.removePrefix("jni/") }
                        .toSet()
                check(actualNative == expectedNative.keys) {
                    "Unexpected Android ASS native matrix: expected=${expectedNative.keys}, actual=$actualNative"
                }
                check(
                    actualNative.all { path -> path.endsWith("/libkmediaass.so") },
                ) {
                    "The Android ASS artifact must contain no private libass, FriBidi, or libc++ runtime."
                }
                expectedNative.forEach { (path, expectedHash) ->
                    val entry = checkNotNull(archive.getEntry("jni/$path")) { "Missing jni/$path" }
                    val actualHash = archive.getInputStream(entry).use { it.sha256() }
                    check(actualHash == expectedHash) { "SHA-256 mismatch for jni/$path" }
                }

                val proguard =
                    checkNotNull(archive.getEntry("proguard.txt")) {
                        "The Android ASS AAR does not publish JNI consumer rules."
                    }.let(archive::getInputStream).bufferedReader().use { it.readText() }
                check("AndroidAssNativeBridge" in proguard && "native <methods>" in proguard) {
                    "The Android ASS AAR does not keep its registered JNI bridge."
                }
                val classesEntry = checkNotNull(archive.getEntry("classes.jar")) { "AAR has no classes.jar" }
                val classesBytes = archive.getInputStream(classesEntry).use { input -> input.readBytes() }
                val forbiddenLegalResources = mutableListOf<String>()
                ZipInputStream(ByteArrayInputStream(classesBytes)).use { jar ->
                    while (true) {
                        val entry = jar.nextEntry ?: break
                        if (
                            !entry.isDirectory &&
                            entry.name.startsWith("META-INF/kmediaplayer/android-ass/")
                        ) {
                            forbiddenLegalResources += entry.name
                        }
                    }
                }
                check(forbiddenLegalResources.isEmpty()) {
                    "The thin adapter still embeds legal/source material owned by the shared runtime: " +
                        forbiddenLegalResources
                }
            }
            logger.lifecycle("Verified the ARM64/ARMv7 thin ASS bridge AAR.")
        }
    }

tasks.named("check") {
    dependsOn(verifyAndroidAssAar)
}

tasks
    .matching {
        it.name.startsWith("publish", ignoreCase = true) &&
            it.name.contains("ConsumerSmokeRepository", ignoreCase = true)
    }.configureEach {
        dependsOn(verifyAndroidAssAar, verifyJvmDesktopNativeMatrix)
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
        artifactId = "composemediaplayer-ass",
        version = projectVersion,
    )

    pom {
        name.set("Compose Media Player ASS")
        description.set("Optional libass/JASSUB subtitle rendering for Compose Media Player.")
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
    if (!System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey").isNullOrBlank()) {
        signAllPublications()
    }
}

val validateReleaseVersion =
    tasks.register("validateReleaseVersion") {
        group = "verification"
        description = "Rejects mutable or mismatched coordinates before publishing the ASS companion remotely."
        dependsOn(":mediaplayer:validateReleaseVersion")
        inputs.property("releaseVersion", projectVersion)
        inputs.property("releaseGroup", projectGroup)

        doLast {
            val releaseVersion = inputs.properties.getValue("releaseVersion") as String
            val releaseGroup = inputs.properties.getValue("releaseGroup") as String
            val semver =
                Regex(
                    "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                        "(?:-(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)" +
                        "(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*)?" +
                        "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
                )
            check(semver.matches(releaseVersion)) {
                "ASS companion release version '$releaseVersion' must be immutable SemVer."
            }
            check(releaseVersion.substringBefore('.').toInt() >= 2) {
                "The ASS companion belongs to KMediaPlayer 2.x and cannot be published as $releaseVersion."
            }
            check(releaseGroup == "io.github.shusek") {
                "ASS companion release group must remain 'io.github.shusek', but was '$releaseGroup'."
            }
        }
    }

tasks.configureEach {
    val targetsRemoteRepository =
        name.contains("MavenCentral", ignoreCase = true) ||
            name.contains("ReleaseStaging", ignoreCase = true)
    if (name.startsWith("publish", ignoreCase = true) && targetsRemoteRepository) {
        dependsOn(validateReleaseVersion, verifyAndroidAssAar, verifyJvmDesktopNativeMatrix)
    }
}
