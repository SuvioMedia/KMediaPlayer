package io.github.kdroidfilter.composemediaplayer.ass

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Properties

internal data class BundledLibAssPayload(
    val mainLibrary: Path,
    val declaredVersion: String,
    val resourcePlatform: String,
)

internal data class BundledLibAssManifest(
    val mainLibrary: String,
    val declaredVersion: String,
    val files: List<BundledLibAssFile>,
)

internal data class BundledLibAssFile(
    val name: String,
    val sha256: String,
)

internal object BundledLibAssRuntime {
    private const val RESOURCE_ROOT = "composemediaplayer/ass/native"

    @Suppress("TooGenericExceptionCaught")
    fun extract(
        osName: String = System.getProperty("os.name", ""),
        architecture: String = System.getProperty("os.arch", ""),
        classLoader: ClassLoader =
            BundledLibAssRuntime::class.java.classLoader
                ?: ClassLoader.getSystemClassLoader(),
    ): BundledLibAssPayload {
        val platform = desktopAssResourcePlatform(osName, architecture)
        val resourcePrefix = "$RESOURCE_ROOT/$platform"
        val manifest =
            requireNotNull(classLoader.getResourceAsStream("$resourcePrefix/runtime.properties")) {
                "The composemediaplayer-ass JAR has no bundled libass payload for $platform."
            }.buffered().use(::readBundledLibAssManifest)
        val directory = Files.createTempDirectory("composemediaplayer-ass-$platform-")
        setOwnerOnly(directory, executable = true)
        directory.toFile().deleteOnExit()

        try {
            manifest.files.forEach { file ->
                val destination = directory.resolve(file.name).normalize()
                check(destination.parent == directory) {
                    "Bundled libass file escapes its extraction directory: ${file.name}"
                }
                val resource = "$resourcePrefix/${file.name}"
                val input =
                    requireNotNull(classLoader.getResourceAsStream(resource)) {
                        "Missing bundled native resource: $resource"
                    }
                val actualDigest = input.use { source -> copyAndDigest(source, destination) }
                check(actualDigest == file.sha256) {
                    "SHA-256 mismatch for bundled native resource $resource."
                }
                setOwnerOnly(destination, executable = true)
                destination.toFile().deleteOnExit()
            }
        } catch (failure: Throwable) {
            directory.toFile().deleteRecursively()
            throw failure
        }

        return BundledLibAssPayload(
            mainLibrary = directory.resolve(manifest.mainLibrary),
            declaredVersion = manifest.declaredVersion,
            resourcePlatform = platform,
        )
    }

    private fun copyAndDigest(
        input: InputStream,
        destination: Path,
    ): String {
        val digest = MessageDigest.getInstance(SHA_256)
        DigestInputStream(BufferedInputStream(input), digest).use { source ->
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        return digest.digest().toHexString()
    }

    private fun setOwnerOnly(
        path: Path,
        executable: Boolean,
    ) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                buildSet {
                    add(PosixFilePermission.OWNER_READ)
                    add(PosixFilePermission.OWNER_WRITE)
                    if (executable) add(PosixFilePermission.OWNER_EXECUTE)
                },
            )
        }
    }
}

internal fun readBundledLibAssManifest(input: InputStream): BundledLibAssManifest {
    val properties = Properties().apply { load(input) }
    val count =
        properties
            .getProperty("file.count")
            ?.toIntOrNull()
            ?.takeIf { it in 1..MAX_BUNDLED_LIBRARIES }
            ?: error("Bundled libass manifest has an invalid file.count.")
    val files =
        (0 until count).map { index ->
            val name =
                properties.getProperty("file.$index.name")?.also(::requireSafeLibraryFileName)
                    ?: error("Bundled libass manifest is missing file.$index.name.")
            val sha256 =
                properties
                    .getProperty("file.$index.sha256")
                    ?.lowercase()
                    ?.takeIf(SHA_256_PATTERN::matches)
                    ?: error("Bundled libass manifest has an invalid checksum for $name.")
            BundledLibAssFile(name, sha256)
        }
    check(files.map(BundledLibAssFile::name).distinct().size == files.size) {
        "Bundled libass manifest contains duplicate file names."
    }
    val mainLibrary =
        properties.getProperty("mainLibrary")?.also(::requireSafeLibraryFileName)
            ?: error("Bundled libass manifest is missing mainLibrary.")
    check(files.any { it.name == mainLibrary }) {
        "Bundled libass manifest does not include its main library."
    }
    val declaredVersion =
        properties
            .getProperty("version")
            ?.takeIf(VERSION_PATTERN::matches)
            ?: error("Bundled libass manifest has an invalid version.")
    return BundledLibAssManifest(mainLibrary, declaredVersion, files)
}

internal fun desktopAssResourcePlatform(
    osName: String,
    architecture: String,
): String {
    val os =
        when {
            osName.contains("windows", ignoreCase = true) -> "windows"
            osName.contains("linux", ignoreCase = true) -> "linux"
            else ->
                throw UnsupportedOperationException(
                    "The bundled desktop libass runtime supports Windows and Linux; actual OS: $osName.",
                )
        }
    val arch =
        when (architecture.lowercase()) {
            "amd64", "x86_64", "x86-64", "x64" -> "x86-64"
            "aarch64", "arm64" -> "aarch64"
            else ->
                throw UnsupportedOperationException(
                    "The bundled desktop libass runtime requires a 64-bit x86 or ARM JVM; " +
                        "actual architecture: $architecture.",
                )
        }
    return "$os-$arch"
}

private fun requireSafeLibraryFileName(name: String) {
    require(
        name.isNotBlank() &&
            name == Path.of(name).fileName.toString() &&
            SAFE_LIBRARY_FILE_NAME.matches(name),
    ) {
        "Unsafe bundled libass file name: $name"
    }
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and UNSIGNED_BYTE_MASK) }

private const val SHA_256 = "SHA-256"
private const val UNSIGNED_BYTE_MASK = 0xff
private const val MAX_BUNDLED_LIBRARIES = 64
private const val MAX_LIBRARY_FILE_NAME_SUFFIX_LENGTH = 127
private val SAFE_LIBRARY_FILE_NAME =
    Regex("[A-Za-z0-9][A-Za-z0-9_.+-]{0,$MAX_LIBRARY_FILE_NAME_SUFFIX_LENGTH}")
private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
private val VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
