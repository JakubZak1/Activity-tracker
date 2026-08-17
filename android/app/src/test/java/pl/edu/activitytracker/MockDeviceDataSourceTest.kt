package pl.edu.activitytracker

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.CRC32
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.edu.activitytracker.data.MockDeviceDataSource
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DeviceCommand
import pl.edu.activitytracker.domain.DeviceControlResponse
import pl.edu.activitytracker.domain.DeviceProtocolEvent
import pl.edu.activitytracker.domain.DeviceStatus
import pl.edu.activitytracker.domain.RemoteFileIdentity

@OptIn(ExperimentalCoroutinesApi::class)
class MockDeviceDataSourceTest {
    @Test
    fun timeoutFaultDropsExactlyOneResponseButStillAppliesTheCommand() = runTest {
        val source = MockDeviceDataSource(backgroundScope, connectDelayMillis = 0L, frameDelayMillis = 0L)
        source.connect(null)
        source.simulateTimeoutForNext("record_start")

        assertTrue(source.sendCommand(DeviceCommand.RecordStart(1L, ActivityType.Walking)))
        runCurrent()
        assertTrue(source.sendCommand(DeviceCommand.Status(2L)))

        val status = nextControl(source) as DeviceControlResponse.Status
        assertEquals(2L, status.requestId)
        assertTrue(status.value is DeviceStatus.Recording)

        assertTrue(source.sendCommand(DeviceCommand.RecordStart(3L, ActivityType.Walking)))
        val replay = nextControl(source) as DeviceControlResponse.RecordingStarted
        assertEquals(3L, replay.requestId)
        assertTrue(replay.alreadyRecording)
    }

    @Test
    fun recordingProducesDeterministicFiftyHertzRowsAcrossDisconnect() = runTest {
        val source = MockDeviceDataSource(backgroundScope, connectDelayMillis = 0L, frameDelayMillis = 0L)
        source.connect(null)
        source.sendCommand(DeviceCommand.RecordStart(10L, ActivityType.Running))
        nextControl(source)

        advanceTimeBy(101L)
        runCurrent()
        source.sendCommand(DeviceCommand.Status(11L))
        val beforeDisconnect = (nextControl(source) as DeviceControlResponse.Status).value as DeviceStatus.Recording
        assertEquals(100L, beforeDisconnect.elapsedMillis)

        source.disconnect()
        advanceTimeBy(100L)
        runCurrent()
        source.connect(null)
        source.sendCommand(DeviceCommand.Status(12L))
        val afterReconnect = (nextControl(source) as DeviceControlResponse.Status).value as DeviceStatus.Recording
        assertEquals(200L, afterReconnect.elapsedMillis)
        assertTrue(afterReconnect.bytesWritten > beforeDisconnect.bytesWritten)

        source.sendCommand(DeviceCommand.RecordStop(13L))
        val stopped = nextControl(source) as DeviceControlResponse.RecordingStopped
        val identity = requireNotNull(stopped.file)
        val bytes = download(source, 14L, identity)
        val lines = bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
        assertEquals(1 + 10, lines.size)
        assertEquals("0,running,0.0100,0.0200,1.0000,0.1000,0.2000,0.3000", lines[1])
        assertEquals("180,running,0.0100,0.0200,1.0000,0.1000,0.2000,0.3000", lines.last())
    }

    @Test
    fun corruptDownloadFaultIsOneShotAndKeepsOriginalIdentity() = runTest {
        val source = MockDeviceDataSource(backgroundScope, connectDelayMillis = 0L, frameDelayMillis = 0L)
        source.connect(null)
        source.sendCommand(DeviceCommand.RecordStart(20L, ActivityType.Cycling))
        nextControl(source)
        advanceTimeBy(61L)
        runCurrent()
        source.sendCommand(DeviceCommand.RecordStop(21L))
        val identity = requireNotNull((nextControl(source) as DeviceControlResponse.RecordingStopped).file)

        source.corruptNextDownload()
        val corrupted = download(source, 22L, identity)
        assertEquals(identity.sizeBytes, corrupted.size.toLong())
        assertNotEquals(identity.crc32, crc32(corrupted))

        val retry = download(source, 23L, identity)
        assertEquals(identity.sizeBytes, retry.size.toLong())
        assertEquals(identity.crc32, crc32(retry))
    }

    @Test
    fun rapidDoubleConnectCreatesOnlyOneConnectionGeneration() = runTest {
        val source = MockDeviceDataSource(backgroundScope, connectDelayMillis = 100L, frameDelayMillis = 0L)

        backgroundScope.launch { source.connect(null) }
        runCurrent()
        backgroundScope.launch { source.connect(null) }
        runCurrent()
        advanceTimeBy(101L)
        runCurrent()

        assertTrue(source.connectionState.value is ConnectionState.Connected)
        assertEquals(1L, source.connectionGeneration.value)
    }

    private suspend fun nextControl(source: MockDeviceDataSource): DeviceControlResponse {
        while (true) {
            val event = source.protocolEvents.first()
            if (event is DeviceProtocolEvent.Control) return event.response
        }
    }

    private suspend fun download(
        source: MockDeviceDataSource,
        requestId: Long,
        file: RemoteFileIdentity,
    ): ByteArray {
        source.sendCommand(DeviceCommand.Download(requestId, file.name, 0L))
        val output = ByteArrayOutputStream()
        while (true) {
            when (val event = source.protocolEvents.first()) {
                is DeviceProtocolEvent.FileData -> {
                    assertEquals(requestId, event.frame.requestId)
                    assertEquals(output.size().toLong(), event.frame.offset)
                    output.write(event.frame.data)
                }
                is DeviceProtocolEvent.Control -> when (val response = event.response) {
                    is DeviceControlResponse.DownloadBegin -> {
                        assertEquals(requestId, response.requestId)
                        assertEquals(file, response.file)
                    }
                    is DeviceControlResponse.DownloadEnd -> {
                        assertEquals(requestId, response.requestId)
                        assertEquals(file, response.file)
                        return output.toByteArray()
                    }
                    else -> error("Unexpected download response: $response")
                }
                is DeviceProtocolEvent.Fault -> error("Unexpected protocol fault: ${event.message}")
            }
        }
    }

    private fun crc32(bytes: ByteArray): String {
        val crc = CRC32().apply { update(bytes) }
        return String.format(Locale.US, "%08X", crc.value)
    }
}
