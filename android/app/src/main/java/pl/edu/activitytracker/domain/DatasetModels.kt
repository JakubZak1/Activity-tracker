package pl.edu.activitytracker.domain

const val DATASET_PROTOCOL_VERSION = 5
const val MAX_REQUEST_ID = 0xFFFF_FFFFL

val REQUIRED_DATASET_CAPABILITIES = setOf(
    "recording",
    "catalog",
    "download",
    "resume",
    "crc32",
    "segmentation",
    "auto_offload",
    "pause_offload",
)

data class RemoteFileIdentity(
    val name: String,
    val sizeBytes: Long,
    val crc32: String,
)

data class DeviceLogFile(
    val name: String,
    val sizeBytes: Long,
    val crc32: String?,
    val isComplete: Boolean,
) {
    val identity: RemoteFileIdentity?
        get() = crc32?.let { RemoteFileIdentity(name, sizeBytes, it) }
}

sealed interface DeviceStatus {
    val freeBytes: Long

    data class Idle(
        val lastFile: RemoteFileIdentity?,
        override val freeBytes: Long,
    ) : DeviceStatus

    data class Recording(
        val label: ActivityType,
        val fileName: String,
        val elapsedMillis: Long,
        val bytesWritten: Long,
        override val freeBytes: Long,
    ) : DeviceStatus

    data class PausedForOffload(
        val label: ActivityType,
        val file: RemoteFileIdentity,
        val elapsedMillis: Long,
        override val freeBytes: Long,
    ) : DeviceStatus

    data class Fault(
        val code: String,
        val activeFileName: String?,
        override val freeBytes: Long,
    ) : DeviceStatus
}

sealed interface DatasetConnectionState {
    data object Offline : DatasetConnectionState
    data object Connecting : DatasetConnectionState
    data object Handshaking : DatasetConnectionState
    data object Synchronizing : DatasetConnectionState
    data class Ready(
        val protocolVersion: Int,
        val capabilities: Set<String>,
        val deviceIdentity: String,
    ) : DatasetConnectionState
    data class Incompatible(val message: String) : DatasetConnectionState
    data class Error(val message: String) : DatasetConnectionState
}

sealed interface CollectionState {
    data object Unknown : CollectionState
    data class Idle(val lastFile: RemoteFileIdentity? = null, val freeBytes: Long? = null) : CollectionState
    data class Starting(val label: ActivityType) : CollectionState
    data class Recording(
        val label: ActivityType,
        val fileName: String,
        val elapsedMillis: Long = 0L,
        val bytesWritten: Long = 0L,
        val freeBytes: Long? = null,
    ) : CollectionState
    data class PausedForOffload(
        val label: ActivityType,
        val file: RemoteFileIdentity,
        val elapsedMillis: Long,
        val freeBytes: Long,
    ) : CollectionState
    data class Stopping(val fileName: String?) : CollectionState
    data class Fault(val code: String, val activeFileName: String?, val freeBytes: Long?) : CollectionState
    data class Error(val message: String) : CollectionState
}

sealed interface CatalogState {
    val files: List<DeviceLogFile>

    data class Idle(override val files: List<DeviceLogFile> = emptyList()) : CatalogState
    data class Loading(override val files: List<DeviceLogFile>) : CatalogState
    data class Loaded(override val files: List<DeviceLogFile>) : CatalogState
    data class Error(
        val message: String,
        override val files: List<DeviceLogFile>,
    ) : CatalogState
}

sealed interface TransferState {
    data object Idle : TransferState
    data class WaitingForFolder(val file: DeviceLogFile) : TransferState
    data class Preparing(val file: DeviceLogFile) : TransferState
    data class AwaitingBegin(val file: DeviceLogFile, val resumeOffset: Long) : TransferState
    data class Receiving(
        val file: DeviceLogFile,
        val receivedBytes: Long,
    ) : TransferState
    data class Finalizing(val file: DeviceLogFile) : TransferState
    data class Completed(val file: RemoteFileIdentity) : TransferState
    data class Interrupted(
        val file: DeviceLogFile?,
        val message: String,
        val canResume: Boolean,
    ) : TransferState
    data class Error(
        val file: DeviceLogFile?,
        val message: String,
        val canResume: Boolean,
    ) : TransferState
}

val TransferState.isBusy: Boolean
    get() = this is TransferState.Preparing ||
        this is TransferState.AwaitingBegin ||
        this is TransferState.Receiving ||
        this is TransferState.Finalizing

data class DatasetState(
    val connection: DatasetConnectionState = DatasetConnectionState.Offline,
    val collection: CollectionState = CollectionState.Unknown,
    val catalog: CatalogState = CatalogState.Idle(),
    val transfer: TransferState = TransferState.Idle,
    val verifiedFiles: Set<RemoteFileIdentity> = emptySet(),
    val dataFolderUri: String? = null,
    val operationMessage: String? = null,
)

sealed interface DeviceControlResponse {
    val requestId: Long

    data class Hello(
        override val requestId: Long,
        val protocolVersion: Int,
        val capabilities: Set<String>,
    ) : DeviceControlResponse

    data class Status(
        override val requestId: Long,
        val value: DeviceStatus,
    ) : DeviceControlResponse

    data class FileEntry(
        override val requestId: Long,
        val value: DeviceLogFile,
    ) : DeviceControlResponse

    data class ListEnd(override val requestId: Long, val count: Int) : DeviceControlResponse

    data class RecordingStarted(
        override val requestId: Long,
        val label: ActivityType,
        val fileName: String,
        val alreadyRecording: Boolean,
    ) : DeviceControlResponse

    data class RecordingStopped(
        override val requestId: Long,
        val file: RemoteFileIdentity?,
        val alreadyStopped: Boolean,
    ) : DeviceControlResponse

    data class DownloadBegin(
        override val requestId: Long,
        val file: RemoteFileIdentity,
        val offset: Long,
    ) : DeviceControlResponse

    data class DownloadEnd(
        override val requestId: Long,
        val file: RemoteFileIdentity,
    ) : DeviceControlResponse

    data class Deleted(
        override val requestId: Long,
        val fileName: String,
        val alreadyDeleted: Boolean,
    ) : DeviceControlResponse

    data class Cancelled(override val requestId: Long) : DeviceControlResponse

    data class Error(
        override val requestId: Long,
        val code: String,
        val details: String? = null,
    ) : DeviceControlResponse
}

data class FileDataFrame(
    val requestId: Long,
    val offset: Long,
    val data: ByteArray,
)

sealed interface DeviceProtocolEvent {
    val generation: Long

    data class Control(
        override val generation: Long,
        val response: DeviceControlResponse,
    ) : DeviceProtocolEvent

    data class FileData(
        override val generation: Long,
        val frame: FileDataFrame,
    ) : DeviceProtocolEvent

    data class Fault(
        override val generation: Long,
        val message: String,
    ) : DeviceProtocolEvent
}
