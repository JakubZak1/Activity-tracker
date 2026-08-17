package pl.edu.activitytracker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.edu.activitytracker.ble.BleDatasetProtocol
import pl.edu.activitytracker.domain.DeviceControlResponse
import pl.edu.activitytracker.domain.FileDataFrame
import pl.edu.activitytracker.domain.FileFrameDecision
import pl.edu.activitytracker.domain.FileTransferValidator

class BleDatasetProtocolTest {
    @Test
    fun parsesCollectionStatus() {
        val response = BleDatasetProtocol.parseControlLine(
            "status,on,walking,walking_0042.csv,2039808,175616,1864192",
        ) as DeviceControlResponse.Status

        assertTrue(response.value.isLogging)
        assertEquals("walking", response.value.label)
        assertEquals("walking_0042.csv", response.value.currentFile)
        assertEquals(1_864_192L, response.value.freeBytes)
    }

    @Test
    fun parsesClosedFileAndDownloadResponses() {
        val file = BleDatasetProtocol.parseControlLine("file,walking_0042.csv,39016,closed")
            as DeviceControlResponse.FileEntry
        val begin = BleDatasetProtocol.parseControlLine("download_begin,walking_0042.csv,39016,1200")
            as DeviceControlResponse.DownloadBegin
        val end = BleDatasetProtocol.parseControlLine("download_end,walking_0042.csv,39016")
            as DeviceControlResponse.DownloadEnd

        assertFalse(file.value.isActive)
        assertEquals(39_016L, file.value.sizeBytes)
        assertEquals(1_200L, begin.offset)
        assertEquals(begin.sizeBytes, end.sizeBytes)
    }

    @Test
    fun parsesLittleEndianFileFrame() {
        val frame = BleDatasetProtocol.parseFileFrame(
            byteArrayOf(0x78, 0x56, 0x34, 0x12, 1, 2, 3),
        )

        requireNotNull(frame)
        assertEquals(0x12345678L, frame.offset)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.data)
        assertNull(BleDatasetProtocol.parseFileFrame(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun rejectsInvalidControlMetadata() {
        assertNull(BleDatasetProtocol.parseControlLine("status,paused,walking,none,10,2,8"))
        assertNull(BleDatasetProtocol.parseControlLine("status,off,walking,none,-1,0,0"))
        assertNull(BleDatasetProtocol.parseControlLine("file,walking_0001.csv,-1,closed"))
        assertNull(BleDatasetProtocol.parseControlLine("file,walking_0001.csv,10,unknown"))
        assertNull(BleDatasetProtocol.parseControlLine("download_begin,walking_0001.csv,10,11"))
        assertNull(BleDatasetProtocol.parseControlLine("list_end,-1"))
    }

    @Test
    fun validatesResumeOffsetsAndBounds() {
        val data = byteArrayOf(1, 2, 3)
        assertEquals(
            FileFrameDecision.Accept,
            FileTransferValidator.evaluate(10, 20, FileDataFrame(10, data)),
        )
        assertEquals(
            FileFrameDecision.Duplicate,
            FileTransferValidator.evaluate(10, 20, FileDataFrame(7, data)),
        )
        assertEquals(
            FileFrameDecision.Gap,
            FileTransferValidator.evaluate(10, 20, FileDataFrame(12, data)),
        )
        assertEquals(
            FileFrameDecision.Overflow,
            FileTransferValidator.evaluate(19, 20, FileDataFrame(19, data)),
        )
    }
}
