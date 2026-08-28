package pl.edu.activitytracker.domain

sealed interface DeviceCommand {
    val requestId: Long
    val line: String

    data class Hello(override val requestId: Long) : DeviceCommand {
        override val line = "hello,$requestId"
    }

    data class Status(override val requestId: Long) : DeviceCommand {
        override val line = "status,$requestId"
    }

    data class Identify(
        override val requestId: Long,
        val color: DeviceLedColor,
        val durationMillis: Long = 5_000L,
    ) : DeviceCommand {
        override val line = "identify,$requestId,${color.wireName},$durationMillis"
    }

    data class RecordStart(
        override val requestId: Long,
        val activity: ActivityType,
    ) : DeviceCommand {
        override val line = "record_start,$requestId,${activity.wireName}"
    }

    data class RecordStop(override val requestId: Long) : DeviceCommand {
        override val line = "record_stop,$requestId"
    }

    data class ListLogs(override val requestId: Long) : DeviceCommand {
        override val line = "list,$requestId"
    }

    data class Download(
        override val requestId: Long,
        val fileName: String,
        val offset: Long,
    ) : DeviceCommand {
        override val line = "download,$requestId,$fileName,$offset"
    }

    data class Cancel(override val requestId: Long) : DeviceCommand {
        override val line = "cancel,$requestId"
    }

    data class Delete(
        override val requestId: Long,
        val file: RemoteFileIdentity,
    ) : DeviceCommand {
        override val line = "delete,$requestId,${file.name},${file.sizeBytes},${file.crc32}"
    }
}
