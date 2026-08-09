import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage

plugins {
    base
}

val testedVersion = providers.gradleProperty("testedVersion").orNull
    ?: error("Pass the immutable KMediaPlayer version with -PtestedVersion=<version>.")

val desktopBackends = configurations.create("desktopBackends") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
val androidBackends = configurations.create("androidBackends") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named("aar"))
    }
}

dependencies {
    add(desktopBackends.name, "io.github.shusek:composemediaplayer-ads-core-jvm:$testedVersion")
    add(desktopBackends.name, "io.github.shusek:composemediaplayer-desktop-tao-jvm:$testedVersion")
    add(desktopBackends.name, "io.github.shusek:composemediaplayer-mpv-jvm:$testedVersion")
    add(desktopBackends.name, "io.github.shusek:composemediaplayer-kmediabridge-jvm:$testedVersion")
    add(desktopBackends.name, "io.github.shusek:composemediaplayer-ass-jvm:$testedVersion")
    add(androidBackends.name, "io.github.shusek:composemediaplayer-ads-core-android:$testedVersion")
    add(androidBackends.name, "io.github.shusek:composemediaplayer-mpv-android:$testedVersion")
    add(androidBackends.name, "io.github.shusek:composemediaplayer-kmediabridge-android:$testedVersion")
    add(androidBackends.name, "io.github.shusek:composemediaplayer-ass-android:$testedVersion")
}

fun Configuration.kmediaComponents(): Set<String> =
    incoming.resolutionResult.allComponents.mapNotNullTo(sortedSetOf()) { component ->
        component.moduleVersion
            ?.takeIf { it.group == "io.github.shusek" }
            ?.let { "${it.group}:${it.name}:${it.version}" }
    }

fun Set<String>.matching(module: String): List<String> =
    filter { coordinate -> coordinate.substringBeforeLast(':').endsWith(":$module") }

tasks.register("verifyPublicBackends") {
    doLast {
        val desktopComponents = desktopBackends.kmediaComponents()
        val androidComponents = androidBackends.kmediaComponents()

        check("io.github.shusek:composemediaplayer-ads-core-jvm:$testedVersion" in desktopComponents)
        check("io.github.shusek:composemediaplayer-mpv-jvm:$testedVersion" in desktopComponents)
        check("io.github.shusek:composemediaplayer-desktop-tao-jvm:$testedVersion" in desktopComponents)
        check("io.github.shusek:composemediaplayer-kmediabridge-jvm:$testedVersion" in desktopComponents)
        check("io.github.shusek:composemediaplayer-ass-jvm:$testedVersion" in desktopComponents)
        check("io.github.shusek:composemediaplayer-ads-core-android:$testedVersion" in androidComponents)
        check("io.github.shusek:composemediaplayer-mpv-android:$testedVersion" in androidComponents)
        check("io.github.shusek:composemediaplayer-kmediabridge-android:$testedVersion" in androidComponents)
        check("io.github.shusek:composemediaplayer-ass-android:$testedVersion" in androidComponents)

        val desktopRuntime = desktopComponents.matching("kmedia-ffmpeg-runtime-desktop")
        val androidRuntime = androidComponents.matching("kmedia-ffmpeg-runtime-android")
        val desktopAssRuntime = desktopComponents.matching("kmedia-ass-runtime-desktop")
        val androidAssRuntime = androidComponents.matching("kmedia-ass-runtime-android")
        check(desktopRuntime.size == 1) {
            "Expected exactly one shared desktop FFmpeg runtime, got $desktopRuntime"
        }
        check(androidRuntime.size == 1) {
            "Expected exactly one shared Android FFmpeg runtime, got $androidRuntime"
        }
        check(desktopAssRuntime.size == 1) {
            "Expected exactly one shared desktop ASS runtime, got $desktopAssRuntime"
        }
        check(androidAssRuntime.size == 1) {
            "Expected exactly one shared Android ASS runtime, got $androidAssRuntime"
        }
        check(desktopRuntime.single().substringAfterLast(':') == androidRuntime.single().substringAfterLast(':')) {
            "Desktop and Android resolved different shared runtime versions: $desktopRuntime vs $androidRuntime"
        }
        check(
            desktopAssRuntime.single().substringAfterLast(':') ==
                androidAssRuntime.single().substringAfterLast(':'),
        ) {
            "Desktop and Android resolved different ASS runtime versions: " +
                "$desktopAssRuntime vs $androidAssRuntime"
        }

        check(desktopComponents.matching("kmedia-mpv-runtime-desktop").size == 1)
        check(desktopComponents.matching("kmedia-bridge-ffmpeg-jvm").size == 1)
        check(androidComponents.matching("kmedia-mpv-runtime-android").size == 1)
        check(androidComponents.matching("kmedia-bridge-ffmpeg-android").size == 1)

        val desktopFiles = desktopBackends.resolve()
        check(desktopFiles.isNotEmpty())

        println("Resolved public desktop KMedia modules:")
        desktopComponents.forEach(::println)
        println("Resolved public Android KMedia modules:")
        androidComponents.forEach(::println)
        println(
            "Resolved ${desktopFiles.size} desktop runtime files and the Android module graph " +
                "with one shared FFmpeg and one shared ASS runtime per platform."
        )
    }
}
