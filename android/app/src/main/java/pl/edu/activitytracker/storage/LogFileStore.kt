package pl.edu.activitytracker.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.CRC32
import pl.edu.activitytracker.domain.DATASET_PROTOCOL_VERSION
import pl.edu.activitytracker.domain.DeviceLogFile
import pl.edu.activitytracker.domain.RemoteFileIdentity

interface DatasetFileStore {
    sealed interface PrepareResult {
        data class Ready(val sink: DownloadSink, val offset: Long) : PrepareResult
        data object AlreadyComplete : PrepareResult
        data class Failure(val message: String) : PrepareResult
    }

    sealed interface CompleteResult {
        data object Success : CompleteResult
        data class Failure(val message: String) : CompleteResult
    }

    interface DownloadSink {
        val file: RemoteFileIdentity
        val position: Long
        fun write(bytes: ByteArray)
        fun close()
    }

    fun prepare(
        treeUri: String,
        deviceIdentity: String,
        file: RemoteFileIdentity,
    ): PrepareResult

    fun complete(sink: DownloadSink): CompleteResult

    fun isVerified(treeUri: String, file: RemoteFileIdentity): Boolean

    fun isFolderAvailable(treeUri: String): Boolean = true
}

class LogFileStore(context: Context) : DatasetFileStore {
    private val appContext = context.applicationContext

    private class AndroidDownloadSink(
        override val file: RemoteFileIdentity,
        val tree: DocumentFile,
        val partialDocument: DocumentFile,
        val metadataDocument: DocumentFile,
        private val descriptor: ParcelFileDescriptor,
        private val output: FileOutputStream,
        initialPosition: Long,
    ) : DatasetFileStore.DownloadSink {
        override var position: Long = initialPosition
            private set
        private var closed = false
        var closeFailure: String? = null
            private set

        override fun write(bytes: ByteArray) {
            check(!closed) { "Download sink is closed" }
            output.write(bytes)
            position += bytes.size
        }

        override fun close() {
            if (closed) return
            closed = true
            fun recordFailure(operation: String, error: Throwable) {
                if (closeFailure == null) {
                    closeFailure = "$operation failed: ${error.message ?: error::class.simpleName}"
                }
            }
            try {
                output.flush()
            } catch (error: Exception) {
                recordFailure("Flush", error)
            }
            try {
                descriptor.fileDescriptor.sync()
            } catch (error: Exception) {
                recordFailure("File sync", error)
            }
            try {
                output.close()
            } catch (error: Exception) {
                recordFailure("Close", error)
            }
            // FileOutputStream owns the same descriptor. This is cleanup only; a
            // second close may legitimately report that it is already closed.
            runCatching { descriptor.close() }
        }
    }

    override fun prepare(
        treeUri: String,
        deviceIdentity: String,
        file: RemoteFileIdentity,
    ): DatasetFileStore.PrepareResult {
        if (!isSafeFile(file)) return DatasetFileStore.PrepareResult.Failure("Invalid remote file metadata")
        val tree = documentTree(treeUri)
            ?: return DatasetFileStore.PrepareResult.Failure("Selected folder is unavailable or permission was revoked")

        val partialName = "${file.name}.part"
        val metadataName = "${file.name}.part.meta"
        val expectedMetadata = metadataText(deviceIdentity, file)

        tree.findFile(file.name)?.let { finalDocument ->
            return if (verifyDocument(finalDocument, file)) {
                val staleMetadata = tree.findFile(metadataName)
                if (staleMetadata != null && readText(staleMetadata) == expectedMetadata) {
                    tree.findFile(partialName)?.delete()
                    staleMetadata.delete()
                }
                DatasetFileStore.PrepareResult.AlreadyComplete
            } else {
                DatasetFileStore.PrepareResult.Failure("A different local file already uses ${file.name}")
            }
        }

        var partial = tree.findFile(partialName)
        var metadata = tree.findFile(metadataName)

        if (partial == null && metadata == null) {
            partial = tree.createFile(BINARY_MIME_TYPE, partialName)
                ?: return DatasetFileStore.PrepareResult.Failure("Could not create partial file")
            metadata = tree.createFile(TEXT_MIME_TYPE, metadataName)
            if (metadata == null || !writeText(metadata, expectedMetadata)) {
                partial.delete()
                metadata?.delete()
                return DatasetFileStore.PrepareResult.Failure("Could not create partial-file metadata")
            }
        } else if (partial == null || metadata == null) {
            return DatasetFileStore.PrepareResult.Failure("Partial file metadata is incomplete; remove the stale .part files")
        } else if (readText(metadata) != expectedMetadata) {
            return DatasetFileStore.PrepareResult.Failure("Partial file belongs to different remote data")
        }

        if (!partial.isFile || !metadata.isFile || partial.length() > file.sizeBytes) {
            return DatasetFileStore.PrepareResult.Failure("Partial file has an invalid size or type")
        }

        val offset = partial.length()
        val descriptor = appContext.contentResolver.openFileDescriptor(partial.uri, "rw")
            ?: return DatasetFileStore.PrepareResult.Failure("Could not open partial file")
        return try {
            val output = FileOutputStream(descriptor.fileDescriptor)
            output.channel.position(offset)
            output.channel.truncate(offset)
            DatasetFileStore.PrepareResult.Ready(
                AndroidDownloadSink(
                    file = file,
                    tree = tree,
                    partialDocument = partial,
                    metadataDocument = metadata,
                    descriptor = descriptor,
                    output = output,
                    initialPosition = offset,
                ),
                offset,
            )
        } catch (error: Exception) {
            runCatching { descriptor.close() }
            DatasetFileStore.PrepareResult.Failure(error.message ?: "Could not prepare partial file")
        }
    }

    override fun complete(sink: DatasetFileStore.DownloadSink): DatasetFileStore.CompleteResult {
        val androidSink = sink as? AndroidDownloadSink
            ?: return DatasetFileStore.CompleteResult.Failure("Unsupported download sink")
        androidSink.close()
        androidSink.closeFailure?.let { failure ->
            return DatasetFileStore.CompleteResult.Failure(
                "Could not durably save the partial file: $failure",
            )
        }

        if (!verifyDocument(androidSink.partialDocument, androidSink.file)) {
            androidSink.partialDocument.delete()
            androidSink.metadataDocument.delete()
            return DatasetFileStore.CompleteResult.Failure("Downloaded size or CRC32 does not match the device file")
        }

        androidSink.tree.findFile(androidSink.file.name)?.let { existing ->
            return if (verifyDocument(existing, androidSink.file)) {
                androidSink.partialDocument.delete()
                androidSink.metadataDocument.delete()
                DatasetFileStore.CompleteResult.Success
            } else {
                DatasetFileStore.CompleteResult.Failure("A different local file already uses ${androidSink.file.name}")
            }
        }

        if (!androidSink.partialDocument.renameTo(androidSink.file.name)) {
            return DatasetFileStore.CompleteResult.Failure("Could not finalize downloaded file")
        }
        val finalDocument = androidSink.tree.findFile(androidSink.file.name)
            ?: androidSink.partialDocument.takeIf { it.name == androidSink.file.name }
        return if (finalDocument != null && verifyDocument(finalDocument, androidSink.file)) {
            androidSink.metadataDocument.delete()
            DatasetFileStore.CompleteResult.Success
        } else {
            // Best effort rollback keeps the sidecar and resumable name together.
            // If the provider cannot roll back, the next prepare() still detects
            // and verifies the final name before allowing a remote delete.
            finalDocument?.renameTo("${androidSink.file.name}.part")
            DatasetFileStore.CompleteResult.Failure("Final file verification failed")
        }
    }

    override fun isVerified(treeUri: String, file: RemoteFileIdentity): Boolean {
        if (!isSafeFile(file)) return false
        return documentTree(treeUri)?.findFile(file.name)?.let { verifyDocument(it, file) } ?: false
    }

    override fun isFolderAvailable(treeUri: String): Boolean = documentTree(treeUri) != null

    private fun verifyDocument(document: DocumentFile, file: RemoteFileIdentity): Boolean {
        if (!document.isFile || document.length() != file.sizeBytes) return false
        val actual = runCatching {
            appContext.contentResolver.openInputStream(document.uri)?.use { input ->
                val crc = CRC32()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    crc.update(buffer, 0, count)
                }
                String.format(Locale.US, "%08X", crc.value)
            }
        }.getOrNull()
        return actual == file.crc32
    }

    private fun metadataText(deviceIdentity: String, file: RemoteFileIdentity): String {
        val encodedDevice = Base64.encodeToString(
            deviceIdentity.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )
        return buildString {
            appendLine("protocol=$DATASET_PROTOCOL_VERSION")
            appendLine("device=$encodedDevice")
            appendLine("name=${file.name}")
            appendLine("size=${file.sizeBytes}")
            appendLine("crc32=${file.crc32}")
        }
    }

    private fun writeText(document: DocumentFile, value: String): Boolean = runCatching {
        appContext.contentResolver.openOutputStream(document.uri, "wt")?.use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.flush()
        } != null
    }.getOrDefault(false)

    private fun readText(document: DocumentFile): String? = runCatching {
        appContext.contentResolver.openInputStream(document.uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    private fun documentTree(uri: String): DocumentFile? = runCatching {
        DocumentFile.fromTreeUri(appContext, Uri.parse(uri))
            ?.takeIf { it.exists() && it.isDirectory && it.canRead() && it.canWrite() }
    }.getOrNull()

    private fun isSafeFile(file: RemoteFileIdentity): Boolean =
        file.name.matches(Regex("[a-z0-9_]+_[0-9]+\\.csv")) &&
            file.sizeBytes in 0L..0xFFFF_FFFFL &&
            file.crc32.matches(Regex("[0-9A-F]{8}"))

    companion object {
        private const val BINARY_MIME_TYPE = "application/octet-stream"
        private const val TEXT_MIME_TYPE = "text/plain"
    }
}
