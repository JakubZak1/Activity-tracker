package pl.edu.activitytracker.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import java.io.FileOutputStream

class LogFileStore(context: Context) {
    private val appContext = context.applicationContext

    sealed interface PrepareResult {
        data class Ready(val sink: DownloadSink, val offset: Long) : PrepareResult
        data object AlreadyComplete : PrepareResult
        data class Failure(val message: String) : PrepareResult
    }

    sealed interface CompleteResult {
        data object Success : CompleteResult
        data class Failure(val message: String) : CompleteResult
    }

    class DownloadSink internal constructor(
        val fileName: String,
        val expectedSize: Long,
        internal val tree: DocumentFile,
        internal val partialDocument: DocumentFile,
        private val descriptor: ParcelFileDescriptor,
        private val output: FileOutputStream,
        initialPosition: Long,
    ) {
        var position: Long = initialPosition
            private set

        fun write(bytes: ByteArray) {
            output.write(bytes)
            position += bytes.size
        }

        fun close() {
            runCatching { output.flush() }
            runCatching { output.close() }
            runCatching { descriptor.close() }
        }
    }

    fun prepare(treeUri: String, fileName: String, expectedSize: Long): PrepareResult {
        if (!isSafeFileName(fileName) || expectedSize < 0L) {
            return PrepareResult.Failure("Invalid file metadata")
        }
        val tree = documentTree(treeUri) ?: return PrepareResult.Failure("Selected folder is unavailable")

        val finalDocument = tree.findFile(fileName)
        if (finalDocument != null) {
            return if (finalDocument.isFile && finalDocument.length() == expectedSize) {
                PrepareResult.AlreadyComplete
            } else {
                PrepareResult.Failure("A file with this name already exists with a different size")
            }
        }

        val partialName = "$fileName.part"
        var partial = tree.findFile(partialName)
        if (partial != null && (!partial.isFile || partial.length() > expectedSize)) {
            if (!partial.delete()) {
                return PrepareResult.Failure("Invalid partial file could not be removed")
            }
            partial = null
        }
        if (partial == null) {
            partial = tree.createFile(PARTIAL_MIME_TYPE, partialName)
                ?: return PrepareResult.Failure("Could not create partial file")
        }

        val offset = partial.length()
        val descriptor = appContext.contentResolver.openFileDescriptor(partial.uri, "rw")
            ?: return PrepareResult.Failure("Could not open partial file")
        return try {
            val output = FileOutputStream(descriptor.fileDescriptor)
            output.channel.position(offset)
            output.channel.truncate(offset)
            PrepareResult.Ready(
                sink = DownloadSink(
                    fileName = fileName,
                    expectedSize = expectedSize,
                    tree = tree,
                    partialDocument = partial,
                    descriptor = descriptor,
                    output = output,
                    initialPosition = offset,
                ),
                offset = offset,
            )
        } catch (error: Exception) {
            runCatching { descriptor.close() }
            PrepareResult.Failure(error.message ?: "Could not prepare partial file")
        }
    }

    fun complete(sink: DownloadSink): CompleteResult {
        sink.close()
        val partialName = "${sink.fileName}.part"
        val partial = sink.tree.findFile(partialName) ?: sink.partialDocument
        if (partial.length() != sink.expectedSize) {
            return CompleteResult.Failure("Downloaded size does not match device file")
        }

        val existing = sink.tree.findFile(sink.fileName)
        if (existing != null) {
            return if (existing.isFile && existing.length() == sink.expectedSize) {
                partial.delete()
                CompleteResult.Success
            } else {
                CompleteResult.Failure("A file with this name already exists with a different size")
            }
        }

        if (!partial.renameTo(sink.fileName)) {
            return CompleteResult.Failure("Could not finalize downloaded file")
        }
        val finalDocument = sink.tree.findFile(sink.fileName)
        return if (finalDocument?.isFile == true && finalDocument.length() == sink.expectedSize) {
            CompleteResult.Success
        } else {
            CompleteResult.Failure("Final file verification failed")
        }
    }

    fun isComplete(treeUri: String, fileName: String, expectedSize: Long): Boolean {
        if (!isSafeFileName(fileName)) return false
        return documentTree(treeUri)
            ?.findFile(fileName)
            ?.let { it.isFile && it.length() == expectedSize }
            ?: false
    }

    private fun documentTree(uri: String): DocumentFile? = runCatching {
        DocumentFile.fromTreeUri(appContext, Uri.parse(uri))
    }.getOrNull()

    private fun isSafeFileName(fileName: String): Boolean =
        fileName.matches(Regex("[a-z0-9_.]+\\.csv")) && !fileName.contains("..")

    companion object {
        private const val PARTIAL_MIME_TYPE = "application/octet-stream"
    }
}
