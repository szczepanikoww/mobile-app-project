package com.trainingapp.server.media

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** The upload exceeded the allowed size; nothing is kept. */
class TooLargeException : RuntimeException("Upload too large")

/** Object storage for media bytes. Local disk in development; S3 later behind the same interface. */
interface MediaStorage {
    /** Streams [input] to [key]; throws [TooLargeException] (and keeps nothing) past [maxBytes]. Returns the byte count. */
    fun write(key: String, input: InputStream, maxBytes: Long): Long
    fun read(key: String): InputStream?
    fun exists(key: String): Boolean
}

class LocalMediaStorage(dir: String) : MediaStorage {
    private val root: Path = Paths.get(dir).toAbsolutePath().normalize()

    private fun path(key: String): Path {
        val p = root.resolve(key).normalize()
        require(p.startsWith(root)) { "Invalid key" }
        return p
    }

    override fun write(key: String, input: InputStream, maxBytes: Long): Long {
        val target = path(key)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "upload-", ".part")
        try {
            var total = 0L
            Files.newOutputStream(temp).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw TooLargeException()
                    out.write(buffer, 0, n)
                }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            return total
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun read(key: String): InputStream? = path(key).takeIf { Files.isRegularFile(it) }?.let { Files.newInputStream(it) }

    override fun exists(key: String): Boolean = Files.isRegularFile(path(key))
}
