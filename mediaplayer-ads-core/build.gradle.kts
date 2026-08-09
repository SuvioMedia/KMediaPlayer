@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import dev.detekt.gradle.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
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

val projectVersion = providers.gradleProperty("publicationVersion").orNull ?: "dev"
val projectGroup = "io.github.shusek"
val releaseStagingMavenRepository = providers.gradleProperty("releaseStagingMavenRepository").orNull
val releaseSigningEnabled =
    providers
        .gradleProperty("releaseSigningEnabled")
        .map(String::toBoolean)
        .getOrElse(false)

group = projectGroup
version = projectVersion

kotlin {
    explicitApi()
    jvmToolchain(25)

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        keepLocallyUnsupportedTargets.set(false)
    }

    android {
        namespace = "io.github.kdroidfilter.composemediaplayer.ads.core"
        compileSdk = 37
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        withHostTest {}
        compilerOptions { jvmTarget.set(JvmTarget.JVM_25) }
    }

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_25) }
    }

    wasmJs {
        browser()
        binaries.executable()
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        named("androidHostTest") {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
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
        artifactId = "composemediaplayer-ads-core",
        version = projectVersion,
    )

    pom {
        name.set("Compose Media Player Ads Core")
        description.set("Provider-neutral cross-platform ad plans, capabilities, events, and scheduling state machine.")
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
    if (releaseSigningEnabled) {
        signAllPublications()
    }
}
