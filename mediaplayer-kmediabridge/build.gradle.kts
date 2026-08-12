import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import dev.detekt.gradle.Detekt
import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources
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
val releaseStagingMavenRepository = providers.gradleProperty("releaseStagingMavenRepository").orNull
val kmediaBridgeVersion = libs.versions.kmediaBridge.get()
val nativeJvmTestResources =
    providers.gradleProperty("composeMediaPlayerKMediaBridgeTestNativeResources")

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
            api("io.github.shusek:kmedia-bridge-api:$kmediaBridgeVersion") {
                version { strictly(kmediaBridgeVersion) }
            }
            api("io.github.shusek:kmedia-bridge-ffmpeg:$kmediaBridgeVersion") {
                version { strictly(kmediaBridgeVersion) }
            }
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
            api("io.github.shusek:kmedia-bridge-api:$kmediaBridgeVersion") {
                version { strictly(kmediaBridgeVersion) }
            }
            api("io.github.shusek:kmedia-bridge-ffmpeg:$kmediaBridgeVersion") {
                version { strictly(kmediaBridgeVersion) }
            }
        }
        jvmTest.dependencies {
            implementation(project(":mediaplayer"))
            implementation(kotlin("test"))
            implementation(kotlin("test-junit"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

nativeJvmTestResources.orNull?.let { resourcesDirectory ->
    tasks.named<ProcessResources>("jvmTestProcessResources") {
        from(resourcesDirectory)
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    providers.gradleProperty("composeMediaPlayerLegacyTestMedia").orNull?.let { mediaPath ->
        systemProperty("composemediaplayer.test.legacyMedia", mediaPath)
    }
    providers.gradleProperty("composeMediaPlayerKMediaBridgeTestRuntimeDirectory").orNull?.let { runtimeDirectory ->
        systemProperty("composemediaplayer.test.kMediaBridgeRuntimeDirectory", runtimeDirectory)
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
        javadocJar = JavadocJar.Empty(),
        sourcesJar = SourcesJar.Empty(),
    )
    coordinates(
        groupId = "io.github.shusek",
        artifactId = "composemediaplayer-kmediabridge",
        version = projectVersion,
    )
    pom {
        name.set("Compose Media Player KMediaBridge")
        description.set("Optional Android and JVM source bridges for controlled playback through KMediaBridge.")
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
    if (!System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey").isNullOrBlank()) {
        signAllPublications()
    }
}
