@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.multiplatform).apply(false)
    alias(libs.plugins.android.library).apply(false)
    alias(libs.plugins.android.multiplatform.library).apply(false)
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.kotlinCocoapods).apply(false)
    alias(libs.plugins.dokka).apply(false)
    alias(libs.plugins.vannitktech.maven.publish).apply(false)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

abstract class VerifyBackendModuleBoundaries : DefaultTask() {
    @get:Input
    abstract val coreDependencies: SetProperty<String>

    @get:Input
    abstract val extensionApiDependencies: SetProperty<String>

    @get:Input
    abstract val desktopTaoDependencies: SetProperty<String>

    @get:Input
    abstract val defaultPlayerDependencies: SetProperty<String>

    @get:Input
    abstract val defaultPlayerExternalDependencies: SetProperty<String>

    @get:Input
    abstract val mpvBackendDependencies: SetProperty<String>

    @get:Input
    abstract val libVlcBackendDependencies: SetProperty<String>

    @get:Input
    abstract val optionalExtensionDependencies: SetProperty<String>

    @TaskAction
    fun verifyBoundaries() {
        val core = coreDependencies.get()
        val extensionApi = extensionApiDependencies.get()
        val desktopTao = desktopTaoDependencies.get()
        val defaultPlayer = defaultPlayerDependencies.get()
        val defaultPlayerExternal = defaultPlayerExternalDependencies.get()
        val mpvBackend = mpvBackendDependencies.get()
        val libVlcBackend = libVlcBackendDependencies.get()
        val optionalExtensions = optionalExtensionDependencies.get()

        check(":mediaplayer-core" in defaultPlayer) {
            "The default player must consume the backend-neutral :mediaplayer-core contracts."
        }
        check(":mediaplayer-extension-api" in defaultPlayer) {
            "The default player must consume the lightweight :mediaplayer-extension-api contracts."
        }
        check(":mediaplayer-core" in extensionApi) {
            "The extension API must consume the backend-neutral :mediaplayer-core contracts."
        }
        check(":mediaplayer-core" in desktopTao) {
            "The desktop Tao API must consume the backend-neutral :mediaplayer-core contracts."
        }
        check(":mediaplayer-desktop-tao" in defaultPlayer) {
            "The default JVM player must implement the Tao playback SPI."
        }
        check(":mediaplayer-desktop-tao" in mpvBackend) {
            "The MPV JVM backend must implement the Tao playback SPI."
        }
        check(":mediaplayer-core" in mpvBackend) {
            "The MPV backend must consume the backend-neutral :mediaplayer-core contracts."
        }
        check(":mediaplayer-desktop-tao" in libVlcBackend) {
            "The libVLC JVM backend must implement the Tao playback SPI."
        }
        check(":mediaplayer-core" in libVlcBackend) {
            "The libVLC backend must consume the backend-neutral :mediaplayer-core contracts."
        }
        check(":mediaplayer-mpv" !in defaultPlayer) {
            ":mediaplayer must not depend on the optional :mediaplayer-mpv implementation."
        }
        check(":mediaplayer-libvlc" !in defaultPlayer) {
            ":mediaplayer must not depend on the optional :mediaplayer-libvlc implementation."
        }
        check(":mediaplayer-ads-core" !in defaultPlayer) {
            ":mediaplayer must not depend on the optional :mediaplayer-ads-core contracts."
        }
        check(defaultPlayerExternal.none { coordinate -> coordinate.startsWith("io.github.shusek:kmedia-bridge-") }) {
            ":mediaplayer must not pull KMediaBridge or FFmpeg; configure :mediaplayer-kmediabridge explicitly."
        }
        check(":mediaplayer" !in mpvBackend) {
            ":mediaplayer-mpv must not depend on the default :mediaplayer implementation."
        }
        check(":mediaplayer" !in libVlcBackend) {
            ":mediaplayer-libvlc must not depend on the default :mediaplayer implementation."
        }
        check(core.none { it == ":mediaplayer" || it == ":mediaplayer-mpv" || it == ":mediaplayer-libvlc" }) {
            ":mediaplayer-core must not depend on a player implementation."
        }
        check(extensionApi.none { it == ":mediaplayer" || it == ":mediaplayer-mpv" || it == ":mediaplayer-libvlc" }) {
            ":mediaplayer-extension-api must not depend on a player implementation."
        }
        check(desktopTao.none { it == ":mediaplayer" || it == ":mediaplayer-mpv" || it == ":mediaplayer-libvlc" }) {
            ":mediaplayer-desktop-tao must not depend on a player implementation."
        }
        check(optionalExtensions.none { edge -> edge.endsWith("->:mediaplayer") }) {
            "Optional pipeline extensions must depend on :mediaplayer-extension-api, not on the default player."
        }
        setOf(
            ":mediaplayer-ass",
            ":mediaplayer-dolbyvision",
            ":mediaplayer-kmediabridge",
        ).forEach { extensionPath ->
            check("$extensionPath->:mediaplayer-extension-api" in optionalExtensions) {
                "$extensionPath must consume :mediaplayer-extension-api."
            }
        }
    }
}

rootProject.plugins.withType<YarnPlugin> {
    rootProject.extensions.configure<YarnRootEnvSpec> {
        // Yarn distributions are resolved through the restricted repository declared in settings.
        downloadBaseUrl.set(null as String?)
    }
    rootProject.extensions.configure<YarnRootExtension> {
        yarnLockMismatchReport = YarnLockMismatchReport.FAIL
        reportNewYarnLock = true
        yarnLockAutoReplace = false
        ignoreScripts = true
    }
}

rootProject.plugins.withType<WasmYarnPlugin> {
    rootProject.extensions.configure<WasmYarnRootEnvSpec> {
        // Yarn distributions are resolved through the restricted repository declared in settings.
        downloadBaseUrl.set(null as String?)
    }
    rootProject.extensions.configure<WasmYarnRootExtension> {
        yarnLockMismatchReport = YarnLockMismatchReport.FAIL
        reportNewYarnLock = true
        yarnLockAutoReplace = false
        ignoreScripts = true
        // karma-webkit-launcher still permits only uuid 10.x, which contains a
        // bounds-check vulnerability. Its launcher usage is compatible with uuid 11.
        resolution("uuid", "11.1.1")
    }
}

rootProject.plugins.withType<WasmNodeJsRootPlugin> {
    @Suppress("DEPRECATION_ERROR")
    rootProject.extensions.configure<WasmNodeJsRootExtension> {
        // Node distributions are resolved through the restricted repository declared in settings.
        downloadBaseUrl = null
    }
}

tasks.register("consumerSmokeTest") {
    group = "verification"
    description = "Compiles and runs a Java 25 consumer against locally published Maven artifacts."
    dependsOn(
        ":consumer-smoke:compileAndroidMain",
        ":consumer-smoke:jvmTest",
        ":consumer-smoke-extensions:compileAndroidMain",
        ":consumer-smoke-extensions:jvmTest",
        ":consumer-smoke-mpv:compileAndroidMain",
        ":consumer-smoke-mpv:jvmTest",
    )
}

tasks.register("publishConsumerSmokeArtifacts") {
    group = "verification"
    description = "Publishes the KMP, JVM and Android variants used by the isolated consumer smoke project."
    dependsOn(
        ":mediaplayer-core:publishKotlinMultiplatformPublicationToConsumerSmokeRepository",
        ":mediaplayer-core:publishJvmPublicationToConsumerSmokeRepository",
        ":mediaplayer-core:publishAndroidPublicationToConsumerSmokeRepository",
        ":mediaplayer-desktop-tao:publishKotlinMultiplatformPublicationToConsumerSmokeRepository",
        ":mediaplayer-desktop-tao:publishJvmPublicationToConsumerSmokeRepository",
        ":mediaplayer-extension-api:publishKotlinMultiplatformPublicationToConsumerSmokeRepository",
        ":mediaplayer-extension-api:publishJvmPublicationToConsumerSmokeRepository",
        ":mediaplayer-extension-api:publishAndroidPublicationToConsumerSmokeRepository",
        ":mediaplayer-ads-core:publishKotlinMultiplatformPublicationToConsumerSmokeRepository",
        ":mediaplayer-ads-core:publishJvmPublicationToConsumerSmokeRepository",
        ":mediaplayer-ads-core:publishAndroidPublicationToConsumerSmokeRepository",
        ":mediaplayer:publishKotlinMultiplatformPublicationToConsumerSmokeRepository",
        ":mediaplayer:publishJvmPublicationToConsumerSmokeRepository",
        ":mediaplayer:publishAndroidPublicationToConsumerSmokeRepository",
        ":mediaplayer-ass:publishKotlinMultiplatformPublicationToConsumerSmokeRepository",
        ":mediaplayer-ass:publishJvmPublicationToConsumerSmokeRepository",
        ":mediaplayer-ass:publishAndroidPublicationToConsumerSmokeRepository",
        ":mediaplayer-ass:verifyAndroidAssAar",
        ":mediaplayer-dolbyvision:publishKotlinMultiplatformPublicationToConsumerSmokeRepository",
        ":mediaplayer-dolbyvision:publishJvmPublicationToConsumerSmokeRepository",
        ":mediaplayer-dolbyvision:publishAndroidPublicationToConsumerSmokeRepository",
        ":mediaplayer-kmediabridge:publishKotlinMultiplatformPublicationToConsumerSmokeRepository",
        ":mediaplayer-kmediabridge:publishAndroidPublicationToConsumerSmokeRepository",
        ":mediaplayer-kmediabridge:publishJvmPublicationToConsumerSmokeRepository",
        ":mediaplayer-mpv:publishKotlinMultiplatformPublicationToConsumerSmokeRepository",
        ":mediaplayer-mpv:publishJvmPublicationToConsumerSmokeRepository",
        ":mediaplayer-mpv:publishAndroidPublicationToConsumerSmokeRepository",
    )
}

tasks.register("publishLibVlcConsumerSmokeArtifacts") {
    group = "verification"
    description = "Publishes the minimal desktop and Android graph for the isolated libVLC consumer smoke test."
    dependsOn(
        ":mediaplayer-core:publishKotlinMultiplatformPublicationToConsumerSmokeRepository",
        ":mediaplayer-core:publishJvmPublicationToConsumerSmokeRepository",
        ":mediaplayer-core:publishAndroidPublicationToConsumerSmokeRepository",
        ":mediaplayer-desktop-tao:publishKotlinMultiplatformPublicationToConsumerSmokeRepository",
        ":mediaplayer-desktop-tao:publishJvmPublicationToConsumerSmokeRepository",
        ":mediaplayer-libvlc:publishKotlinMultiplatformPublicationToConsumerSmokeRepository",
        ":mediaplayer-libvlc:publishJvmPublicationToConsumerSmokeRepository",
        ":mediaplayer-libvlc:publishAndroidPublicationToConsumerSmokeRepository",
    )
}

val proprietaryPublicationProjects =
    listOf(
        project(":mediaplayer"),
        project(":mediaplayer-core"),
        project(":mediaplayer-ads-core"),
        project(":mediaplayer-ass"),
        project(":mediaplayer-desktop-tao"),
        project(":mediaplayer-dolbyvision"),
        project(":mediaplayer-extension-api"),
        project(":mediaplayer-kmediabridge"),
        project(":mediaplayer-libvlc"),
        project(":mediaplayer-mpv"),
    )

val publicationLegalDirectory = rootProject.layout.projectDirectory.dir("legal/publication")
val publicationLicense = publicationLegalDirectory.file("META-INF/LICENSE")
val publicationNotice = publicationLegalDirectory.file("META-INF/NOTICE")
val upstreamComposeMediaPlayerLicense =
    publicationLegalDirectory.file("META-INF/LICENSES/UPSTREAM-COMPOSE-MEDIA-PLAYER-MIT.txt")

proprietaryPublicationProjects.forEach { publicationProject ->
    publicationProject.plugins.withId("org.jetbrains.kotlin.multiplatform") {
        publicationProject.extensions.configure(KotlinMultiplatformExtension::class.java) {
            sourceSets.named("commonMain") {
                resources.srcDir(publicationLegalDirectory)
            }
        }
    }
}

proprietaryPublicationProjects.forEach { publicationProject ->
    publicationProject.tasks
        .withType(Jar::class.java)
        .matching { task -> task.name.endsWith("EmptySourcesJar") }
        .configureEach {
            val publicationName = name.removeSuffix("EmptySourcesJar").ifBlank { "root" }
            archiveBaseName.set("${publicationProject.name}-${publicationName}-empty")
        }
}

val verifyClosedSourcePublications =
    tasks.register("verifyClosedSourcePublications") {
        group = "verification"
        description = "Rejects Maven publications that expose proprietary KMediaPlayer sources."
        val emptySourceJarTasks =
            proprietaryPublicationProjects.map { publicationProject ->
                publicationProject.tasks.matching { task -> task.name.endsWith("EmptySourcesJar") }
            }
        dependsOn(emptySourceJarTasks)
        inputs.files(
            rootProject.layout.projectDirectory.file("LICENSE"),
            publicationLicense,
            publicationNotice,
            upstreamComposeMediaPlayerLicense,
        )
        doLast {
            check(publicationLicense.asFile.readText() == rootProject.file("LICENSE").readText()) {
                "The embedded proprietary license differs from the repository LICENSE."
            }
            check(publicationNotice.asFile.isFile) {
                "The proprietary KMediaPlayer NOTICE is missing."
            }
            val upstreamLicenseText = upstreamComposeMediaPlayerLicense.asFile.readText()
            check("MIT License" in upstreamLicenseText && "Copyright (c) 2025 Elie G." in upstreamLicenseText) {
                "The inherited Compose Media Player MIT notice is missing or incomplete."
            }
            val forbiddenExtensions =
                setOf("kt", "kts", "java", "js", "mjs", "ts", "c", "cc", "cpp", "h", "hpp")
            proprietaryPublicationProjects.forEach { publicationProject ->
                val placeholderJars =
                    publicationProject.tasks
                        .matching { task -> task.name.endsWith("EmptySourcesJar") }
                        .flatMap { task -> task.outputs.files.files }
                        .filter { file -> file.isFile && file.extension == "jar" }
                        .map { file -> file.canonicalFile }
                        .toSet()
                check(placeholderJars.isNotEmpty()) {
                    "No source JAR placeholders were produced for ${publicationProject.path}."
                }
                placeholderJars.forEach { sourceJar ->
                    ZipFile(sourceJar).use { zip ->
                        val leaked =
                            zip.entries().asSequence().firstOrNull { entry ->
                                !entry.isDirectory &&
                                    entry.name.substringAfterLast('.', "").lowercase() in forbiddenExtensions
                            }
                        check(leaked == null) {
                            "Proprietary source leaked through ${sourceJar.name}: ${leaked?.name}"
                        }
                    }
                }
                val publishing =
                    publicationProject.extensions.findByType(PublishingExtension::class.java)
                        ?: error("Missing publishing extension for ${publicationProject.path}.")
                val publishedSourceArtifacts =
                    publishing.publications
                        .withType(MavenPublication::class.java)
                        .flatMap { publication -> publication.artifacts }
                        .filter { artifact -> artifact.classifier == "sources" }
                check(publishedSourceArtifacts.isNotEmpty()) {
                    "No source JAR placeholders are attached to ${publicationProject.path} publications."
                }
                val leakedPublicationArtifact =
                    publishedSourceArtifacts.firstOrNull { artifact ->
                        artifact.file.canonicalFile !in placeholderJars
                    }
                check(leakedPublicationArtifact == null) {
                    "${publicationProject.path} exposes a non-placeholder source JAR: " +
                        "${leakedPublicationArtifact?.file}"
                }
            }
        }
    }

tasks.matching { task -> task.name == "check" }.configureEach {
    dependsOn(verifyClosedSourcePublications)
}

tasks.named("publishConsumerSmokeArtifacts") {
    dependsOn(verifyClosedSourcePublications)
}

tasks.named("publishLibVlcConsumerSmokeArtifacts") {
    dependsOn(verifyClosedSourcePublications)
}

proprietaryPublicationProjects.forEach { publicationProject ->
    publicationProject.tasks.configureEach {
        val publishesRemoteRelease =
            name.contains("MavenCentral", ignoreCase = true) ||
                name.contains("ReleaseStaging", ignoreCase = true) ||
                name == "publishAndReleaseToMavenCentral"
        if (publishesRemoteRelease) {
            dependsOn(verifyClosedSourcePublications)
        }
    }
}

tasks.register("libVlcConsumerSmokeTest") {
    group = "verification"
    description = "Compiles Android and runs JVM consumers against locally published libVLC adapter artifacts."
    dependsOn(
        ":consumer-smoke-libvlc:compileAndroidMain",
        ":consumer-smoke-libvlc:jvmTest",
    )
}

val verifyBackendModuleBoundaries =
    tasks.register<VerifyBackendModuleBoundaries>("verifyBackendModuleBoundaries") {
        group = "verification"
        description = "Rejects dependencies that couple the default player and optional backend implementations."
    }

gradle.projectsEvaluated {
    // Integration tests intentionally compose multiple implementations; enforce boundaries only on shipped graphs.
    fun Project.productionConfigurations() =
        configurations.filterNot { configuration ->
            configuration.name.contains("test", ignoreCase = true)
        }

    fun Project.productionProjectDependencyPaths(): Set<String> =
        productionConfigurations()
            .flatMap { configuration ->
                configuration.dependencies
                    .withType(ProjectDependency::class.java)
                    .map(ProjectDependency::getPath)
            }.toSet()

    fun Project.productionExternalDependencyCoordinates(): Set<String> =
        productionConfigurations()
            .flatMap { configuration ->
                configuration.dependencies.mapNotNull { dependency ->
                    dependency.group
                        ?.takeUnless(String::isBlank)
                        ?.let { group -> "$group:${dependency.name}" }
                }
            }.toSet()

    verifyBackendModuleBoundaries.configure {
        coreDependencies.set(project(":mediaplayer-core").productionProjectDependencyPaths())
        extensionApiDependencies.set(project(":mediaplayer-extension-api").productionProjectDependencyPaths())
        desktopTaoDependencies.set(project(":mediaplayer-desktop-tao").productionProjectDependencyPaths())
        defaultPlayerDependencies.set(project(":mediaplayer").productionProjectDependencyPaths())
        defaultPlayerExternalDependencies.set(project(":mediaplayer").productionExternalDependencyCoordinates())
        mpvBackendDependencies.set(project(":mediaplayer-mpv").productionProjectDependencyPaths())
        libVlcBackendDependencies.set(project(":mediaplayer-libvlc").productionProjectDependencyPaths())
        optionalExtensionDependencies.set(
            setOf(
                project(":mediaplayer-ass"),
                project(":mediaplayer-ads-core"),
                project(":mediaplayer-dolbyvision"),
                project(":mediaplayer-kmediabridge"),
            ).flatMap { extensionProject ->
                extensionProject
                    .productionProjectDependencyPaths()
                    .map { dependencyPath -> "${extensionProject.path}->$dependencyPath" }
            }.toSet(),
        )
    }
}

// Code quality
detekt {
    config.setFrom(files("config/detekt/detekt.yml"))
    // KMP detekt tasks derive baseline-<sourceSet>.xml from this base path.
    baseline = file("config/detekt/baseline.xml")
    buildUponDefaultConfig = true
}

ktlint {
    baseline.set(file("config/ktlint/baseline.xml"))
    ignoreFailures.set(false)
}

subprojects {
    plugins.withType<BinaryenPlugin> {
        extensions.configure<BinaryenEnvSpec> {
            // Binaryen distributions are resolved through the restricted repository declared in settings.
            downloadBaseUrl.set(null as String?)
        }
    }

    if (name == "composeApp") return@subprojects
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    ktlint {
        debug.set(false)
        verbose.set(true)
        android.set(false)
        outputToConsole.set(true)
        baseline.set(rootProject.file("config/ktlint/baseline.xml"))
        ignoreFailures.set(false)
        enableExperimentalRules.set(true)
        filter {
            exclude("**/generated/**")
            include("**/kotlin/**")
        }
    }
}
