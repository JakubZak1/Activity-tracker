package pl.edu.activitytracker.ble

import pl.edu.activitytracker.domain.DeviceCollectionStatus
import pl.edu.activitytracker.domain.DeviceControlResponse
import pl.edu.activitytracker.domain.DeviceLogFile
import pl.edu.activitytracker.domain.FileDataFrame

object BleDatasetProtocol {
    fun parseControlLine(payload: String): DeviceControlResponse? {
        val line = payload.trim()
        if (line.isEmpty()) return null
        val parts = line.split(",")

        return when (parts.firstOrNull()) {
            "status" -> parseStatus(parts)
            "file" -> parseFile(parts)
            "list_end" -> parts.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it >= 0 }
                ?.let(DeviceControlResponse::ListEnd)
            "download_begin" -> parseDownloadBegin(parts)
            "download_end" -> parseDownloadEnd(parts)
            "error" -> DeviceControlResponse.Error(
                code = parts.getOrNull(1).orEmpty().ifBlank { "unknown" },
                details = parts.drop(2).joinToString(",").ifBlank { null },
            )
            "ok" -> parseOk(parts)
            else -> DeviceControlResponse.Unknown(line)
        }
    }

    fun parseFileFrame(payload: ByteArray): FileDataFrame? {
        if (payload.size < FILE_FRAME_HEADER_SIZE) return null
        val offset =
            (payload[0].toLong() and 0xFF) or
                ((payload[1].toLong() and 0xFF) shl 8) or
                ((payload[2].toLong() and 0xFF) shl 16) or
                ((payload[3].toLong() and 0xFF) shl 24)
        return FileDataFrame(
            offset = offset,
            data = payload.copyOfRange(FILE_FRAME_HEADER_SIZE, payload.size),
        )
    }

    private fun parseStatus(parts: List<String>): DeviceControlResponse? {
        if (parts.size != 7) return null
        val isLogging = when (parts[1]) {
            "on" -> true
            "off" -> false
            else -> return null
        }
        val total = parts[4].toLongOrNull() ?: return null
        val used = parts[5].toLongOrNull() ?: return null
        val free = parts[6].toLongOrNull() ?: return null
        if (total < 0L || used < 0L || free < 0L) return null
        return DeviceControlResponse.Status(
            DeviceCollectionStatus(
                isLogging = isLogging,
                label = parts[2],
                currentFile = parts[3].takeUnless { it == "none" },
                totalBytes = total,
                usedBytes = used,
                freeBytes = free,
            ),
        )
    }

    private fun parseFile(parts: List<String>): DeviceControlResponse? {
        if (parts.size != 4) return null
        val size = parts[2].toLongOrNull() ?: return null
        if (size < 0L) return null
        val isActive = when (parts[3]) {
            "active" -> true
            "closed" -> false
            else -> return null
        }
        return DeviceControlResponse.FileEntry(
            DeviceLogFile(
                name = parts[1],
                sizeBytes = size,
                isActive = isActive,
            ),
        )
    }

    private fun parseDownloadBegin(parts: List<String>): DeviceControlResponse? {
        if (parts.size != 4) return null
        val size = parts[2].toLongOrNull() ?: return null
        val offset = parts[3].toLongOrNull() ?: return null
        if (size < 0L || offset < 0L || offset > size) return null
        return DeviceControlResponse.DownloadBegin(parts[1], size, offset)
    }

    private fun parseDownloadEnd(parts: List<String>): DeviceControlResponse? {
        if (parts.size != 3) return null
        val size = parts[2].toLongOrNull() ?: return null
        if (size < 0L) return null
        return DeviceControlResponse.DownloadEnd(parts[1], size)
    }

    private fun parseOk(parts: List<String>): DeviceControlResponse? {
        return when (parts.getOrNull(1)) {
            "label" -> parts.getOrNull(2)?.let(DeviceControlResponse::LabelSet)
            "logging_started", "logging_already_running" ->
                parts.getOrNull(2)?.let(DeviceControlResponse::LoggingStarted)
            "logging_stopped" -> {
                val name = parts.getOrNull(2) ?: return null
                val size = parts.getOrNull(3)?.toLongOrNull() ?: return null
                if (size < 0L) return null
                DeviceControlResponse.LoggingStopped(name, size)
            }
            "logging_already_stopped" -> DeviceControlResponse.LoggingStopped(null, null)
            "deleted" -> parts.getOrNull(2)?.let(DeviceControlResponse::Deleted)
            "cancelled" -> DeviceControlResponse.Cancelled
            else -> DeviceControlResponse.Unknown(parts.joinToString(","))
        }
    }

    private const val FILE_FRAME_HEADER_SIZE = 4
}
