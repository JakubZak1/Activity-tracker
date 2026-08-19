package pl.edu.activitytracker.ble

import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.DeviceCommand
import pl.edu.activitytracker.domain.DeviceControlResponse
import pl.edu.activitytracker.domain.DeviceLogFile
import pl.edu.activitytracker.domain.DeviceStatus
import pl.edu.activitytracker.domain.FileDataFrame
import pl.edu.activitytracker.domain.MAX_REQUEST_ID
import pl.edu.activitytracker.domain.RemoteFileIdentity

object BleDatasetProtocol {
    const val MAX_CONTROL_RECORD_BYTES = 256
    private const val FILE_FRAME_HEADER_SIZE = 8
    private val FILE_NAME_REGEX = Regex("[a-z0-9_]+_[0-9]+\\.csv")
    private val CRC32_REGEX = Regex("[0-9A-F]{8}")

    fun encodeCommand(command: DeviceCommand): ByteArray {
        require(isRequestId(command.requestId)) { "request_id is outside uint32" }
        val encoded = "${command.line}\n".toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_CONTROL_RECORD_BYTES) { "Command exceeds protocol record limit" }
        return encoded
    }

    fun fragmentCommand(command: DeviceCommand, attPayloadBytes: Int): List<ByteArray> {
        require(attPayloadBytes > 0) { "ATT payload must be positive" }
        return encodeCommand(command).asList()
            .chunked(attPayloadBytes)
            .map { chunk -> chunk.toByteArray() }
    }

    fun parseControlLine(payload: String): DeviceControlResponse? {
        val line = payload.trimEnd('\r')
        if (line.isEmpty() || line.toByteArray(Charsets.UTF_8).size + 1 > MAX_CONTROL_RECORD_BYTES) return null
        val parts = line.split(',')
        return when (parts.firstOrNull()) {
            "ok" -> parseOk(parts)
            "error" -> parseError(parts)
            "status" -> parseStatus(parts)
            "file" -> parseFile(parts)
            "list_end" -> parseListEnd(parts)
            "download_begin" -> parseDownloadBegin(parts)
            "download_end" -> parseDownloadEnd(parts)
            else -> null
        }
    }

    fun parseFileFrame(payload: ByteArray): FileDataFrame? {
        if (payload.size <= FILE_FRAME_HEADER_SIZE) return null
        return FileDataFrame(
            requestId = readUInt32Le(payload, 0),
            offset = readUInt32Le(payload, 4),
            data = payload.copyOfRange(FILE_FRAME_HEADER_SIZE, payload.size),
        )
    }

    fun encodeFileFrame(frame: FileDataFrame): ByteArray {
        require(isRequestId(frame.requestId) && isUInt32(frame.offset))
        return ByteArray(FILE_FRAME_HEADER_SIZE + frame.data.size).also { output ->
            writeUInt32Le(output, 0, frame.requestId)
            writeUInt32Le(output, 4, frame.offset)
            frame.data.copyInto(output, FILE_FRAME_HEADER_SIZE)
        }
    }

    fun isValidFileName(value: String): Boolean = FILE_NAME_REGEX.matches(value)

    fun isValidCrc32(value: String): Boolean = CRC32_REGEX.matches(value)

    private fun parseOk(parts: List<String>): DeviceControlResponse? {
        if (parts.size < 3) return null
        val requestId = parseRequestId(parts[1]) ?: return null
        return when (parts[2]) {
            "hello" -> {
                if (parts.size != 5) return null
                val version = parts[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                val capabilities = parts[4].split(';').filter(String::isNotBlank).toSet()
                DeviceControlResponse.Hello(requestId, version, capabilities)
            }
            "recording_started", "already_recording" -> {
                if (parts.size != 5 || !isValidFileName(parts[4])) return null
                val activity = parseActivity(parts[3]) ?: return null
                DeviceControlResponse.RecordingStarted(
                    requestId = requestId,
                    label = activity,
                    fileName = parts[4],
                    alreadyRecording = parts[2] == "already_recording",
                )
            }
            "recording_stopped", "already_stopped" -> {
                if (parts.size != 6) return null
                val already = parts[2] == "already_stopped"
                val file = if (parts[3] == "none") {
                    if (parts[4] != "0" || parts[5] != "none") return null
                    null
                } else {
                    parseFileIdentity(parts[3], parts[4], parts[5]) ?: return null
                }
                DeviceControlResponse.RecordingStopped(requestId, file, already)
            }
            "cancelled" -> if (parts.size == 3) DeviceControlResponse.Cancelled(requestId) else null
            "deleted", "already_deleted" -> {
                if (parts.size != 4 || !isValidFileName(parts[3])) return null
                DeviceControlResponse.Deleted(requestId, parts[3], parts[2] == "already_deleted")
            }
            else -> null
        }
    }

    private fun parseError(parts: List<String>): DeviceControlResponse? {
        if (parts.size < 3 || parts[2].isBlank()) return null
        val requestId = parseRequestId(parts[1]) ?: return null
        return DeviceControlResponse.Error(
            requestId = requestId,
            code = parts[2],
            details = parts.drop(3).joinToString(",").ifBlank { null },
        )
    }

    private fun parseStatus(parts: List<String>): DeviceControlResponse? {
        if (parts.size < 3) return null
        val requestId = parseRequestId(parts[1]) ?: return null
        val status = when (parts[2]) {
            "idle" -> {
                if (parts.size != 7) return null
                val lastFile = if (parts[3] == "none") {
                    if (parts[4] != "0" || parts[5] != "none") return null
                    null
                } else {
                    parseFileIdentity(parts[3], parts[4], parts[5]) ?: return null
                }
                val free = parseUInt32(parts[6]) ?: return null
                DeviceStatus.Idle(lastFile, free)
            }
            "recording" -> {
                if (parts.size != 8 || !isValidFileName(parts[4])) return null
                DeviceStatus.Recording(
                    label = parseActivity(parts[3]) ?: return null,
                    fileName = parts[4],
                    elapsedMillis = parseUInt32(parts[5]) ?: return null,
                    bytesWritten = parseUInt32(parts[6]) ?: return null,
                    freeBytes = parseUInt32(parts[7]) ?: return null,
                )
            }
            "paused" -> {
                if (parts.size != 9) return null
                DeviceStatus.PausedForOffload(
                    label = parseActivity(parts[3]) ?: return null,
                    file = parseFileIdentity(parts[4], parts[5], parts[6]) ?: return null,
                    elapsedMillis = parseUInt32(parts[7]) ?: return null,
                    freeBytes = parseUInt32(parts[8]) ?: return null,
                )
            }
            "fault" -> {
                if (parts.size != 6 || parts[3].isBlank()) return null
                val active = parts[4].takeUnless { it == "none" }
                if (active != null && !isValidFileName(active)) return null
                DeviceStatus.Fault(parts[3], active, parseUInt32(parts[5]) ?: return null)
            }
            else -> return null
        }
        return DeviceControlResponse.Status(requestId, status)
    }

    private fun parseFile(parts: List<String>): DeviceControlResponse? {
        if (parts.size != 6) return null
        val requestId = parseRequestId(parts[1]) ?: return null
        if (!isValidFileName(parts[2])) return null
        val size = parseUInt32(parts[3]) ?: return null
        val crc = parts[4].takeUnless { it == "none" }
        if (crc != null && !isValidCrc32(crc)) return null
        val complete = when (parts[5]) {
            "complete" -> true
            "incomplete" -> false
            else -> return null
        }
        if (complete && crc == null) return null
        return DeviceControlResponse.FileEntry(
            requestId,
            DeviceLogFile(parts[2], size, crc, complete),
        )
    }

    private fun parseListEnd(parts: List<String>): DeviceControlResponse? {
        if (parts.size != 3) return null
        val requestId = parseRequestId(parts[1]) ?: return null
        val count = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return DeviceControlResponse.ListEnd(requestId, count)
    }

    private fun parseDownloadBegin(parts: List<String>): DeviceControlResponse? {
        if (parts.size != 6) return null
        val requestId = parseRequestId(parts[1]) ?: return null
        val file = parseFileIdentity(parts[2], parts[3], parts[5]) ?: return null
        val offset = parseUInt32(parts[4]) ?: return null
        if (offset > file.sizeBytes) return null
        return DeviceControlResponse.DownloadBegin(requestId, file, offset)
    }

    private fun parseDownloadEnd(parts: List<String>): DeviceControlResponse? {
        if (parts.size != 5) return null
        val requestId = parseRequestId(parts[1]) ?: return null
        val file = parseFileIdentity(parts[2], parts[3], parts[4]) ?: return null
        return DeviceControlResponse.DownloadEnd(requestId, file)
    }

    private fun parseFileIdentity(name: String, sizeText: String, crcText: String): RemoteFileIdentity? {
        if (!isValidFileName(name) || !isValidCrc32(crcText)) return null
        return RemoteFileIdentity(name, parseUInt32(sizeText) ?: return null, crcText)
    }

    private fun parseActivity(value: String): ActivityType? =
        ActivityType.fromWire(value).takeUnless { it == ActivityType.Unknown }

    private fun parseRequestId(value: String): Long? = parseUInt32(value)

    private fun parseUInt32(value: String): Long? =
        value.toLongOrNull()?.takeIf(::isUInt32)

    private fun isRequestId(value: Long): Boolean = isUInt32(value)

    private fun isUInt32(value: Long): Boolean = value in 0L..MAX_REQUEST_ID

    private fun readUInt32Le(bytes: ByteArray, start: Int): Long =
        (bytes[start].toLong() and 0xFF) or
            ((bytes[start + 1].toLong() and 0xFF) shl 8) or
            ((bytes[start + 2].toLong() and 0xFF) shl 16) or
            ((bytes[start + 3].toLong() and 0xFF) shl 24)

    private fun writeUInt32Le(bytes: ByteArray, start: Int, value: Long) {
        repeat(4) { index -> bytes[start + index] = ((value shr (index * 8)) and 0xFF).toByte() }
    }

}

class ControlRecordAssembler(
    private val maxRecordBytes: Int = BleDatasetProtocol.MAX_CONTROL_RECORD_BYTES,
) {
    private val buffer = ByteArray(maxRecordBytes)
    private var length = 0

    fun append(bytes: ByteArray): List<String> {
        val records = mutableListOf<String>()
        bytes.forEach { byte ->
            if (byte == '\n'.code.toByte()) {
                val contentLength = if (length > 0 && buffer[length - 1] == '\r'.code.toByte()) length - 1 else length
                records += buffer.copyOfRange(0, contentLength).toString(Charsets.UTF_8)
                length = 0
            } else {
                if (length >= maxRecordBytes - 1) {
                    reset()
                    throw IllegalArgumentException("Control record exceeds $maxRecordBytes bytes")
                }
                buffer[length++] = byte
            }
        }
        return records
    }

    fun reset() {
        length = 0
    }
}
