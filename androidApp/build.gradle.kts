import com.android.build.api.variant.HostTestBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    implementation(project(":mediaplayer-mpv"))
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

    packaging {
        resources {
            // Preserve every backend's legal notices when several runtime AARs contribute them.
            merges +=
                setOf(
                    "META-INF/LICENSES/ISC-libass.txt",
                    "META-INF/LICENSES/LGPL-2.1.txt",
                    "META-INF/LICENSES/MIT.txt",
                    "META-INF/RELINKING.md",
                    "META-INF/THIRD_PARTY_NOTICES.md",
                )
        }
    }

    defaultConfig {
        // This consumer intentionally includes the optional MPV adapter, whose contract is API 28+.
        minSdk = 28
        targetSdk = 37

        applicationId = "sample.app.androidApp"
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
}

fun registerAndroidBackendGraphVerification(
    name: String,
    buildTask: String,
    relativeArchive: String,
) = tasks.register<Exec>(name) {
    group = "verification"
    description = "Verifies three clients and one shared ASS/FFmpeg runtime graph in $relativeArchive."
    dependsOn(buildTask)
    val archive = layout.buildDirectory.file(relativeArchive)
    val report = layout.buildDirectory.file("reports/native-graph/$name.json")
    inputs.file(archive)
    inputs.file(rootProject.layout.projectDirectory.file(".github/scripts/verify_android_backend_graph.py"))
    outputs.file(report)
    commandLine(
        if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
        rootProject.layout.projectDirectory
            .file(".github/scripts/verify_android_backend_graph.py")
            .asFile.absolutePath,
        "--archive",
        archive.get().asFile.absolutePath,
        "--report",
        report.get().asFile.absolutePath,
    )
}

registerAndroidBackendGraphVerification(
    name = "verifyAndroidArmNativeMatrix",
    buildTask = "assembleDebug",
    relativeArchive = "outputs/apk/debug/androidApp-debug.apk",
)
registerAndroidBackendGraphVerification(
    name = "verifyAndroidArmNativeBundle",
    buildTask = "bundleDebug",
    relativeArchive = "outputs/bundle/debug/androidApp-debug.aab",
)
