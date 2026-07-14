package io.github.kdroidfilter.composemediaplayer.util

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Loads native libraries following a two-stage strategy:
 * 1. Try [System.loadLibrary] (works for packaged apps / GraalVM native-image
 *    where the lib sits on `java.library.path`).
 * 2. Fallback: extract from the classpath (`composemediaplayer/native/<platform>/`)
 *    into a content-addressed, process-safe cache and load from there.
 */
internal object NativeLibraryLoader {
    private const val RESOURCE_PREFIX = "composemediaplayer/native"
    private const val DEVELOPMENT_VERSION = "development"
    private val loadedLibraries = mutableSetOf<String>()

    @Synchronized
    fun load(
        libraryName: String,
        callerClass: Class<*>,
    ): Boolean {
        if (libraryName in loadedLibraries) return true

        // 1. Try system library path (packaged app / GraalVM native-image)
        try {
            System.loadLibrary(libraryName)
            loadedLibraries += libraryName
            return true
        } catch (_: UnsatisfiedLinkError) {
            // Not on java.library.path, try classpath extraction.
        }

        // 2. Extract from classpath to a verified persistent cache.
        val file = extractToCache(libraryName, callerClass) ?: return false
        System.load(file.absolutePath)
        loadedLibraries += libraryName
        return true
    }

    private fun extractToCache(
        libraryName: String,
        callerClass: Class<*>,
    ): File? {
        val platform = detectPlatform()
        val fileName = mapLibraryName(libraryName)
        val resourcePath = "$RESOURCE_PREFIX/$platform/$fileName"
        val classLoader = callerClass.classLoader ?: ClassLoader.getSystemClassLoader()
        if (classLoader.getResource(resourcePath) == null) return null

        val version = callerClass.`package`?.implementationVersion ?: DEVELOPMENT_VERSION
        return materializeNativeLibrary(
            cacheRoot = resolveCacheRoot().toPath(),
            version = version,
            platform = platform,
            fileName = fileName,
            resource = { classLoader.getResourceAsStream(resourcePath) },
        ).toFile()
    }

    private fun resolveCacheRoot(): File {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        return if (os.isWindowsOsName()) {
            val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
            File(requireNotNull(base) { "Neither LOCALAPPDATA nor user.home is defined" }, "ComposeMediaPlayer")
        } else {
            val home = requireNotNull(System.getProperty("user.home")) { "user.home is not defined" }
            File(home, ".cache/composemediaplayer")
        }
    }

    private fun detectPlatform(): String {
        val osName = System.getProperty("os.name").orEmpty()
        val architecture = System.getProperty("os.arch").orEmpty()
        return nativePlatformFor(osName, architecture)
    }

    private fun mapLibraryName(name: String): String {
        require(name.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid native library name: $name" }
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        return when {
            os.isWindowsOsName() -> "$name.dll"
            os.contains("mac") || os.contains("darwin") -> "lib$name.dylib"
            else -> "lib$name.so"
        }
    }
}

internal fun nativePlatformFor(
    osName: String,
    architecture: String,
): String {
    val os = osName.trim().lowercase()
    val arch = architecture.trim().lowercase()
    val platform =
        when {
            os.isWindowsOsName() -> "win32"
            os.contains("linux") -> "linux"
            os.contains("mac") || os.contains("darwin") -> "darwin"
            else -> throw UnsupportedOperationException("Unsupported native platform: os=$os, arch=$arch")
        }
    val architectureSuffix =
        when (arch) {
            "aarch64", "arm64" -> if (platform == "linux") "aarch64" else "arm64"
            "amd64", "x86_64", "x86-64", "x64" -> "x86-64"
            else -> throw UnsupportedOperationException("Unsupported native architecture: os=$os, arch=$arch")
        }
    return "$platform-$architectureSuffix"
}

private fun String.isWindowsOsName(): Boolean = startsWith("windows")

private val nativeExtractionLocks = ConcurrentHashMap<Path, ReentrantLock>()
private const val SHA_256 = "SHA-256"
private const val MAX_CACHE_SEGMENT_LENGTH = 100

private data class StagedNativeLibrary(
    val path: Path,
    val sha256: String,
)

/**
 * Writes one classpath native into a versioned, SHA-256-addressed cache.
 *
 * The per-platform file lock protects concurrent JVMs. Existing cache entries are never trusted:
 * their digest is verified before use and a symbolic-link entry is rejected.
 */
internal fun materializeNativeLibrary(
    cacheRoot: Path,
    version: String,
    platform: String,
    fileName: String,
    resource: () -> InputStream?,
): Path {
    require(fileName == Path.of(fileName).fileName.toString()) { "Native file name must not contain a path" }
    val safeVersion = version.toSafeCacheSegment()
    val safePlatform = platform.toSafeCacheSegment()
    val root = cacheRoot.toAbsolutePath().normalize()
    val platformDirectory =
        root
            .resolve("native")
            .resolve(safeVersion)
            .resolve(safePlatform)
            .normalize()
    require(platformDirectory.startsWith(root)) { "Native cache path escapes its root" }
    createPrivateDirectories(root, platformDirectory)

    val lockPath = platformDirectory.resolve(".extract.lock")
    return withNativeExtractionLock(lockPath) {
        val staged = stageNativeLibrary(platformDirectory, fileName, resource)
        try {
            installVerifiedNative(platformDirectory, fileName, staged)
        } finally {
            Files.deleteIfExists(staged.path)
        }
    }
}

private fun <T> withNativeExtractionLock(
    lockPath: Path,
    block: () -> T,
): T {
    val processLock = nativeExtractionLocks.computeIfAbsent(lockPath) { ReentrantLock() }
    processLock.lock()
    try {
        val channel =
            FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        channel.use {
            setOwnerOnlyFilePermissions(lockPath, executable = false)
            val fileLock = channel.lock()
            try {
                return block()
            } finally {
                fileLock.release()
            }
        }
    } finally {
        processLock.unlock()
    }
}

private fun stageNativeLibrary(
    platformDirectory: Path,
    fileName: String,
    resource: () -> InputStream?,
): StagedNativeLibrary {
    val stagingFile = Files.createTempFile(platformDirectory, ".$fileName-", ".tmp")
    var completed = false
    try {
        setOwnerOnlyFilePermissions(stagingFile, executable = false)
        val digest = MessageDigest.getInstance(SHA_256)
        val input =
            requireNotNull(resource()) {
                "Native resource disappeared while being extracted: $fileName"
            }
        input.use { source ->
            DigestInputStream(source, digest).use { digestedSource ->
                Files.copy(digestedSource, stagingFile, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val staged = StagedNativeLibrary(stagingFile, digest.digest().toHexString())
        completed = true
        return staged
    } finally {
        if (!completed) Files.deleteIfExists(stagingFile)
    }
}

private fun installVerifiedNative(
    platformDirectory: Path,
    fileName: String,
    staged: StagedNativeLibrary,
): Path {
    val digestDirectory = platformDirectory.resolve(staged.sha256)
    createPrivateDirectories(platformDirectory, digestDirectory)
    val cachedFile = digestDirectory.resolve(fileName).normalize()
    require(cachedFile.parent == digestDirectory) { "Native cache path escapes its digest directory" }

    if (Files.exists(cachedFile, LinkOption.NOFOLLOW_LINKS)) {
        check(!Files.isSymbolicLink(cachedFile)) {
            "Refusing symbolic-link native cache entry: $cachedFile"
        }
        check(Files.isRegularFile(cachedFile, LinkOption.NOFOLLOW_LINKS)) {
            "Refusing non-regular native cache entry: $cachedFile"
        }
        if (cachedFile.sha256() == staged.sha256) {
            setOwnerOnlyFilePermissions(cachedFile, executable = true)
            return cachedFile
        }
        // A cache created by an older Windows build can carry the DOS read-only flag.
        // Restore owner write access before replacing the verified-as-corrupt entry.
        setOwnerOnlyFilePermissions(cachedFile, executable = false)
        Files.delete(cachedFile)
    }

    moveAtomically(staged.path, cachedFile)
    check(cachedFile.sha256() == staged.sha256) { "Native cache verification failed: $cachedFile" }
    setOwnerOnlyFilePermissions(cachedFile, executable = true)
    return cachedFile
}

private fun String.toSafeCacheSegment(): String {
    val safe = replace(Regex("[^A-Za-z0-9_.-]"), "_").trim('.', '_')
    return safe.ifEmpty { "unknown" }.take(MAX_CACHE_SEGMENT_LENGTH)
}

private fun createPrivateDirectories(
    trustedRoot: Path,
    target: Path,
) {
    val root = trustedRoot.toAbsolutePath().normalize()
    val normalizedTarget = target.toAbsolutePath().normalize()
    require(normalizedTarget.startsWith(root)) { "Native cache directory escapes its trusted root" }
    Files.createDirectories(root)
    verifyPrivateDirectory(root)

    var current = root
    root.relativize(normalizedTarget).forEach { segment ->
        current = current.resolve(segment)
        Files.createDirectories(current)
        verifyPrivateDirectory(current)
    }
}

private fun verifyPrivateDirectory(path: Path) {
    check(!Files.isSymbolicLink(path)) { "Refusing symbolic-link native cache directory: $path" }
    check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "Native cache path is not a directory: $path" }
    try {
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    } catch (_: UnsupportedOperationException) {
        path.toFile().apply {
            setReadable(false, false)
            setWritable(false, false)
            setExecutable(false, false)
            setReadable(true, true)
            setWritable(true, true)
            setExecutable(true, true)
        }
    }
}

private fun setOwnerOnlyFilePermissions(
    path: Path,
    executable: Boolean,
) {
    val permissions =
        buildSet {
            add(PosixFilePermission.OWNER_READ)
            if (executable) {
                add(PosixFilePermission.OWNER_EXECUTE)
            } else {
                add(PosixFilePermission.OWNER_WRITE)
            }
        }
    try {
        Files.setPosixFilePermissions(path, permissions)
    } catch (_: UnsupportedOperationException) {
        path.toFile().apply {
            setReadable(false, false)
            setWritable(false, false)
            setExecutable(false, false)
            setReadable(true, true)
            // Removing write access on Windows sets the DOS read-only attribute. Native DLLs
            // do not rely on POSIX executable-only permissions, and retaining owner write
            // access allows a corrupted cache entry to be replaced on the next extraction.
            setWritable(true, true)
            if (executable) {
                setExecutable(true, true)
            }
        }
    }
}

private fun moveAtomically(
    source: Path,
    target: Path,
) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target)
    }
}

private fun Path.sha256(): String {
    val digest = MessageDigest.getInstance(SHA_256)
    Files.newInputStream(this, LinkOption.NOFOLLOW_LINKS).use { input ->
        DigestInputStream(input, digest).use { digestedInput ->
            digestedInput.transferTo(OutputStream.nullOutputStream())
        }
    }
    return digest.digest().toHexString()
}

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
