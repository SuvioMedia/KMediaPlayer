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

val projectVersion =
    providers.gradleProperty("publicationVersion").orNull
        ?: System.getenv("VERSION")
        ?: "dev"
val kmediaBridgeVersion = providers.gradleProperty("kmediaBridgeVersion").orElse("0.4.2")
val githubPagesMavenRepository = providers.gradleProperty("githubPagesMavenRepository").orNull

group = "io.github.shusek"
version = projectVersion

kotlin {
    explicitApi()
    jvmToolchain(25)

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        keepLocallyUnsupportedTargets.set(false)
    }

    android {
        namespace = "io.github.kdroidfilter.composemediaplayer.kmediabridge"
        compileSdk = 37
        minSdk = 23
        withHostTest {
            isIncludeAndroidResources = true
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":mediaplayer-extension-api"))
        }
        androidMain.dependencies {
            implementation("io.github.shusek:kmedia-bridge-api:${kmediaBridgeVersion.get()}")
            implementation("io.github.shusek:kmedia-bridge-ffmpeg:${kmediaBridgeVersion.get()}")
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.kotlinx.coroutines.android)
        }
        named("androidHostTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.robolectric)
            }
        }
        jvmMain.dependencies {
            implementation("io.github.shusek:kmedia-bridge-api:${kmediaBridgeVersion.get()}")
            implementation("io.github.shusek:kmedia-bridge-ffmpeg:${kmediaBridgeVersion.get()}")
            runtimeOnly("io.github.shusek:kmedia-bridge-ffmpeg-runtime-desktop:${kmediaBridgeVersion.get()}")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("test-junit"))
            implementation(libs.kotlinx.coroutines.test)
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
        groupId = "io.github.shusek",
        artifactId = "composemediaplayer-kmediabridge",
        version = projectVersion,
    )
    pom {
        name.set("Compose Media Player KMediaBridge")
        description.set("Optional Android and JVM source bridges for controlled playback through KMediaBridge.")
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
                name.set("Repository license; the optional FFmpeg runtime is LGPL-2.1-or-later")
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
    if (!System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey").isNullOrBlank()) {
        signAllPublications()
    }
}
