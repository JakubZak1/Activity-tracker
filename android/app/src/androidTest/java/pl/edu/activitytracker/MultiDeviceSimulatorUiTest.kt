package pl.edu.activitytracker

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.edu.activitytracker.data.BleDeviceScanner
import pl.edu.activitytracker.data.DatasetController
import pl.edu.activitytracker.data.DatasetDeviceSlot
import pl.edu.activitytracker.data.DatasetWorkRuntime
import pl.edu.activitytracker.data.MockDeviceDataSource
import pl.edu.activitytracker.data.MultiDeviceDatasetManager
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.CollectionState
import pl.edu.activitytracker.domain.BodySide
import pl.edu.activitytracker.domain.DatasetConnectionState
import pl.edu.activitytracker.domain.RemoteFileIdentity
import pl.edu.activitytracker.domain.SensorPlacement
import pl.edu.activitytracker.domain.TransferState
import pl.edu.activitytracker.storage.DatasetDeviceSettings
import pl.edu.activitytracker.storage.DatasetFileStore
import pl.edu.activitytracker.storage.SettingsUiState
import pl.edu.activitytracker.ui.data.MultiDataCollectionScreen

@RunWith(AndroidJUnit4::class)
class MultiDeviceSimulatorUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComposeTestActivity>()
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = testScope.cancel()

    @Test
    fun twoBoardsSharePairedSessionButKeepIndependentIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val blueSource = MockDeviceDataSource(
            testScope, connectDelayMillis = 0L, frameDelayMillis = 0L,
            mockTransportIdentity = "MOCK-BLUE", mockHardwareIdentity = "01020304A1B2C3D4",
        )
        val greenSource = MockDeviceDataSource(
            testScope, connectDelayMillis = 0L, frameDelayMillis = 0L,
            mockTransportIdentity = "MOCK-GREEN", mockHardwareIdentity = "05060708E5F6A7B8",
        )
        val store = MemoryFileStore()
        val transferMutex = Mutex()
        val blueController = DatasetController(
            blueSource, store, testScope, commandTimeoutMillis = 2_000L,
            catalogInactivityMillis = 2_000L, fileInactivityMillis = 2_000L,
            sharedTransferMutex = transferMutex,
        )
        val greenController = DatasetController(
            greenSource, store, testScope, commandTimeoutMillis = 2_000L,
            catalogInactivityMillis = 2_000L, fileInactivityMillis = 2_000L,
            sharedTransferMutex = transferMutex,
        )
        val settings = MemorySettings()
        val manager = MultiDeviceDatasetManager(
            BleDeviceScanner(context), blueSource, greenSource, blueController, greenController,
            settings, DatasetWorkRuntime.NoOp, testScope,
        )

        composeRule.setContent {
            val state by manager.state.collectAsState()
            MaterialTheme {
                MultiDataCollectionScreen(
                    PaddingValues(), state, true,
                    onScan = manager::scan,
                    onConnect = manager::connect,
                    onDisconnect = manager::disconnect,
                    onIdentify = manager::identify,
                    onStartBoth = manager::startBoth,
                    onStopBoth = manager::stopBoth,
                    onRefresh = manager::refresh,
                    onDownload = manager::download,
                    onDelete = manager::delete,
                    onCancel = manager::cancel,
                    onFolderSelected = { settings.setFolder(it) },
                )
            }
        }

        composeRule.onNodeWithText("Connect two simulators").performClick()
        composeRule.waitUntil(5_000L) {
            manager.state.value.blue.dataset.connection is DatasetConnectionState.Ready &&
                manager.state.value.green.dataset.connection is DatasetConnectionState.Ready
        }
        runBlocking {
            manager.startBoth(
                ActivityType.Walking,
                SensorPlacement.Wrist,
                BodySide.Left,
                SensorPlacement.Leg,
                BodySide.Left,
            ).join()
        }
        composeRule.waitUntil(5_000L) {
            manager.state.value.blue.dataset.collection is CollectionState.Recording &&
                manager.state.value.green.dataset.collection is CollectionState.Recording
        }
        val blueName = (manager.state.value.blue.dataset.collection as CollectionState.Recording).fileName
        val greenName = (manager.state.value.green.dataset.collection as CollectionState.Recording).fileName
        assertTrue(blueName.startsWith("a1b2c3d4_"))
        assertTrue(greenName.startsWith("e5f6a7b8_"))
        assertNotEquals(blueName, greenName)

        runBlocking { manager.stopBoth().join() }
        composeRule.waitUntil(8_000L) {
            manager.state.value.blue.dataset.transfer is TransferState.Completed &&
                manager.state.value.green.dataset.transfer is TransferState.Completed
        }
        assertEquals(2, store.pairedIds.size)
        assertEquals(1, store.pairedIds.distinct().size)
    }

    private class MemorySettings : DatasetDeviceSettings {
        private val mutable = MutableStateFlow(SettingsUiState(dataFolderUri = "memory://paired"))
        override val settings = mutable
        override suspend fun setDataFolderUri(uri: String) { mutable.value = mutable.value.copy(dataFolderUri = uri) }
        override suspend fun setDatasetDeviceAddress(slot: String, address: String?) {
            mutable.value = if (slot == "blue") mutable.value.copy(blueDeviceAddress = address)
            else mutable.value.copy(greenDeviceAddress = address)
        }
        fun setFolder(uri: String) = runBlocking { setDataFolderUri(uri) }
    }

    private class MemoryFileStore : DatasetFileStore {
        val pairedIds = mutableListOf<String>()
        private val verified = mutableSetOf<RemoteFileIdentity>()
        private class Sink(
            override val file: RemoteFileIdentity,
            val output: ByteArrayOutputStream = ByteArrayOutputStream(),
        ) : DatasetFileStore.DownloadSink {
            override val position get() = output.size().toLong()
            override fun write(bytes: ByteArray) = output.write(bytes)
            override fun close() = Unit
        }
        override fun isFolderAvailable(treeUri: String) = true
        override fun prepare(
            treeUri: String,
            deviceIdentity: String,
            file: RemoteFileIdentity,
            sessionMetadata: pl.edu.activitytracker.domain.DatasetSessionMetadata?,
        ): DatasetFileStore.PrepareResult {
            sessionMetadata?.pairedSessionId?.let(pairedIds::add)
            return DatasetFileStore.PrepareResult.Ready(Sink(file), 0L)
        }
        override fun complete(sink: DatasetFileStore.DownloadSink): DatasetFileStore.CompleteResult {
            val value = sink as Sink
            val crc = CRC32().apply { update(value.output.toByteArray()) }
            val actual = String.format(Locale.US, "%08X", crc.value)
            return if (value.position == value.file.sizeBytes && actual == value.file.crc32) {
                verified += value.file
                DatasetFileStore.CompleteResult.Success
            } else DatasetFileStore.CompleteResult.Failure("CRC mismatch")
        }
        override fun isVerified(treeUri: String, deviceIdentity: String, file: RemoteFileIdentity) = file in verified
    }
}
