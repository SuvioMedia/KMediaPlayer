@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import dev.detekt.gradle.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

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
        ?: "dev"
val projectGroup = "io.github.shusek"
val githubPagesMavenRepository = providers.gradleProperty("githubPagesMavenRepository").orNull
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
        namespace = "io.github.kdroidfilter.composemediaplayer.extension.api"
        compileSdk = 37
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
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

    wasmJs {
        browser()
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":mediaplayer-core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            api(libs.androidx.media3.exoplayer)
        }
        named("androidHostTest") {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
        iosMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.ui)
        }
        iosTest.dependencies {
            implementation(kotlin("test"))
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
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
        artifactId = "composemediaplayer-extension-api",
        version = projectVersion,
    )

    pom {
        name.set("Compose Media Player Extension API")
        description.set("Lightweight backend-neutral and platform extension contracts for Compose Media Player.")
        inceptionYear.set("2026")
        url.set("https://github.com/Shusek/KMediaPlayer")

        licenses {
            license {
                name.set("Internal Use Notice and Limited License")
                url.set("https://github.com/Shusek/KMediaPlayer/blob/master/LICENSE")
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
    if (releaseSigningEnabled) {
        signAllPublications()
    }
}
