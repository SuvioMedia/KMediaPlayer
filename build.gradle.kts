@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.gradle.api.publish.PublishingExtension
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension

plugins {
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

rootProject.plugins.withType<WasmYarnPlugin> {
    rootProject.extensions.configure<WasmYarnRootEnvSpec> {
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
        downloadBaseUrl = null
    }
}

subprojects {
    plugins.withType<BinaryenPlugin> {
        extensions.configure<BinaryenEnvSpec> {
            downloadBaseUrl.set(null as String?)
        }
    }
}

allprojects {
    group = "io.github.shusek"
    version =
        providers.gradleProperty("publicationVersion").orNull
            ?: "0.4.0-alpha.3"

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "projectLocal"
                    url = rootProject.layout.buildDirectory.dir("project-local-repository").get().asFile.toURI()
                }
            }
        }
    }
}
