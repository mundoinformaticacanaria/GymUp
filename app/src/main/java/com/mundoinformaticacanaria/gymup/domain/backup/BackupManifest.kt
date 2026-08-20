package com.mundoinformaticacanaria.gymup.domain.backup

import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackupManifestV1(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("exported_at") val exportedAt: String,
    val files: List<BackupFileEntry>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val MANIFEST_PATH = "manifest.json"
        const val DATA_PATH = "data.json"
    }
}

@Serializable
data class BackupFileEntry(
    val path: String,
    val bytes: Long,
    val sha256: String,
)

sealed interface BackupValidationResult {
    data object Valid : BackupValidationResult
    data class UnsupportedVersion(val version: Int) : BackupValidationResult
    data class MissingFile(val path: String) : BackupValidationResult
    data class SizeMismatch(val path: String, val expected: Long, val actual: Long) : BackupValidationResult
    data class ChecksumMismatch(val path: String) : BackupValidationResult
    data class InvalidManifest(val reason: String) : BackupValidationResult
}

object BackupManifestCodec {
    private val json = Json {
        prettyPrint = true
        explicitNulls = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun create(
        files: Map<String, ByteArray>,
        exportedAt: Instant = Instant.now(),
    ): BackupManifestV1 {
        require(BackupManifestV1.DATA_PATH in files) { "El backup debe incluir ${BackupManifestV1.DATA_PATH}" }
        require(BackupManifestV1.MANIFEST_PATH !in files) { "El manifiesto no debe incluirse a sí mismo" }
        val entries = files.entries
            .sortedBy { it.key }
            .map { (path, bytes) ->
                require(isSafeRelativePath(path)) { "Ruta de backup no válida: $path" }
                BackupFileEntry(
                    path = path,
                    bytes = bytes.size.toLong(),
                    sha256 = sha256(bytes),
                )
            }
        return BackupManifestV1(exportedAt = exportedAt.toString(), files = entries)
    }

    fun encode(manifest: BackupManifestV1): String = json.encodeToString(manifest)

    fun decode(raw: String): Result<BackupManifestV1> = runCatching { json.decodeFromString(raw) }

    fun validate(manifest: BackupManifestV1, files: Map<String, ByteArray>): BackupValidationResult {
        if (manifest.schemaVersion != BackupManifestV1.SCHEMA_VERSION) {
            return BackupValidationResult.UnsupportedVersion(manifest.schemaVersion)
        }
        if (manifest.files.none { it.path == BackupManifestV1.DATA_PATH }) {
            return BackupValidationResult.MissingFile(BackupManifestV1.DATA_PATH)
        }
        val duplicatePath = manifest.files.groupingBy { it.path }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        if (duplicatePath != null) {
            return BackupValidationResult.InvalidManifest("Ruta duplicada: $duplicatePath")
        }
        for (entry in manifest.files) {
            if (!isSafeRelativePath(entry.path)) {
                return BackupValidationResult.InvalidManifest("Ruta no válida: ${entry.path}")
            }
            if (entry.bytes < 0 || !entry.sha256.matches(Regex("[0-9a-f]{64}"))) {
                return BackupValidationResult.InvalidManifest("Metadatos no válidos: ${entry.path}")
            }
            val bytes = files[entry.path] ?: return BackupValidationResult.MissingFile(entry.path)
            if (bytes.size.toLong() != entry.bytes) {
                return BackupValidationResult.SizeMismatch(entry.path, entry.bytes, bytes.size.toLong())
            }
            if (sha256(bytes) != entry.sha256) {
                return BackupValidationResult.ChecksumMismatch(entry.path)
            }
        }
        return BackupValidationResult.Valid
    }

    internal fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\')) return false
        if ('\\' in path) return false
        val segments = path.split('/')
        return segments.none { it.isBlank() || it == "." || it == ".." }
    }

    internal fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it) }
}
