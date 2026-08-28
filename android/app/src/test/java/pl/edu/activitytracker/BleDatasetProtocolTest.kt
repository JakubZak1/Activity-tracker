package pl.edu.activitytracker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.edu.activitytracker.ble.BleDatasetProtocol
import pl.edu.activitytracker.ble.ControlRecordAssembler
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.DeviceCommand
import pl.edu.activitytracker.domain.DeviceControlResponse
import pl.edu.activitytracker.domain.DeviceStatus
import pl.edu.activitytracker.domain.FileDataFrame
import pl.edu.activitytracker.domain.FileFrameDecision
import pl.edu.activitytracker.domain.FileTransferValidator

class BleDatasetProtocolTest {
    @Test
    fun parsesHelloAndAllStatusVariants() {
        val hello = BleDatasetProtocol.parseControlLine(
            "ok,7,hello,6,11111111A1B2C3D4,A1B2C3D4,recording;catalog;download;resume;crc32;segmentation;auto_offload;pause_offload;imu_drdy104_mean2_52_deadline_guard;stable_device_id;rgb_identify;unique_filenames",
        ) as DeviceControlResponse.Hello
        assertEquals(7L, hello.requestId)
        assertEquals(6, hello.protocolVersion)
        assertEquals("11111111A1B2C3D4", hello.deviceIdentity)
        assertTrue("crc32" in hello.capabilities)
        assertTrue("imu_drdy104_mean2_52_deadline_guard" in hello.capabilities)

        val idle = BleDatasetProtocol.parseControlLine(
            "status,8,idle,walking_0042.csv,39016,12ABCDEF,1864192",
        ) as DeviceControlResponse.Status
        assertEquals("walking_0042.csv", (idle.value as DeviceStatus.Idle).lastFile?.name)

        val recording = BleDatasetProtocol.parseControlLine(
            "status,9,recording,running,running_0043.csv,1200,8192,1800000",
        ) as DeviceControlResponse.Status
        assertEquals(ActivityType.Running, (recording.value as DeviceStatus.Recording).label)

        val paused = BleDatasetProtocol.parseControlLine(
            "status,10,paused,cycling,cycling_0044.csv,1572864,1234ABCD,540000,400000",
        ) as DeviceControlResponse.Status
        val pausedStatus = paused.value as DeviceStatus.PausedForOffload
        assertEquals(ActivityType.Cycling, pausedStatus.label)
        assertEquals("cycling_0044.csv", pausedStatus.file.name)
        assertEquals("1234ABCD", pausedStatus.file.crc32)

        val fault = BleDatasetProtocol.parseControlLine(
            "status,11,fault,flash_write_failed,running_0043.csv,1700000",
        ) as DeviceControlResponse.Status
        assertEquals("flash_write_failed", (fault.value as DeviceStatus.Fault).code)
    }

    @Test
    fun parsesCatalogRecordingAndTransferResponses() {
        val started = BleDatasetProtocol.parseControlLine(
            "ok,11,recording_started,walking,walking_0001.csv",
        ) as DeviceControlResponse.RecordingStarted
        val stopped = BleDatasetProtocol.parseControlLine(
            "ok,12,recording_stopped,walking_0001.csv,1234,89ABCDEF",
        ) as DeviceControlResponse.RecordingStopped
        val file = BleDatasetProtocol.parseControlLine(
            "file,13,walking_0001.csv,1234,89ABCDEF,complete",
        ) as DeviceControlResponse.FileEntry
        val begin = BleDatasetProtocol.parseControlLine(
            "download_begin,14,walking_0001.csv,1234,100,89ABCDEF",
        ) as DeviceControlResponse.DownloadBegin
        val end = BleDatasetProtocol.parseControlLine(
            "download_end,14,walking_0001.csv,1234,89ABCDEF",
        ) as DeviceControlResponse.DownloadEnd

        assertEquals(ActivityType.Walking, started.label)
        assertEquals(stopped.file, file.value.identity)
        assertEquals(100L, begin.offset)
        assertEquals(begin.file, end.file)
    }

    @Test
    fun rejectsMalformedIdsNamesCrcAndMetadata() {
        assertNull(BleDatasetProtocol.parseControlLine("ok,4294967296,hello,3,recording"))
        assertNull(BleDatasetProtocol.parseControlLine("file,1,../walk.csv,10,89ABCDEF,complete"))
        assertNull(BleDatasetProtocol.parseControlLine("file,1,walking_1.csv,10,89abcdef,complete"))
        assertNull(BleDatasetProtocol.parseControlLine("file,1,walking_1.csv,10,none,complete"))
        assertNull(BleDatasetProtocol.parseControlLine("status,1,idle,none,10,none,100"))
        assertNull(BleDatasetProtocol.parseControlLine("download_begin,1,walking_1.csv,10,11,89ABCDEF"))
        assertNull(BleDatasetProtocol.parseControlLine("garbage,1,value"))
    }

    @Test
    fun reassemblesFragmentedAndCoalescedControlRecords() {
        val assembler = ControlRecordAssembler()
        val input = "ok,1,hello,6,11111111A1B2C3D4,A1B2C3D4,recording;catalog;download;resume;crc32;segmentation;auto_offload;pause_offload;imu_drdy104_mean2_52_deadline_guard;stable_device_id;rgb_identify;unique_filenames\nstatus,2,idle,none,0,none,100\n"
            .toByteArray()
        val records = mutableListOf<String>()
        input.forEach { byte -> records += assembler.append(byteArrayOf(byte)) }
        assertEquals(2, records.size)
        assertTrue(BleDatasetProtocol.parseControlLine(records[0]) is DeviceControlResponse.Hello)
        assertTrue(BleDatasetProtocol.parseControlLine(records[1]) is DeviceControlResponse.Status)

        val coalesced = ControlRecordAssembler().append("ok,3,cancelled\nok,4,deleted,walking_1.csv\n".toByteArray())
        assertEquals(2, coalesced.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedControlRecord() {
        ControlRecordAssembler().append(ByteArray(256) { 'a'.code.toByte() })
    }

    @Test
    fun fragmentsCommandsForDefaultMtuWithoutLosingBytes() {
        val command = DeviceCommand.Download(42L, "walking_1234.csv", 123456L)
        val fragments = BleDatasetProtocol.fragmentCommand(command, attPayloadBytes = 20)
        assertTrue(fragments.size > 1)
        assertTrue(fragments.all { it.size <= 20 })
        val rebuilt = fragments.fold(ByteArray(0)) { result, fragment -> result + fragment }
        assertArrayEquals(BleDatasetProtocol.encodeCommand(command), rebuilt)
        assertEquals('\n'.code.toByte(), rebuilt.last())
    }

    @Test
    fun parsesRequestIdAndOffsetInBinaryFrames() {
        val original = FileDataFrame(0x12345678L, 0x90ABCDEFL, byteArrayOf(1, 2, 3))
        val parsed = BleDatasetProtocol.parseFileFrame(BleDatasetProtocol.encodeFileFrame(original))
        requireNotNull(parsed)
        assertEquals(original.requestId, parsed.requestId)
        assertEquals(original.offset, parsed.offset)
        assertArrayEquals(original.data, parsed.data)
        assertNull(BleDatasetProtocol.parseFileFrame(ByteArray(8)))
    }

    @Test
    fun validatesDuplicateGapOverlapEmptyAndBounds() {
        fun decision(expected: Long, offset: Long, bytes: Int) = FileTransferValidator.evaluate(
            expected,
            20,
            FileDataFrame(1L, offset, ByteArray(bytes)),
        )
        assertEquals(FileFrameDecision.Accept, decision(10, 10, 3))
        assertEquals(FileFrameDecision.Duplicate, decision(10, 7, 3))
        assertEquals(FileFrameDecision.Overlap, decision(10, 8, 3))
        assertEquals(FileFrameDecision.Gap, decision(10, 12, 3))
        assertEquals(FileFrameDecision.Overflow, decision(19, 19, 2))
        assertEquals(FileFrameDecision.Empty, decision(10, 10, 0))
        assertFalse(BleDatasetProtocol.isValidFileName("walking.csv"))
    }
}
