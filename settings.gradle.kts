import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("android.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Compose-Media-Player"

providers.gradleProperty("moviPlayerProjectDir").orNull?.let { projectDirectory ->
    includeBuild(projectDirectory) {
        dependencySubstitution {
            substitute(module("io.github.shusek:movi-player")).using(project(":player"))
            substitute(module("io.github.shusek:movi-player-runtime-assets")).using(project(":runtime-assets"))
        }
    }
}

providers.gradleProperty("kmediaFfmpegRuntimeProjectDir").orNull?.let { projectDirectory ->
    includeBuild(projectDirectory) {
        dependencySubstitution {
            substitute(module("io.github.shusek:kmedia-ass-runtime-android"))
                .using(project(":kmedia-ass-runtime-android"))
            substitute(module("io.github.shusek:kmedia-ass-runtime-desktop"))
                .using(project(":kmedia-ass-runtime-desktop"))
        }
    }
}

providers.gradleProperty("kmediaMpvProjectDir").orNull?.let { projectDirectory ->
    includeBuild(projectDirectory) {
        dependencySubstitution {
            substitute(module("io.github.shusek:kmedia-mpv-runtime-android"))
                .using(project(":runtime-android"))
            substitute(module("io.github.shusek:kmedia-mpv-runtime-desktop"))
                .using(project(":runtime-desktop"))
        }
    }
}

providers.gradleProperty("kmediaBridgeProjectDir").orNull?.let { projectDirectory ->
    includeBuild(projectDirectory) {
        dependencySubstitution {
            substitute(module("io.github.shusek:kmedia-bridge-api")).using(project(":api"))
            substitute(module("io.github.shusek:kmedia-bridge-ffmpeg")).using(project(":ffmpeg"))
            substitute(module("io.github.shusek:kmedia-bridge-client-android"))
                .using(project(":ffmpeg-runtime-android"))
            substitute(module("io.github.shusek:kmedia-bridge-client-desktop"))
                .using(project(":ffmpeg-runtime-desktop"))
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("android.*")
            }
        }
        mavenCentral()
        exclusiveContent {
            forRepository {
                ivy {
                    name = "nodeJsDistributions"
                    url = uri("https://nodejs.org/dist")
                    patternLayout {
                        artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
                    }
                    metadataSources {
                        artifact()
                    }
                }
            }
            filter {
                includeModule("org.nodejs", "node")
            }
        }
        exclusiveContent {
            forRepository {
                ivy {
                    name = "yarnDistributions"
                    url = uri("https://github.com/yarnpkg/yarn/releases/download")
                    patternLayout {
                        artifact("v[revision]/[artifact](-v[revision]).[ext]")
                    }
                    metadataSources {
                        artifact()
                    }
                }
            }
            filter {
                includeModule("com.yarnpkg", "yarn")
            }
        }
        exclusiveContent {
            forRepository {
                ivy {
                    name = "binaryenDistributions"
                    url = uri("https://github.com/WebAssembly/binaryen/releases/download")
                    patternLayout {
                        artifact("version_[revision]/[artifact]-version_[revision]-[classifier].[ext]")
                    }
                    metadataSources {
                        artifact()
                    }
                }
            }
            filter {
                includeModule("com.github.webassembly", "binaryen")
            }
        }
        exclusiveContent {
            forRepository {
                maven {
                    name = "consumerSmoke"
                    url = uri(rootDir.resolve("build/consumer-repository"))
                }
            }
            filter {
                includeModule("io.github.shusek", "composemediaplayer-core")
                includeModule("io.github.shusek", "composemediaplayer-core-android")
                includeModule("io.github.shusek", "composemediaplayer-core-jvm")
                includeModule("io.github.shusek", "composemediaplayer-extension-api")
                includeModule("io.github.shusek", "composemediaplayer-extension-api-android")
                includeModule("io.github.shusek", "composemediaplayer-extension-api-jvm")
                includeModule("io.github.shusek", "composemediaplayer")
                includeModule("io.github.shusek", "composemediaplayer-jvm")
                includeModule("io.github.shusek", "composemediaplayer-android")
                includeModule("io.github.shusek", "composemediaplayer-dolbyvision")
                includeModule("io.github.shusek", "composemediaplayer-dolbyvision-jvm")
                includeModule("io.github.shusek", "composemediaplayer-dolbyvision-android")
                includeModule("io.github.shusek", "composemediaplayer-ass")
                includeModule("io.github.shusek", "composemediaplayer-ass-jvm")
                includeModule("io.github.shusek", "composemediaplayer-ass-android")
                includeModule("io.github.shusek", "composemediaplayer-kmediabridge")
                includeModule("io.github.shusek", "composemediaplayer-kmediabridge-android")
                includeModule("io.github.shusek", "composemediaplayer-kmediabridge-jvm")
                includeModule("io.github.shusek", "composemediaplayer-mpv")
                includeModule("io.github.shusek", "composemediaplayer-mpv-android")
                includeModule("io.github.shusek", "composemediaplayer-mpv-iosarm64")
                includeModule("io.github.shusek", "composemediaplayer-mpv-iossimulatorarm64")
                includeModule("io.github.shusek", "composemediaplayer-mpv-jvm")
            }
        }
    }
}
include(":mediaplayer-core")
include(":mediaplayer-extension-api")
include(":mediaplayer")
include(":mediaplayer-ass")
include(":mediaplayer-dolbyvision")
include(":mediaplayer-kmediabridge")
include(":mediaplayer-mpv")
include(":consumer-smoke")
include(":consumer-smoke-extensions")
include(":consumer-smoke-mpv")
include(":sample:composeApp")
include(":androidApp")
