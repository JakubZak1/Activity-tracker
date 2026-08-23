package pl.edu.activitytracker

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.edu.activitytracker.data.DatasetController
import pl.edu.activitytracker.data.MockDeviceDataSource
import pl.edu.activitytracker.data.TrackerState
import pl.edu.activitytracker.domain.CollectionState
import pl.edu.activitytracker.domain.DatasetConnectionState
import pl.edu.activitytracker.domain.RemoteFileIdentity
import pl.edu.activitytracker.domain.TransferState
import pl.edu.activitytracker.storage.DatasetFileStore
import pl.edu.activitytracker.ui.data.DataCollectionScreen

@RunWith(AndroidJUnit4::class)
class DatasetSimulatorUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeTestActivity>()

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun recordReconnectStopDownloadVerifyAndAutoDelete() {
        val source = MockDeviceDataSource(testScope, connectDelayMillis = 0L, frameDelayMillis = 0L)
        val controller = DatasetController(
            deviceDataSource = source,
            fileStore = MemoryFileStore(),
            scope = testScope,
            commandTimeoutMillis = 2_000L,
            catalogInactivityMillis = 2_000L,
            fileInactivityMillis = 2_000L,
        )
        controller.setDataFolderUri("memory://instrumentation-logs")

        composeRule.setContent {
            val dataset by controller.state.collectAsState()
            val connection by source.connectionState.collectAsState()
            MaterialTheme {
                DataCollectionScreen(
                    paddingValues = PaddingValues(),
                    state = TrackerState(connectionState = connection, dataset = dataset),
                    useMockSource = true,
                    onConnect = { testScope.launch { source.connect(null) } },
                    onDisconnect = { testScope.launch { source.disconnect() } },
                    onStart = { activity, placement, side ->
                        controller.startRecording(activity, placement, side)
                    },
                    onStop = controller::stopRecording,
                    onRefresh = controller::refreshCatalog,
                    onDownload = controller::downloadLog,
                    onDelete = controller::deleteLog,
                    onCancelTransfer = controller::cancelTransfer,
                    onFolderSelected = controller::setDataFolderUri,
                )
            }
        }

        composeRule.onNodeWithText("Connect simulator").performClick()
        composeRule.waitUntil(5_000L) { controller.state.value.connection is DatasetConnectionState.Ready }

        composeRule.onNodeWithTag("sensor-placement-wrist").performClick()
        composeRule.onNodeWithTag("body-side-left").performClick()
        composeRule.onNodeWithTag("start-dataset-recording").performScrollTo().assertIsEnabled().performClick()
        try {
            composeRule.waitUntil(5_000L) { controller.state.value.collection is CollectionState.Recording }
        } catch (error: Throwable) {
            throw AssertionError("Recording did not start; state=${controller.state.value}", error)
        }
        composeRule.onNodeWithText("Recording Walking").assertIsDisplayed()

        composeRule.onNodeWithText("Disconnect").performClick()
        composeRule.waitUntil(5_000L) { controller.state.value.connection is DatasetConnectionState.Offline }
        composeRule.onNodeWithText("Connect simulator").performClick()
        composeRule.waitUntil(5_000L) {
            controller.state.value.connection is DatasetConnectionState.Ready &&
                controller.state.value.collection is CollectionState.Recording
        }

        composeRule.onNodeWithTag("stop-dataset-recording").performScrollTo().performClick()
        composeRule.waitUntil(5_000L) { controller.state.value.transfer is TransferState.Completed }
        composeRule.onNodeWithText("Saved and CRC32 verified", substring = true)
            .performScrollTo()
            .assertIsDisplayed()

        val completed = controller.state.value.transfer as TransferState.Completed
        composeRule.waitUntil(5_000L) { controller.state.value.catalog.files.isEmpty() }
        assertTrue(completed.file.name.endsWith(".csv"))
        assertTrue(controller.state.value.catalog.files.isEmpty())
    }

    private class MemoryFileStore : DatasetFileStore {
        private val verified = mutableSetOf<RemoteFileIdentity>()

        private class Sink(
            override val file: RemoteFileIdentity,
            val output: ByteArrayOutputStream = ByteArrayOutputStream(),
        ) : DatasetFileStore.DownloadSink {
            override val position: Long get() = output.size().toLong()
            override fun write(bytes: ByteArray) = output.write(bytes)
            override fun close() = Unit
        }

        override fun isFolderAvailable(treeUri: String): Boolean = treeUri.startsWith("memory://")

        override fun prepare(
            treeUri: String,
            deviceIdentity: String,
            file: RemoteFileIdentity,
            sessionMetadata: pl.edu.activitytracker.domain.DatasetSessionMetadata?,
        ): DatasetFileStore.PrepareResult = DatasetFileStore.PrepareResult.Ready(Sink(file), 0L)

        override fun complete(sink: DatasetFileStore.DownloadSink): DatasetFileStore.CompleteResult {
            val memorySink = sink as Sink
            val crc = CRC32().apply { update(memorySink.output.toByteArray()) }
            val actual = String.format(Locale.US, "%08X", crc.value)
            return if (memorySink.position == memorySink.file.sizeBytes && actual == memorySink.file.crc32) {
                verified += memorySink.file
                DatasetFileStore.CompleteResult.Success
            } else {
                DatasetFileStore.CompleteResult.Failure("CRC32 mismatch")
            }
        }

        override fun isVerified(
            treeUri: String,
            deviceIdentity: String,
            file: RemoteFileIdentity,
        ): Boolean = file in verified
    }
}
