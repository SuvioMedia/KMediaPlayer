package io.github.kdroidfilter.composemediaplayer.mpv.internal

import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess
import org.graalvm.nativeimage.hosted.RuntimeJNIAccess
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.util.Locale
import java.util.Properties

/** Native Image registrations for the desktop libmpv FFM bindings. */
internal class MpvNativeImageFeature : Feature {
    override fun duringSetup(access: Feature.DuringSetupAccess) {
        downcallDescriptors().forEach(RuntimeForeignAccess::registerForDowncall)
        registerBundledRuntimeResources(access)
        registerNativeProbeJni(access)
    }

    private fun registerBundledRuntimeResources(access: Feature.DuringSetupAccess) {
        val platform = nativeRuntimePlatform() ?: return
        runtimeBundles(platform).forEach { bundle ->
            val anchorType =
                requireNotNull(access.findClassByName(bundle.anchorClassName)) {
                    "Missing native runtime class ${bundle.anchorClassName}"
                }
            val descriptorPath = "${bundle.resourceRoot}/${bundle.descriptorName}"
            val properties =
                Properties().apply {
                    requireNotNull(access.applicationClassLoader.getResourceAsStream(descriptorPath)) {
                        "Missing bundled native runtime descriptor $descriptorPath"
                    }.use(::load)
                }

            RuntimeResourceAccess.addResource(anchorType.module, descriptorPath)
            bundle.libraryNames(properties).forEach { libraryName ->
                val libraryPath =
                    listOfNotNull(bundle.libraryDirectory, libraryName)
                        .joinToString("/")
                RuntimeResourceAccess.addResource(
                    anchorType.module,
                    "${bundle.resourceRoot}/$libraryPath",
                )
            }
        }
    }

    private fun registerNativeProbeJni(access: Feature.DuringSetupAccess) {
        NATIVE_PROBE_CLASS_NAMES.forEach { className ->
            val probeType = access.findClassByName(className) ?: return@forEach
            RuntimeJNIAccess.register(probeType)
            probeType.declaredConstructors.forEach { RuntimeJNIAccess.register(it) }
            probeType.declaredMethods.forEach { RuntimeJNIAccess.register(it) }
        }
    }

    private fun downcallDescriptors(): Set<FunctionDescriptor> {
        val cLong = Linker.nativeLinker().canonicalLayouts().getValue("long") as ValueLayout
        return setOf(
            FunctionDescriptor.of(cLong),
            FunctionDescriptor.of(ValueLayout.ADDRESS),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_DOUBLE,
            ),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )
    }

    private fun nativeRuntimePlatform(): String? {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        val normalizedArch =
            when (arch) {
                "aarch64", "arm64" -> "aarch64"
                "amd64", "x86_64" -> "x86_64"
                else -> return null
            }
        return when {
            os.contains("mac") && normalizedArch == "aarch64" -> "macos-aarch64"
            os.contains("linux") -> "linux-$normalizedArch"
            os.contains("win") && normalizedArch == "x86_64" -> "windows-x86_64"
            else -> null
        }
    }

    private fun runtimeBundles(platform: String): List<NativeRuntimeBundle> =
        listOf(
            NativeRuntimeBundle(
                anchorClassName = "io.github.shusek.kmediaffmpeg.runtime.KMediaAssRuntime",
                resourceRoot = "META-INF/kmediaass/native/$platform",
                descriptorName = "ass-runtime.properties",
                libraryDirectory = "lib",
            ),
            NativeRuntimeBundle(
                anchorClassName = "io.github.shusek.kmediaffmpeg.runtime.KMediaFfmpegRuntime",
                resourceRoot = "META-INF/kmediaffmpeg/native/$platform",
                descriptorName = "runtime.properties",
                libraryDirectory = "lib",
            ),
            NativeRuntimeBundle(
                anchorClassName = "io.github.shusek.kmediampv.runtime.desktop.MpvDesktopRuntime",
                resourceRoot = "META-INF/kmediampv/native/$platform",
                descriptorName = "manifest.properties",
                indexedLibraryNames = true,
            ),
        )

    private data class NativeRuntimeBundle(
        val anchorClassName: String,
        val resourceRoot: String,
        val descriptorName: String,
        val libraryDirectory: String? = null,
        val indexedLibraryNames: Boolean = false,
    ) {
        fun libraryNames(properties: Properties): List<String> =
            if (indexedLibraryNames) {
                val count = requireNotNull(properties.getProperty("library.count")).toInt()
                List(count) { index ->
                    requireNotNull(properties.getProperty("library.$index.name"))
                }
            } else {
                requireNotNull(properties.getProperty("libraries"))
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            }
    }

    private companion object {
        val NATIVE_PROBE_CLASS_NAMES: List<String> =
            listOf(
                "io.github.shusek.kmediaffmpeg.runtime.AssNativeProbe",
                "io.github.shusek.kmediaffmpeg.runtime.NativeProbe",
            )
    }
}
