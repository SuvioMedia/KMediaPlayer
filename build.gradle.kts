@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
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
    abstract val defaultPlayerDependencies: SetProperty<String>

    @get:Input
    abstract val mpvBackendDependencies: SetProperty<String>

    @TaskAction
    fun verifyBoundaries() {
        val core = coreDependencies.get()
        val defaultPlayer = defaultPlayerDependencies.get()
        val mpvBackend = mpvBackendDependencies.get()

        check(":mediaplayer-core" in defaultPlayer) {
            "The default player must consume the backend-neutral :mediaplayer-core contracts."
        }
        check(":mediaplayer-core" in mpvBackend) {
            "The MPV backend must consume the backend-neutral :mediaplayer-core contracts."
        }
        check(":mediaplayer-mpv" !in defaultPlayer) {
            ":mediaplayer must not depend on the optional :mediaplayer-mpv implementation."
        }
        check(":mediaplayer" !in mpvBackend) {
            ":mediaplayer-mpv must not depend on the default :mediaplayer implementation."
        }
        check(core.none { it == ":mediaplayer" || it == ":mediaplayer-mpv" }) {
            ":mediaplayer-core must not depend on a player implementation."
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
        ":mediaplayer:publishKotlinMultiplatformPublicationToConsumerSmokeRepository",
        ":mediaplayer:publishJvmPublicationToConsumerSmokeRepository",
        ":mediaplayer:publishAndroidPublicationToConsumerSmokeRepository",
        ":mediaplayer-mpv:publishKotlinMultiplatformPublicationToConsumerSmokeRepository",
        ":mediaplayer-mpv:publishJvmPublicationToConsumerSmokeRepository",
        ":mediaplayer-mpv:publishAndroidPublicationToConsumerSmokeRepository",
    )
}

val verifyBackendModuleBoundaries = tasks.register<VerifyBackendModuleBoundaries>("verifyBackendModuleBoundaries") {
    group = "verification"
    description = "Rejects dependencies that couple the default player and optional backend implementations."
}

gradle.projectsEvaluated {
    fun Project.projectDependencyPaths(): Set<String> =
        configurations
            .flatMap { configuration ->
                configuration.dependencies
                    .withType(ProjectDependency::class.java)
                    .map(ProjectDependency::getPath)
            }.toSet()

    verifyBackendModuleBoundaries.configure {
        coreDependencies.set(project(":mediaplayer-core").projectDependencyPaths())
        defaultPlayerDependencies.set(project(":mediaplayer").projectDependencyPaths())
        mpvBackendDependencies.set(project(":mediaplayer-mpv").projectDependencyPaths())
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
