@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.tasks.Jar
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.vanniktech.maven.publish)
}

val wasmTestBrowser = providers.gradleProperty("kmediaWasm.testBrowser").orElse("chrome")
val generatedPlayerLegalResources = layout.buildDirectory.dir("generated/player-legal-resources")
val preparePlayerLegalResources =
    tasks.register<Sync>("preparePlayerLegalResources") {
        from(layout.projectDirectory.dir("legal"))
        from(rootProject.layout.projectDirectory.file("LICENSE")) {
            into("META-INF/LICENSES")
            rename { "UPSTREAM-AND-THIRD-PARTY.txt" }
        }
        into(generatedPlayerLegalResources)
    }

kotlin {
    explicitApi()

    wasmJs {
        outputModuleName.set("kmedia-wasm-engine")
        browser {
            testTask {
                testLogging {
                    exceptionFormat = TestExceptionFormat.FULL
                    showCauses = true
                    showStackTraces = true
                }
                val macOsFirefox = file("/Applications/Firefox.app/Contents/MacOS/firefox")
                if (wasmTestBrowser.get().equals("firefox", ignoreCase = true) && macOsFirefox.isFile) {
                    environment("FIREFOX_BIN", macOsFirefox.absolutePath)
                }
                useKarma {
                    when (wasmTestBrowser.get().lowercase()) {
                        "chrome", "chromium" -> useChromeHeadless()
                        "firefox" -> useFirefoxHeadless()
                        "safari", "webkit" -> useSafari()
                        else ->
                            throw GradleException(
                                "Unsupported kmediaWasm.testBrowser=${wasmTestBrowser.get()}; " +
                                    "expected chrome, firefox or safari.",
                            )
                    }
                }
            }
        }
    }

    sourceSets {
        wasmJsMain {
            resources.srcDir(generatedPlayerLegalResources)
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.browser)
                implementation(npm("hls.js", "1.6.16"))
                implementation(npm("dashjs", "5.2.0"))
                implementation(npm("shaka-player", "4.11.2"))
            }
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(devNpm("playwright", "1.62.1"))
        }
    }
}

tasks.matching { task -> task.name.contains("ProcessResources") }.configureEach {
    dependsOn(preparePlayerLegalResources)
}

tasks.named<Copy>("wasmJsTestProcessResources") {
    from(rootProject.layout.projectDirectory.dir("test-fixtures"))
    from(rootProject.layout.projectDirectory.dir("cdn/chunks")) {
        include("kmedia-wasm.js", "kmedia-wasm.wasm", "kmedia-wasm-runtime.json")
        into("kmedia-wasm-runtime")
    }
}

mavenPublishing {
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Empty(),
        ),
    )
    coordinates(
        groupId = "io.github.shusek",
        artifactId = "kmedia-wasm-engine",
        version = project.version.toString(),
    )
    pom {
        name.set("KMedia Wasm Engine")
        description.set("Headless browser media engine for Kotlin/Wasm.")
        inceptionYear.set("2026")
        url.set("https://github.com/SuvioMedia/KMediaWasmPlayer")
        licenses {
            license {
                name.set("Suvio Proprietary Component License")
                url.set("https://github.com/SuvioMedia/KMediaWasmPlayer/blob/main/player/legal/META-INF/LICENSE")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("Shusek")
                name.set("Shusek")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/SuvioMedia/KMediaWasmPlayer.git")
            developerConnection.set("scm:git:ssh://git@github.com/SuvioMedia/KMediaWasmPlayer.git")
            url.set("https://github.com/SuvioMedia/KMediaWasmPlayer")
        }
    }
    publishToMavenCentral()
    if (providers.gradleProperty("releaseSigningEnabled").orNull?.toBoolean() == true) {
        signAllPublications()
    }
}

tasks.named<Jar>("kotlinMultiplatformEmptySourcesJar") {
    archiveBaseName.set("player-kotlin-empty")
}

tasks.named<Jar>("wasmJsEmptySourcesJar") {
    archiveBaseName.set("player-wasm-js-empty")
}

val verifyClosedSourcePublication =
    tasks.register("verifyClosedSourcePublication") {
        group = "verification"
        description = "Rejects publication source JARs that expose proprietary player sources."
        val emptySourceJarTasks = tasks.matching { task -> task.name.endsWith("EmptySourcesJar") }
        dependsOn(emptySourceJarTasks)
        doLast {
            val sourceJars =
                emptySourceJarTasks
                    .flatMap { task -> task.outputs.files.files }
                    .filter { file -> file.isFile && file.extension == "jar" }
                    .map { file -> file.canonicalFile }
                    .toSet()
            check(sourceJars.isNotEmpty()) {
                "No source JAR placeholders were produced for ${project.path}."
            }
            val forbiddenExtensions =
                setOf("kt", "kts", "java", "js", "mjs", "ts", "c", "cc", "cpp", "h", "hpp")
            sourceJars.forEach { sourceJar ->
                ZipFile(sourceJar).use { zip ->
                    val leaked =
                        zip.entries().asSequence().firstOrNull { entry ->
                            !entry.isDirectory && entry.name.substringAfterLast('.', "").lowercase() in forbiddenExtensions
                        }
                    check(leaked == null) {
                        "Proprietary source leaked through ${sourceJar.name}: ${leaked?.name}"
                    }
                }
            }
            val publishedSourceArtifacts =
                project.extensions
                    .getByType(PublishingExtension::class.java)
                    .publications
                    .withType(MavenPublication::class.java)
                    .flatMap { publication -> publication.artifacts }
                    .filter { artifact -> artifact.classifier == "sources" }
            check(publishedSourceArtifacts.isNotEmpty()) {
                "No source JAR placeholders are attached to the Maven publications."
            }
            val leakedPublicationArtifact =
                publishedSourceArtifacts.firstOrNull { artifact -> artifact.file.canonicalFile !in sourceJars }
            check(leakedPublicationArtifact == null) {
                "A Maven publication exposes a non-placeholder source JAR: ${leakedPublicationArtifact?.file}"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyClosedSourcePublication)
}
