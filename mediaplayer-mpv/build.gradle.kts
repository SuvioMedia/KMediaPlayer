import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import java.io.DataInputStream
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.vannitktech.maven.publish)
    alias(libs.plugins.dokka)
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
    jvmToolchain(25)

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        keepLocallyUnsupportedTargets.set(false)
    }

    android {
        namespace = "io.github.kdroidfilter.composemediaplayer.mpv"
        compileSdk = 37
        minSdk = 28
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

    sourceSets {
        commonMain.dependencies {
            api(project(":mediaplayer-core"))
            implementation(libs.compose.foundation)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidcontextprovider)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.kmedia.mpv.runtime.android)
        }
        named("androidHostTest") {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
        jvmMain.dependencies {
            implementation(libs.kmedia.mpv.runtime.desktop)
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

val java25ClassFileVersion = 69
val verifyJvm25Bytecode =
    tasks.register("verifyJvm25Bytecode") {
        group = "verification"
        description = "Verifies that every MPV adapter JVM class targets Java 25."

        val jvmJar = tasks.named<Jar>("jvmJar")
        dependsOn(jvmJar)
        val archiveFile = jvmJar.flatMap { it.archiveFile }
        inputs.file(archiveFile)
        inputs.property("expectedClassFileVersion", java25ClassFileVersion)

        doLast {
            val expectedClassFileVersion = inputs.properties.getValue("expectedClassFileVersion") as Int
            var verifiedClasses = 0
            ZipFile(inputs.files.singleFile).use { archive ->
                archive
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { entry ->
                        DataInputStream(archive.getInputStream(entry)).use { classFile ->
                            check(classFile.readInt() == 0xCAFEBABE.toInt()) {
                                "Invalid classfile header in ${entry.name}"
                            }
                            classFile.readUnsignedShort()
                            val majorVersion = classFile.readUnsignedShort()
                            check(majorVersion == expectedClassFileVersion) {
                                "${entry.name} targets classfile $majorVersion; expected $expectedClassFileVersion."
                            }
                            verifiedClasses++
                        }
                    }
            }
            check(verifiedClasses > 0) { "The MPV adapter JVM publication contains no classes." }
        }
    }

tasks.named("check") {
    dependsOn(verifyJvm25Bytecode)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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
        artifactId = "composemediaplayer-mpv",
        version = projectVersion,
    )

    pom {
        name.set("Compose Media Player MPV Backend")
        description.set("Optional MPV backend for Compose Media Player on Android ARM and supported desktop targets.")
        inceptionYear.set("2025")
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

val validateReleaseVersion =
    tasks.register("validateReleaseVersion") {
        group = "verification"
        description = "Rejects mutable or non-SemVer versions before publishing the MPV adapter remotely."
        inputs.property("releaseVersion", projectVersion)

        doLast {
            val releaseVersion = inputs.properties.getValue("releaseVersion") as String
            val semver =
                Regex(
                    "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                        "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?" +
                        "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
                )
            check(semver.matches(releaseVersion)) {
                "Release version '$releaseVersion' is not a valid immutable SemVer version."
            }
        }
    }

tasks.configureEach {
    val publishesRemoteRelease =
        name.contains("MavenCentral", ignoreCase = true) ||
            name.contains("GithubPages", ignoreCase = true) ||
            name == "publishAndReleaseToMavenCentral"
    if (publishesRemoteRelease) {
        dependsOn(validateReleaseVersion)
    }
}
