@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import dev.detekt.gradle.Detekt
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

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
        ?: System
            .getenv("GITHUB_REF")
            ?.takeIf { it.startsWith("refs/tags/") }
            ?.removePrefix("refs/tags/")
            ?.removePrefix("v")
        ?: "dev"
val projectGroup = "io.github.shusek"
val githubPagesMavenRepository = providers.gradleProperty("githubPagesMavenRepository").orNull
val appleNativeDirectory = layout.projectDirectory.dir("native/apple")
val desktopNativeDirectory = layout.projectDirectory.dir("native/desktop")
val generatedAppleJvmResources = layout.buildDirectory.dir("generated/appleAssJvmResources")
val generatedDesktopJvmResources = layout.buildDirectory.dir("generated/desktopAssJvmResources")
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
val skipDesktopNativeBuild =
    providers
        .gradleProperty("composeMediaPlayer.skipAssDesktopNativeBuild")
        .orElse(providers.gradleProperty("composeMediaPlayer.skipNativeBuild"))
        .orElse(providers.environmentVariable("COMPOSE_MEDIA_PLAYER_SKIP_NATIVE_BUILD"))
        .map { it.equals("true", ignoreCase = true) }
        .getOrElse(false)
val canBuildWindowsDesktopNative = Os.isFamily(Os.FAMILY_WINDOWS) && !skipDesktopNativeBuild
val linuxDesktopArchitecture =
    when (System.getProperty("os.arch", "").lowercase()) {
        "amd64", "x86_64", "x86-64", "x64" -> "x86-64"
        "aarch64", "arm64" -> "aarch64"
        else -> null
    }
val canBuildLinuxDesktopNative =
    Os.isFamily(Os.FAMILY_UNIX) &&
        !Os.isFamily(Os.FAMILY_MAC) &&
        linuxDesktopArchitecture != null &&
        !skipDesktopNativeBuild
val cleanUnsupportedAppleAssOutputs =
    tasks.register<Delete>("cleanUnsupportedAppleAssOutputs") {
        delete(
            generatedAppleJvmResources.map {
                it.dir("composemediaplayer/ass/native/darwin-x86-64")
            },
        )
    }

fun registerAppleAssBuild(
    taskName: String,
    target: String,
    outputDirectory: Provider<Directory>,
) = tasks.register<Exec>(taskName) {
    description = "Builds the pinned bundled ASS runtime for $target."
    group = "build"
    enabled = canBuildAppleNative
    dependsOn(cleanUnsupportedAppleAssOutputs)
    workingDir(layout.projectDirectory)
    commandLine(
        "bash",
        appleNativeDirectory.file("build.sh").asFile.absolutePath,
        target,
        outputDirectory.get().asFile.absolutePath,
    )
    inputs.file(appleNativeDirectory.file("build.sh"))
    inputs.file(appleNativeDirectory.file("KMediaAssRenderer.c"))
    inputs.file(appleNativeDirectory.file("AppleAssJni.c"))
    inputs.file(appleNativeDirectory.file("include/KMediaAssRenderer.h"))
    inputs.file(appleNativeDirectory.file("macos.exports"))
    inputs.dir(layout.projectDirectory.dir("native/common"))
    inputs.file(
        layout.projectDirectory.file(
            "src/androidMain/native/ass/corresponding-source/fribidi-1.0.16-source.tar.xz",
        ),
    )
    outputs.dir(outputDirectory)
}

val macosArm64AppleAssOutput =
    generatedAppleJvmResources.map {
        it.dir("composemediaplayer/ass/native/darwin-aarch64")
    }
val buildMacosArm64AppleAss =
    registerAppleAssBuild(
        taskName = "buildMacosArm64AppleAss",
        target = "macos-arm64",
        outputDirectory = macosArm64AppleAssOutput,
    )
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
val packageAppleAssLegalResources =
    tasks.register<Copy>("packageAppleAssLegalResources") {
        description = "Packages Apple ASS notices, exact FriBidi source and relink instructions."
        group = "build"
        val legalRoot =
            generatedAppleJvmResources.map {
                it.dir("META-INF/kmediaplayer/apple-ass")
            }
        into(legalRoot)
        from(appleNativeDirectory.file("NOTICE.md"))
        from(appleNativeDirectory.file("BUILD.md")) {
            into("corresponding-source")
        }
        from(appleNativeDirectory.file("LGPL-RELINK.md")) {
            into("corresponding-source")
        }
        from(appleNativeDirectory.file("build.sh")) {
            into("corresponding-source")
        }
        from(
            layout.projectDirectory.file(
                "src/androidMain/native/ass/corresponding-source/fribidi-1.0.16-source.tar.xz",
            ),
        ) {
            into("corresponding-source")
        }
        from(
            layout.projectDirectory.dir(
                "src/androidMain/resources/META-INF/kmediaplayer/android-ass/LICENSES",
            ),
        ) {
            include(
                "freetype-FTL.txt",
                "freetype-license-selection.txt",
                "fribidi-LGPL-2.1-or-later.txt",
                "harfbuzz.txt",
                "libass-ISC.txt",
                "libunibreak-zlib.txt",
            )
            into("LICENSES")
        }
    }
val appleAssLgplMaterials =
    tasks.register<Zip>("appleAssLgplMaterials") {
        description = "Packages the Apple FriBidi source, notices and relinking material."
        group = "distribution"
        archiveClassifier.set("apple-lgpl-materials")
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false

        from(appleNativeDirectory.file("NOTICE.md"))
        from(appleNativeDirectory.file("BUILD.md")) {
            into("corresponding-source")
        }
        from(appleNativeDirectory.file("LGPL-RELINK.md")) {
            into("corresponding-source")
        }
        from(appleNativeDirectory.file("build.sh")) {
            into("corresponding-source")
        }
        from(
            layout.projectDirectory.file(
                "src/androidMain/native/ass/corresponding-source/fribidi-1.0.16-source.tar.xz",
            ),
        ) {
            into("corresponding-source")
        }
        from(
            layout.projectDirectory.dir(
                "src/androidMain/resources/META-INF/kmediaplayer/android-ass/LICENSES",
            ),
        ) {
            include(
                "freetype-FTL.txt",
                "freetype-license-selection.txt",
                "fribidi-LGPL-2.1-or-later.txt",
                "harfbuzz.txt",
                "libass-ISC.txt",
                "libunibreak-zlib.txt",
            )
            into("LICENSES")
        }
    }

val windowsDesktopAssOutputs =
    listOf("windows-x86-64", "windows-aarch64").map { platform ->
        generatedDesktopJvmResources.map {
            it.dir("composemediaplayer/ass/native/$platform")
        }
    }
val buildWindowsDesktopAss =
    tasks.register<Exec>("buildWindowsDesktopAss") {
        description = "Builds bundled x86_64 and ARM64 Windows libass runtimes."
        group = "build"
        enabled = canBuildWindowsDesktopNative
        workingDir(layout.projectDirectory)
        commandLine(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            desktopNativeDirectory.file("build-windows.ps1").asFile.absolutePath,
            "-OutputRoot",
            generatedDesktopJvmResources.get().asFile.absolutePath,
        )
        inputs.file(desktopNativeDirectory.file("build-windows.ps1"))
        inputs.dir(desktopNativeDirectory.dir("vcpkg-fribidi-port"))
        windowsDesktopAssOutputs.forEach(outputs::dir)
    }
val linuxDesktopAssOutput =
    generatedDesktopJvmResources.map {
        it.dir(
            "composemediaplayer/ass/native/" +
                "linux-${linuxDesktopArchitecture ?: "unsupported"}",
        )
    }
val buildLinuxDesktopAss =
    tasks.register<Exec>("buildLinuxDesktopAss") {
        description = "Builds the bundled libass runtime for this Linux host architecture."
        group = "build"
        enabled = canBuildLinuxDesktopNative
        workingDir(layout.projectDirectory)
        commandLine(
            "bash",
            desktopNativeDirectory.file("build-linux.sh").asFile.absolutePath,
            "linux-${linuxDesktopArchitecture ?: "unsupported"}",
            generatedDesktopJvmResources.get().asFile.absolutePath,
        )
        inputs.file(desktopNativeDirectory.file("build-linux.sh"))
        inputs.file(
            layout.projectDirectory.file(
                "src/androidMain/native/ass/corresponding-source/fribidi-1.0.16-source.tar.xz",
            ),
        )
        outputs.dir(linuxDesktopAssOutput)
    }
val packageDesktopAssLegalResources =
    tasks.register<Copy>("packageDesktopAssLegalResources") {
        description = "Packages desktop ASS notices, FriBidi source and replacement instructions."
        group = "build"
        val legalRoot =
            generatedDesktopJvmResources.map {
                it.dir("META-INF/kmediaplayer/desktop-ass")
            }
        into(legalRoot)
        from(desktopNativeDirectory.file("NOTICE.md"))
        from(desktopNativeDirectory.file("BUILD.md")) {
            into("corresponding-source")
        }
        from(desktopNativeDirectory.file("LGPL-RELINK.md")) {
            into("corresponding-source")
        }
        from(desktopNativeDirectory.file("build-windows.ps1")) {
            into("corresponding-source")
        }
        from(desktopNativeDirectory.file("build-linux.sh")) {
            into("corresponding-source")
        }
        from(desktopNativeDirectory.dir("vcpkg-fribidi-port")) {
            into("corresponding-source/vcpkg-fribidi-port")
        }
        from(
            layout.projectDirectory.file(
                "src/androidMain/native/ass/corresponding-source/fribidi-1.0.16-source.tar.xz",
            ),
        ) {
            into("corresponding-source")
        }
        from(
            layout.projectDirectory.dir(
                "src/androidMain/resources/META-INF/kmediaplayer/android-ass/LICENSES",
            ),
        ) {
            include(
                "freetype-FTL.txt",
                "freetype-license-selection.txt",
                "fribidi-LGPL-2.1-or-later.txt",
                "harfbuzz.txt",
                "libass-ISC.txt",
                "libunibreak-zlib.txt",
            )
            into("LICENSES")
        }
    }
val desktopAssLgplMaterials =
    tasks.register<Zip>("desktopAssLgplMaterials") {
        description = "Packages desktop FriBidi source, notices and replacement material."
        group = "distribution"
        dependsOn(packageDesktopAssLegalResources)
        archiveClassifier.set("desktop-lgpl-materials")
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false

        from(desktopNativeDirectory.file("NOTICE.md"))
        from(desktopNativeDirectory.file("BUILD.md")) {
            into("corresponding-source")
        }
        from(desktopNativeDirectory.file("LGPL-RELINK.md")) {
            into("corresponding-source")
        }
        from(desktopNativeDirectory.file("build-windows.ps1")) {
            into("corresponding-source")
        }
        from(desktopNativeDirectory.file("build-linux.sh")) {
            into("corresponding-source")
        }
        from(desktopNativeDirectory.dir("vcpkg-fribidi-port")) {
            into("corresponding-source/vcpkg-fribidi-port")
        }
        from(
            layout.projectDirectory.file(
                "src/androidMain/native/ass/corresponding-source/fribidi-1.0.16-source.tar.xz",
            ),
        ) {
            into("corresponding-source")
        }
        from(
            layout.projectDirectory.dir(
                "src/androidMain/resources/META-INF/kmediaplayer/android-ass/LICENSES",
            ),
        ) {
            include(
                "freetype-FTL.txt",
                "freetype-license-selection.txt",
                "fribidi-LGPL-2.1-or-later.txt",
                "harfbuzz.txt",
                "libass-ISC.txt",
                "libunibreak-zlib.txt",
            )
            into("LICENSES")
        }
        from(
            generatedDesktopJvmResources.map {
                it.dir("META-INF/kmediaplayer/desktop-ass/LICENSES/vcpkg")
            },
        ) {
            into("LICENSES/vcpkg")
        }
    }

group = projectGroup
version = projectVersion

kotlin {
    jvmToolchain(25)

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
    }

    listOf(
        Triple(iosArm64(), iosArm64AppleNative, buildIosArm64AppleAss),
        Triple(
            iosSimulatorArm64(),
            iosSimulatorArm64AppleNative,
            buildIosSimulatorArm64AppleAss,
        ),
    ).forEach { (target, nativeOutput, _) ->
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
            resources.srcDir(generatedAppleJvmResources)
            resources.srcDir(generatedDesktopJvmResources)
            resources.exclude("composemediaplayer/ass/native/darwin-x86-64/**")
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
            implementation(npm("jassub", "2.5.1"))
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.named("jvmProcessResources") {
    dependsOn(
        cleanUnsupportedAppleAssOutputs,
        buildMacosArm64AppleAss,
        buildWindowsDesktopAss,
        buildLinuxDesktopAss,
        packageAppleAssLegalResources,
        packageDesktopAssLegalResources,
    )
}
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val verifyJvmMacArmNativeMatrix =
    tasks.register("verifyJvmMacArmNativeMatrix") {
        group = "verification"
        description = "Verifies that the ASS JVM JAR publishes macOS arm64 only."

        val jvmJar = tasks.named<Jar>("jvmJar")
        dependsOn(jvmJar)
        val archiveFile = jvmJar.flatMap { it.archiveFile }
        inputs.file(archiveFile)

        doLast {
            ZipFile(inputs.files.singleFile).use { archive ->
                val armPrefix = "composemediaplayer/ass/native/darwin-aarch64/"
                check(archive.getEntry("${armPrefix}libcomposemediaplayer_ass.dylib") != null) {
                    "The ASS JVM JAR is missing its macOS arm64 renderer."
                }
                check(archive.getEntry("${armPrefix}libkmediafribidi.dylib") != null) {
                    "The ASS JVM JAR is missing its replaceable macOS arm64 FriBidi library."
                }
                val intelEntries =
                    archive
                        .entries()
                        .asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                entry.name.startsWith("composemediaplayer/ass/native/darwin-x86-64/")
                        }.map { it.name }
                        .toList()
                check(intelEntries.isEmpty()) {
                    "The ASS JVM JAR contains unsupported Intel macOS payloads: $intelEntries"
                }
            }
        }
    }

val verifyJvmDesktopNativeMatrix =
    tasks.register("verifyJvmDesktopNativeMatrix") {
        group = "verification"
        description = "Verifies bundled Windows/Linux libass payloads and their SHA-256 manifests."

        val jvmJar = tasks.named<Jar>("jvmJar")
        dependsOn(jvmJar)
        val archiveFile = jvmJar.flatMap { it.archiveFile }
        inputs.file(archiveFile)

        doLast {
            fun java.io.InputStream.sha256(): String {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                return digest.digest().joinToString("") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                }
            }

            val expectedPlatforms =
                setOf(
                    "windows-x86-64",
                    "windows-aarch64",
                    "linux-x86-64",
                    "linux-aarch64",
                )
            val resourceRoot = "composemediaplayer/ass/native/"
            ZipFile(inputs.files.singleFile).use { archive ->
                val actualPlatforms =
                    archive
                        .entries()
                        .asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                entry.name.startsWith(resourceRoot) &&
                                (
                                    entry.name.removePrefix(resourceRoot).startsWith("windows-") ||
                                        entry.name.removePrefix(resourceRoot).startsWith("linux-")
                                )
                        }.map { entry ->
                            entry.name.removePrefix(resourceRoot).substringBefore('/')
                        }.toSet()
                check(actualPlatforms == expectedPlatforms) {
                    "Unexpected desktop ASS native matrix: expected=$expectedPlatforms, actual=$actualPlatforms"
                }

                expectedPlatforms.forEach { platform ->
                    val prefix = "$resourceRoot$platform/"
                    val manifestEntry =
                        checkNotNull(archive.getEntry("${prefix}runtime.properties")) {
                            "The ASS JVM JAR is missing $platform/runtime.properties."
                        }
                    val properties =
                        Properties().apply {
                            archive.getInputStream(manifestEntry).use(::load)
                        }
                    val count =
                        properties.getProperty("file.count")?.toIntOrNull()
                            ?: error("The $platform ASS manifest has an invalid file.count.")
                    check(count in 1..64) {
                        "The $platform ASS manifest has an unsafe file count: $count."
                    }
                    val mainLibrary =
                        checkNotNull(properties.getProperty("mainLibrary")) {
                            "The $platform ASS manifest has no mainLibrary."
                        }
                    val fileNames =
                        (0 until count).map { index ->
                            val name =
                                checkNotNull(properties.getProperty("file.$index.name")) {
                                    "The $platform ASS manifest is missing file.$index.name."
                                }
                            check(name == Path.of(name).fileName.toString()) {
                                "The $platform ASS manifest contains an unsafe file name: $name"
                            }
                            val expectedDigest =
                                checkNotNull(properties.getProperty("file.$index.sha256")) {
                                    "The $platform ASS manifest is missing the digest for $name."
                                }
                            check(Regex("[0-9a-f]{64}").matches(expectedDigest)) {
                                "The $platform ASS manifest has an invalid digest for $name."
                            }
                            val entry =
                                checkNotNull(archive.getEntry("$prefix$name")) {
                                    "The ASS JVM JAR is missing $platform/$name."
                                }
                            val actualDigest = archive.getInputStream(entry).use { it.sha256() }
                            check(actualDigest == expectedDigest) {
                                "SHA-256 mismatch for bundled ASS runtime $platform/$name."
                            }
                            name
                        }
                    check(fileNames.distinct().size == fileNames.size && mainLibrary in fileNames) {
                        "The $platform ASS manifest has duplicates or omits its main library."
                    }
                    if (platform.startsWith("linux-")) {
                        check(
                            fileNames ==
                                listOf(
                                    "libass.so.9",
                                    "libkmediafribidi.so.0",
                                ),
                        ) {
                            "Unexpected Linux ASS payload for $platform: $fileNames"
                        }
                    } else {
                        check(mainLibrary.endsWith(".dll") && fileNames.all { it.endsWith(".dll") }) {
                            "Unexpected Windows ASS payload for $platform: $fileNames"
                        }
                    }
                }

                listOf(
                    "META-INF/kmediaplayer/desktop-ass/NOTICE.md",
                    "META-INF/kmediaplayer/desktop-ass/corresponding-source/BUILD.md",
                    "META-INF/kmediaplayer/desktop-ass/corresponding-source/LGPL-RELINK.md",
                    "META-INF/kmediaplayer/desktop-ass/corresponding-source/" +
                        "fribidi-1.0.16-source.tar.xz",
                ).forEach { resource ->
                    check(archive.getEntry(resource) != null) {
                        "The ASS JVM JAR is missing desktop legal resource $resource."
                    }
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
        description = "Verifies the optional Android libass matrix, notices, source and consumer rules."

        dependsOn(tasks.named("bundleAndroidMainAar"), verifyCoreAndroidAarHasNoAssPayload)
        val aar = layout.buildDirectory.file("outputs/aar/${project.name}.aar")
        val checksums = layout.projectDirectory.file("src/androidMain/native/ass/CHECKSUMS.sha256")
        val legalResources =
            layout.projectDirectory.dir("src/androidMain/resources/META-INF/kmediaplayer/android-ass")
        inputs.file(aar)
        inputs.file(checksums)
        inputs.dir(legalResources)

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

            fun java.io.File.sha256(): String = inputStream().buffered().use { input -> input.sha256() }

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
            check(expectedNative.size == androidAbis.size * 3) {
                "Expected three Android ASS libraries for every ABI."
            }
            val sourcePath = "corresponding-source/fribidi-1.0.16-source.tar.xz"
            check(expected.keys == expectedNative.keys + sourcePath) {
                "The Android ASS checksum manifest must contain exactly the native matrix and FriBidi source; " +
                    "actual=${expected.keys}"
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
                    actualNative.none { path ->
                        path.endsWith("/libass.so") ||
                            path.endsWith("/libfribidi.so") ||
                            path.endsWith("/libc++_shared.so")
                    },
                ) {
                    "The Android ASS artifact must not expose generic libass/FriBidi or libc++ SONAMEs."
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

                val resourcePrefix = "META-INF/kmediaplayer/android-ass/"
                val expectedClassResources =
                    legalResources.asFile
                        .walkTopDown()
                        .filter { it.isFile }
                        .associate { resource ->
                            val relativePath =
                                resource
                                    .relativeTo(legalResources.asFile)
                                    .invariantSeparatorsPath
                            "$resourcePrefix$relativePath" to resource.sha256()
                        }
                check(expectedClassResources.isNotEmpty()) {
                    "The Android ASS legal-resource directory is empty."
                }

                val actualClassResources = mutableMapOf<String, String>()
                val classesEntry = checkNotNull(archive.getEntry("classes.jar")) { "AAR has no classes.jar" }
                val classesBytes = archive.getInputStream(classesEntry).use { input -> input.readBytes() }
                ZipInputStream(ByteArrayInputStream(classesBytes)).use { jar ->
                    while (true) {
                        val entry = jar.nextEntry ?: break
                        if (!entry.isDirectory && entry.name.startsWith(resourcePrefix)) {
                            val previous = actualClassResources.put(entry.name, jar.sha256())
                            check(previous == null) { "Duplicate Android ASS class resource: ${entry.name}" }
                        }
                    }
                }
                check(actualClassResources.keys == expectedClassResources.keys) {
                    "Unexpected Android ASS legal-resource set: " +
                        "missing=${expectedClassResources.keys - actualClassResources.keys}, " +
                        "unexpected=${actualClassResources.keys - expectedClassResources.keys}"
                }
                expectedClassResources.forEach { (path, expectedHash) ->
                    check(actualClassResources[path] == expectedHash) {
                        "SHA-256 mismatch for Android ASS class resource $path"
                    }
                }

                val packagedChecksums = "${resourcePrefix}CHECKSUMS.sha256"
                check(actualClassResources[packagedChecksums] == checksums.asFile.sha256()) {
                    "The AAR's packaged checksum manifest differs from the native release manifest."
                }
                val sourceResource = "$resourcePrefix$sourcePath"
                check(actualClassResources[sourceResource] == checkNotNull(expected[sourcePath])) {
                    "The AAR's FriBidi corresponding-source archive does not match its checksum."
                }
            }
            logger.lifecycle("Verified the optional ARM64/ARMv7 Android libass AAR and corresponding source.")
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
    publications
        .withType<MavenPublication>()
        .matching { publication -> publication.name == "kotlinMultiplatform" }
        .configureEach {
            artifact(appleAssLgplMaterials) {
                classifier = "apple-lgpl-materials"
                extension = "zip"
            }
            artifact(desktopAssLgplMaterials) {
                classifier = "desktop-lgpl-materials"
                extension = "zip"
            }
        }

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
        artifactId = "composemediaplayer-ass",
        version = projectVersion,
    )

    pom {
        name.set("Compose Media Player ASS")
        description.set("Optional libass/JASSUB subtitle rendering for Compose Media Player.")
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
                name.set("Internal Use Notice and Limited License")
                url.set("https://github.com/Shusek/KMediaPlayer/blob/master/LICENSE")
                distribution.set("repo")
            }
            license {
                name.set("Bundled FriBidi component: LGPL-2.1-or-later")
                url.set("https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html")
                distribution.set("repo")
            }
            license {
                name.set("Bundled libass component: ISC License")
                url.set("https://github.com/libass/libass/blob/master/COPYING")
                distribution.set("repo")
            }
            license {
                name.set("Bundled FreeType component: FreeType License")
                url.set("https://freetype.org/license.html")
                distribution.set("repo")
            }
            license {
                name.set("Bundled HarfBuzz component: Old MIT License")
                url.set("https://github.com/harfbuzz/harfbuzz/blob/main/COPYING")
                distribution.set("repo")
            }
            license {
                name.set("Bundled libunibreak component: zlib License")
                url.set("https://github.com/adah1972/libunibreak/blob/master/LICENCE")
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
            val stableSemver =
                Regex(
                    "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                        "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
                )
            check(stableSemver.matches(releaseVersion)) {
                "ASS companion release version '$releaseVersion' must be stable major.minor.patch SemVer."
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
            name.contains("GithubPages", ignoreCase = true)
    if (name.startsWith("publish", ignoreCase = true) && targetsRemoteRepository) {
        dependsOn(validateReleaseVersion, verifyAndroidAssAar, verifyJvmDesktopNativeMatrix)
    }
}
