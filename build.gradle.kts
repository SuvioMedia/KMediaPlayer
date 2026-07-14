@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

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
    )
}

tasks.register("publishConsumerSmokeArtifacts") {
    group = "verification"
    description = "Publishes the KMP, JVM and Android variants used by the isolated consumer smoke project."
    dependsOn(
        ":mediaplayer:publishKotlinMultiplatformPublicationToConsumerSmokeRepository",
        ":mediaplayer:publishJvmPublicationToConsumerSmokeRepository",
        ":mediaplayer:publishAndroidPublicationToConsumerSmokeRepository",
    )
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
