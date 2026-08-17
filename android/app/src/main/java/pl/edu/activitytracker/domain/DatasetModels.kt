package pl.edu.activitytracker.domain

data class DeviceCollectionStatus(
    val isLogging: Boolean,
    val label: String,
    val currentFile: String?,
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
)

data class DeviceLogFile(
    val name: String,
    val sizeBytes: Long,
    val isActive: Boolean,
)

sealed interface FileTransferState {
    data object Idle : FileTransferState
    data class WaitingForFolder(val fileName: String, val sizeBytes: Long) : FileTransferState
    data class Preparing(val fileName: String, val sizeBytes: Long) : FileTransferState
    data class Downloading(
        val fileName: String,
        val sizeBytes: Long,
        val receivedBytes: Long,
    ) : FileTransferState
    data class Completed(val fileName: String, val sizeBytes: Long) : FileTransferState
    data class Failed(
        val fileName: String?,
        val message: String,
        val canResume: Boolean,
    ) : FileTransferState
}

val FileTransferState.isBusy: Boolean
    get() = this is FileTransferState.Preparing || this is FileTransferState.Downloading

sealed interface DeviceControlResponse {
    data class Status(val value: DeviceCollectionStatus) : DeviceControlResponse
    data class FileEntry(val value: DeviceLogFile) : DeviceControlResponse
    data class ListEnd(val count: Int) : DeviceControlResponse
    data class LabelSet(val label: String) : DeviceControlResponse
    data class LoggingStarted(val fileName: String) : DeviceControlResponse
    data class LoggingStopped(val fileName: String?, val sizeBytes: Long?) : DeviceControlResponse
    data class DownloadBegin(val fileName: String, val sizeBytes: Long, val offset: Long) : DeviceControlResponse
    data class DownloadEnd(val fileName: String, val sizeBytes: Long) : DeviceControlResponse
    data class Deleted(val fileName: String) : DeviceControlResponse
    data object Cancelled : DeviceControlResponse
    data class Error(val code: String, val details: String?) : DeviceControlResponse
    data class Unknown(val payload: String) : DeviceControlResponse
}

data class FileDataFrame(
    val offset: Long,
    val data: ByteArray,
)

sealed interface DeviceProtocolEvent {
    data class Control(val response: DeviceControlResponse) : DeviceProtocolEvent
    data class FileData(val frame: FileDataFrame) : DeviceProtocolEvent
}
