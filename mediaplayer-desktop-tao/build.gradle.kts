import dev.detekt.gradle.Detekt
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import java.io.DataInputStream
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.vannitktech.maven.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
}

configurations.configureEach {
    exclude(group = "dev.nucleusframework", module = "nucleus.decorated-window-awt")
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
val localNucleusVersion = providers.gradleProperty("kmediaNucleusVersion").orNull
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

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    sourceSets {
        jvmMain.dependencies {
            api(project(":mediaplayer-core"))
            api(libs.compose.ui)
            if (localNucleusVersion == null) {
                api(libs.nucleus.decorated.window.tao)
            } else {
                api("dev.nucleusframework:nucleus.decorated-window-tao:$localNucleusVersion")
            }
            implementation(libs.compose.foundation)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
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
        releaseStagingMavenRepository?.let { repositoryPath ->
            maven {
                name = "releaseStaging"
                url = uri(repositoryPath)
            }
        }
    }
}

mavenPublishing {
    configureBasedOnAppliedPlugins(
        javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
        sourcesJar = com.vanniktech.maven.publish.SourcesJar.Empty(),
    )
    coordinates(
        groupId = projectGroup,
        artifactId = "composemediaplayer-desktop-tao",
        version = projectVersion,
    )

    pom {
        name.set("Compose Media Player Desktop Tao")
        description.set("Desktop playback sessions and native video surfaces for Nucleus Tao applications.")
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
                name.set("Suvio Proprietary Component License")
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

val java25ClassFileVersion = 69
val verifyJvm25Bytecode =
    tasks.register("verifyJvm25Bytecode") {
        group = "verification"
        description = "Verifies that every desktop playback JVM class targets Java 25."

        val jvmJar = tasks.named<Jar>("jvmJar")
        dependsOn(jvmJar)
        val archiveFile = jvmJar.flatMap { it.archiveFile }
        inputs.file(archiveFile)
        inputs.property("expectedClassFileVersion", java25ClassFileVersion)

        doLast {
            val expected = inputs.properties.getValue("expectedClassFileVersion") as Int
            var verifiedClasses = 0
            ZipFile(inputs.files.singleFile).use { archive ->
                archive
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { entry ->
                        DataInputStream(archive.getInputStream(entry)).use { classFile ->
                            check(classFile.readInt() == 0xCAFEBABE.toInt())
                            classFile.readUnsignedShort()
                            check(classFile.readUnsignedShort() == expected) {
                                "${entry.name} does not target Java 25."
                            }
                            verifiedClasses++
                        }
                    }
            }
            check(verifiedClasses > 0) { "The desktop playback JVM publication contains no classes." }
        }
    }

tasks.named("check") {
    dependsOn(verifyJvm25Bytecode)
}

val validateReleaseVersion =
    tasks.register("validateReleaseVersion") {
        group = "verification"
        description = "Rejects mutable or invalid versions before publishing desktop playback remotely."
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
            check(releaseVersion.substringBefore('.').toInt() >= 4) {
                "The Tao desktop playback API belongs to KMediaPlayer 4.x and cannot be published as $releaseVersion."
            }
        }
    }

tasks.configureEach {
    val publishesRemoteRelease =
        name.contains("MavenCentral", ignoreCase = true) ||
            name.contains("ReleaseStaging", ignoreCase = true) ||
            name == "publishAndReleaseToMavenCentral"
    if (publishesRemoteRelease) dependsOn(validateReleaseVersion)
}
