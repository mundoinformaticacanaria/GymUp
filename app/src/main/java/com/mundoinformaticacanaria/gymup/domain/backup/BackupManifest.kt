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
    val format: String = FORMAT,
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("exported_at") val exportedAt: String,
    @SerialName("app_version") val appVersion: String,
    val data: BackupDataFile,
    val images: List<BackupImageFile>,
) {
    companion object {
        const val FORMAT = "gymup-backup"
        const val SCHEMA_VERSION = 1
        const val MANIFEST_PATH = "manifest.json"
        const val DATA_PATH = "data.json"
    }
}

@Serializable
data class BackupDataFile(
    val path: String = BackupManifestV1.DATA_PATH,
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
)

@Serializable
data class BackupImageFile(
    @SerialName("storage_key") val storageKey: String,
    val path: String,
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
)

sealed interface BackupValidationResult {
    data object Valid : BackupValidationResult
    data class UnsupportedFormat(val format: String) : BackupValidationResult
    data class UnsupportedVersion(val version: Int) : BackupValidationResult
    data class MissingFile(val path: String) : BackupValidationResult
    data class SizeMismatch(val path: String, val expected: Long, val actual: Long) : BackupValidationResult
    data class ChecksumMismatch(val path: String) : BackupValidationResult
    data class InvalidManifest(val reason: String) : BackupValidationResult
}

object BackupManifestCodec {
    private val storageKeyPattern = Regex("^user:[0-9a-fA-F-]{36}$")
    private val json = Json {
        prettyPrint = true
        explicitNulls = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun create(
        data: ByteArray,
        images: Map<String, ByteArray>,
        appVersion: String,
        exportedAt: Instant = Instant.now(),
    ): BackupManifestV1 {
        require(appVersion.isNotBlank()) { "La versión de la app es obligatoria" }
        val imageEntries = images.entries.sortedBy { it.key }.map { (storageKey, bytes) ->
            require(storageKeyPattern.matches(storageKey)) { "storage_key no válido: $storageKey" }
            val path = imagePath(storageKey)
            BackupImageFile(
                storageKey = storageKey,
                path = path,
                sha256 = sha256(bytes),
                sizeBytes = bytes.size.toLong(),
            )
        }
        return BackupManifestV1(
            exportedAt = exportedAt.toString(),
            appVersion = appVersion,
            data = BackupDataFile(
                sha256 = sha256(data),
                sizeBytes = data.size.toLong(),
            ),
            images = imageEntries,
        )
    }

    fun encode(manifest: BackupManifestV1): String = json.encodeToString(manifest)

    fun decode(raw: String): Result<BackupManifestV1> = runCatching { json.decodeFromString(raw) }

    fun validate(manifest: BackupManifestV1, files: Map<String, ByteArray>): BackupValidationResult {
        if (manifest.format != BackupManifestV1.FORMAT) {
            return BackupValidationResult.UnsupportedFormat(manifest.format)
        }
        if (manifest.schemaVersion != BackupManifestV1.SCHEMA_VERSION) {
            return BackupValidationResult.UnsupportedVersion(manifest.schemaVersion)
        }
        if (manifest.appVersion.isBlank()) {
            return BackupValidationResult.InvalidManifest("app_version vacío")
        }
        if (manifest.data.path != BackupManifestV1.DATA_PATH) {
            return BackupValidationResult.InvalidManifest("Ruta data no válida: ${manifest.data.path}")
        }
        val duplicateStorageKey = manifest.images.groupingBy { it.storageKey }.eachCount().entries
            .firstOrNull { it.value > 1 }?.key
        if (duplicateStorageKey != null) {
            return BackupValidationResult.InvalidManifest("storage_key duplicado: $duplicateStorageKey")
        }
        val duplicatePath = manifest.images.groupingBy { it.path }.eachCount().entries
            .firstOrNull { it.value > 1 }?.key
        if (duplicatePath != null) {
            return BackupValidationResult.InvalidManifest("Ruta de imagen duplicada: $duplicatePath")
        }

        val dataResult = validateFile(
            path = manifest.data.path,
            expectedSize = manifest.data.sizeBytes,
            expectedSha = manifest.data.sha256,
            files = files,
        )
        if (dataResult != BackupValidationResult.Valid) return dataResult

        for (image in manifest.images) {
            if (!storageKeyPattern.matches(image.storageKey)) {
                return BackupValidationResult.InvalidManifest("storage_key no válido: ${image.storageKey}")
            }
            if (image.path != imagePath(image.storageKey) || !isSafeRelativePath(image.path)) {
                return BackupValidationResult.InvalidManifest("Ruta de imagen no válida: ${image.path}")
            }
            val result = validateFile(image.path, image.sizeBytes, image.sha256, files)
            if (result != BackupValidationResult.Valid) return result
        }
        return BackupValidationResult.Valid
    }

    fun declaredPaths(manifest: BackupManifestV1): Set<String> =
        buildSet {
            add(manifest.data.path)
            manifest.images.forEach { add(it.path) }
        }

    fun imagePath(storageKey: String): String = "images/${storageKey.removePrefix("user:")}"

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

    private fun validateFile(
        path: String,
        expectedSize: Long,
        expectedSha: String,
        files: Map<String, ByteArray>,
    ): BackupValidationResult {
        if (!isSafeRelativePath(path) || expectedSize < 0 || !expectedSha.matches(Regex("[0-9a-f]{64}"))) {
            return BackupValidationResult.InvalidManifest("Metadatos no válidos: $path")
        }
        val bytes = files[path] ?: return BackupValidationResult.MissingFile(path)
        if (bytes.size.toLong() != expectedSize) {
            return BackupValidationResult.SizeMismatch(path, expectedSize, bytes.size.toLong())
        }
        if (sha256(bytes) != expectedSha) {
            return BackupValidationResult.ChecksumMismatch(path)
        }
        return BackupValidationResult.Valid
    }
}
