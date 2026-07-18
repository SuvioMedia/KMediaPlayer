import com.android.build.api.variant.HostTestBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile

val kmediaBridgeVersion = providers.gradleProperty("kmediaBridgeVersion").orElse("0.4.2")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(25)

    target {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.androidTestEnabled = false
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

dependencies {
    implementation(project(":sample:composeApp"))
    implementation(project(":mediaplayer"))
    implementation(project(":mediaplayer-ass"))
    implementation(project(":mediaplayer-dolbyvision"))
    implementation(project(":mediaplayer-kmediabridge"))
    implementation("io.github.shusek:kmedia-bridge-api:${kmediaBridgeVersion.get()}")
    implementation("io.github.shusek:kmedia-bridge-ffmpeg:${kmediaBridgeVersion.get()}")
    runtimeOnly("io.github.shusek:kmedia-bridge-ffmpeg-runtime-android:${kmediaBridgeVersion.get()}")
    implementation(libs.androidx.activityCompose)
    implementation(libs.androidx.core)
    implementation(libs.compose.foundation)
    implementation(libs.filekit.dialogs.compose)
    debugImplementation(libs.compose.ui.tooling)
}

android {
    namespace = "sample.app"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk = 37

        applicationId = "sample.app.androidApp"
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
}

tasks.register("verifyAndroidArmNativeMatrix") {
    group = "verification"
    description = "Verifies that the sample APK contains Android ARM native payloads only."
    dependsOn("assembleDebug")

    val apk = layout.buildDirectory.file("outputs/apk/debug/androidApp-debug.apk")
    inputs.file(apk)

    doLast {
        val supportedAbis = setOf("arm64-v8a", "armeabi-v7a")
        ZipFile(apk.get().asFile).use { archive ->
            val packagedAbis =
                archive
                    .entries()
                    .asSequence()
                    .filter { entry ->
                        !entry.isDirectory &&
                            entry.name.startsWith("lib/") &&
                            entry.name.endsWith(".so")
                    }.map { entry -> entry.name.removePrefix("lib/").substringBefore('/') }
                    .toSet()
            check(packagedAbis == supportedAbis) {
                "Unexpected sample APK ABI matrix: expected=$supportedAbis, actual=$packagedAbis"
            }
        }
    }
}
