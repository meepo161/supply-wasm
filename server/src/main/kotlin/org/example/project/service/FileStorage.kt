package org.example.project.service

import java.io.File
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.security.MessageDigest
import java.util.UUID

/**
 * Порт файлового хранилища. Реализация по умолчанию — локальная файловая система,
 * заменяется через DI без изменения сервисного слоя.
 */
data class StoredFile(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String
)

interface FileStorage {
    fun store(relativePath: String, content: ByteArray): StoredFile
    fun read(relativePath: String): ByteArray
    fun delete(relativePath: String)
}

class LocalFileStorage(rootPath: String) : FileStorage {

    private val root: File = File(rootPath).canonicalFile

    init {
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Cannot create storage root: ${root.path}")
        }
    }

    override fun store(relativePath: String, content: ByteArray): StoredFile {
        val target = resolve(relativePath)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part-${UUID.randomUUID()}")
        try {
            temp.writeBytes(content)
            if (target.exists()) {
                throw IOException("Storage path already exists: $relativePath")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = false)
                if (!temp.delete()) {
                    target.delete()
                    throw IOException("Cannot move uploaded file into place: $relativePath")
                }
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
        return StoredFile(
            relativePath = relativePath,
            sizeBytes = content.size.toLong(),
            sha256 = sha256Hex(content)
        )
    }

    override fun read(relativePath: String): ByteArray {
        val target = resolve(relativePath)
        if (!target.isFile) {
            throw NoSuchFileException("Stored file not found: $relativePath")
        }
        return target.readBytes()
    }

    override fun delete(relativePath: String) {
        val target = resolve(relativePath)
        if (target.exists() && !target.delete()) {
            throw IOException("Cannot delete stored file: $relativePath")
        }
    }

    private fun resolve(relativePath: String): File {
        val target = File(root, relativePath).canonicalFile
        if (target.path != root.path && !target.path.startsWith(root.path + File.separator)) {
            throw IOException("Storage path escapes the storage root: $relativePath")
        }
        return target
    }

    private fun sha256Hex(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
