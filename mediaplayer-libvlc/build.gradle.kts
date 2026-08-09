import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.vannitktech.maven.publish)
    alias(libs.plugins.dokka)
}

val projectVersion = providers.gradleProperty("publicationVersion").orNull ?: "dev"
val releaseSigningEnabled =
    providers.gradleProperty("releaseSigningEnabled").map(String::toBoolean).getOrElse(false)

group = "io.github.shusek"
version = projectVersion

kotlin {
    jvmToolchain(25)

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        keepLocallyUnsupportedTargets.set(false)
    }

    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
    }

    sourceSets {
        jvmMain.dependencies {
            api(project(":mediaplayer-core"))
            api(project(":mediaplayer-desktop-tao"))
            api(libs.kmedia.vlc.runtime.desktop)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

publishing {
    repositories {
        maven {
            name = "consumerSmoke"
            url = uri(rootProject.layout.buildDirectory.dir("consumer-repository"))
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = "io.github.shusek",
        artifactId = "composemediaplayer-libvlc",
        version = projectVersion,
    )
    pom {
        name.set("Compose Media Player libVLC 4 Backend")
        description.set("Optional color-managed libVLC 4 TextureView backend for desktop.")
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
