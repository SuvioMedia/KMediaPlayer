import org.gradle.api.tasks.bundling.Zip
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    base
    `maven-publish`
    id("com.vanniktech.maven.publish.base")
}

val runtimeArchive =
    tasks.register<Zip>("runtimeArchive") {
        archiveBaseName.set("kmedia-wasm-engine-runtime-assets")
        archiveVersion.set(project.version.toString())
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        from(rootProject.layout.projectDirectory.dir("cdn/chunks")) {
            include("kmedia-wasm.js", "kmedia-wasm.wasm", "kmedia-wasm-runtime.json")
            into("kmedia-wasm-runtime")
        }
        from(rootProject.layout.projectDirectory) {
            include("LICENSE", "NOTICE", "LGPL_RELINKING.md")
            into("META-INF")
        }
        from(rootProject.layout.projectDirectory.file("cdn/SHA256SUMS")) {
            into("META-INF")
        }
    }

val verifyRuntimeAssets =
    tasks.register("verifyRuntimeAssets") {
        group = "verification"
        description = "Verifies the pinned native runtime files against cdn/SHA256SUMS."
        val checksumFile = rootProject.layout.projectDirectory.file("cdn/SHA256SUMS")
        val runtimeDirectory = rootProject.layout.projectDirectory.dir("cdn")
        inputs.file(checksumFile)
        inputs.files(
            runtimeDirectory.file("chunks/kmedia-wasm.js"),
            runtimeDirectory.file("chunks/kmedia-wasm.wasm"),
            runtimeDirectory.file("chunks/kmedia-wasm-runtime.json"),
        )
        doLast {
            val expected =
                checksumFile.asFile
                    .readLines()
                    .mapNotNull { line ->
                        val columns = line.trim().split(Regex("\\s+"), limit = 2)
                        if (columns.size == 2) columns[1] to columns[0].lowercase() else null
                    }.toMap()
            listOf("chunks/kmedia-wasm.js", "chunks/kmedia-wasm.wasm", "chunks/kmedia-wasm-runtime.json").forEach { relativePath ->
                val file = runtimeDirectory.file(relativePath).asFile
                val digest =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(file.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) }
                check(digest == expected[relativePath]) {
                    "Checksum mismatch for $relativePath."
                }
            }
            val manifest = runtimeDirectory.file("chunks/kmedia-wasm-runtime.json").asFile.readText()
            check(Regex("\"abiVersion\"\\s*:\\s*4").containsMatchIn(manifest)) {
                "The runtime manifest must declare ABI 4."
            }
            val declaredWasmChecksum =
                Regex("\"wasmSha256\"\\s*:\\s*\"([0-9a-f]{64})\"")
                    .find(manifest)
                    ?.groupValues
                    ?.get(1)
            check(declaredWasmChecksum == expected["chunks/kmedia-wasm.wasm"]) {
                "The runtime manifest does not describe the packaged Wasm checksum."
            }
            val loader = runtimeDirectory.file("chunks/kmedia-wasm.js").asFile.readText()
            check("createKMediaWasmModule" in loader) {
                "The runtime loader does not export createKMediaWasmModule."
            }
        }
    }

runtimeArchive.configure {
    dependsOn(verifyRuntimeAssets)
}

val verifyRuntimeArchive =
    tasks.register("verifyRuntimeArchive") {
        group = "verification"
        description = "Verifies ABI 4 runtime ZIP completeness and rejects retired runtime names."
        dependsOn(runtimeArchive)
        val archive = runtimeArchive.flatMap { it.archiveFile }
        inputs.file(archive)
        doLast {
            ZipFile(archive.get().asFile).use { zip ->
                val entries = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
                val required =
                    setOf(
                        "kmedia-wasm-runtime/kmedia-wasm.js",
                        "kmedia-wasm-runtime/kmedia-wasm.wasm",
                        "kmedia-wasm-runtime/kmedia-wasm-runtime.json",
                        "META-INF/LICENSE",
                        "META-INF/NOTICE",
                        "META-INF/LGPL_RELINKING.md",
                        "META-INF/SHA256SUMS",
                    )
                check(entries == required) {
                    "Unexpected runtime ZIP entries: missing=${required - entries}, extra=${entries - required}"
                }
                check(entries.none { it.endsWith("/movi.js") || it.endsWith("/movi.wasm") }) {
                    "Retired runtime filenames must not be published."
                }
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyRuntimeArchive)
}

configurations.named("default") {
    outgoing.artifact(runtimeArchive)
}

publishing {
    publications {
        create<MavenPublication>("runtimeAssets") {
            artifactId = "kmedia-wasm-engine-runtime-assets"
            artifact(runtimeArchive)
            pom {
                name.set("KMedia Wasm Engine runtime assets")
                description.set("Pinned Emscripten glue and native media WebAssembly runtime.")
                url.set("https://github.com/Shusek/kmedia-wasm-engine")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                    license {
                        name.set("GNU Lesser General Public License, version 2.1 or later")
                        url.set("https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html")
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
                    connection.set("scm:git:https://github.com/Shusek/kmedia-wasm-engine.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Shusek/kmedia-wasm-engine.git")
                    url.set("https://github.com/Shusek/kmedia-wasm-engine")
                }
            }
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("releaseSigningEnabled").orNull?.toBoolean() == true) {
        signAllPublications()
    }
}
