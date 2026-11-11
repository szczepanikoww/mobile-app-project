package com.trainingapp.server.media

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Private object storage behind an interface (plan §2: S3-compatible; MinIO locally). Keys are opaque
 * (`media/<id>`), never names or user data. [LocalDiskStorage] is the local/test implementation; an S3 adapter
 * implements the same five calls.
 */
interface ObjectStorage {
    /** Bytes stored so far, or null when the object does not exist. */
    fun size(key: String): Long?

    /** Appends [bytes] at [offset]; the offset must equal the current size (resumable upload). Returns the new size. */
    fun append(key: String, offset: Long, bytes: InputStream, maxBytes: Long): Long

    fun file(key: String): File?

    fun read(key: String, maxBytes: Int): ByteArray?

    fun delete(key: String)
}

class LocalDiskStorage(root: String) : ObjectStorage {
    private val base = File(root).absoluteFile.also { it.mkdirs() }

    private fun path(key: String): File {
        require(Regex("[a-z]+/[0-9a-f-]{36}(\\.[a-z]+)?").matches(key)) { "invalid storage key" }
        return File(base, key)
    }

    override fun size(key: String): Long? = path(key).takeIf { it.exists() }?.length()

    @Synchronized
    override fun append(key: String, offset: Long, bytes: InputStream, maxBytes: Long): Long {
        val file = path(key)
        file.parentFile.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() != offset) throw IllegalStateException("offset mismatch")
            raf.seek(offset)
            val buffer = ByteArray(64 * 1024)
            var written = 0L
            while (true) {
                val n = bytes.read(buffer)
                if (n < 0) break
                written += n
                if (offset + written > maxBytes) throw IllegalArgumentException("too large")
                raf.write(buffer, 0, n)
            }
            return raf.length()
        }
    }

    override fun file(key: String): File? = path(key).takeIf { it.exists() }

    override fun read(key: String, maxBytes: Int): ByteArray? = path(key).takeIf { it.exists() }?.inputStream()?.use { it.readNBytes(maxBytes) }

    override fun delete(key: String) {
        path(key).delete()
    }
}

/**
 * Reads the duration of an MP4/QuickTime file from its `moov/mvhd` box, wherever `moov` is. Null when the file is not
 * an ISO-BMFF container or has no movie header.
 */
object Mp4Duration {
    fun durationMs(file: File): Long? = RandomAccessFile(file, "r").use { raf -> findMvhd(raf, 0, raf.length()) }

    private fun findMvhd(raf: RandomAccessFile, start: Long, end: Long): Long? {
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { raf.readFully(it) }.decodeToString()
            var header = 8L
            if (size == 1L) { size = raf.readLong(); header = 16 }
            if (size == 0L) size = end - pos
            if (size < header || pos + size > end) return null
            when (type) {
                "moov" -> return findMvhd(raf, pos + header, pos + size)
                "mvhd" -> {
                    val version = raf.readUnsignedByte()
                    raf.skipBytes(3)
                    return if (version == 1) {
                        raf.skipBytes(16)
                        val scale = raf.readInt().toLong()
                        val duration = raf.readLong()
                        if (scale > 0) duration * 1000 / scale else null
                    } else {
                        raf.skipBytes(8)
                        val scale = raf.readInt().toLong() and 0xFFFFFFFFL
                        val duration = raf.readInt().toLong() and 0xFFFFFFFFL
                        if (scale > 0) duration * 1000 / scale else null
                    }
                }
            }
            pos += size
        }
        return null
    }
}
