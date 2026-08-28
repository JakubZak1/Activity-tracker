package pl.edu.activitytracker

import android.graphics.Bitmap
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.edu.activitytracker.data.DatasetDeviceSlot
import pl.edu.activitytracker.data.DatasetSlotState
import pl.edu.activitytracker.data.MultiDeviceDatasetState
import pl.edu.activitytracker.data.TrackerState
import pl.edu.activitytracker.domain.ActivityDurationBreakdown
import pl.edu.activitytracker.domain.ActivityReading
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.BatteryReading
import pl.edu.activitytracker.domain.CollectionState
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DatasetConnectionState
import pl.edu.activitytracker.domain.DatasetState
import pl.edu.activitytracker.domain.HomeSessionStatus
import pl.edu.activitytracker.domain.HomeSessionSummary
import pl.edu.activitytracker.domain.RoutePoint
import pl.edu.activitytracker.domain.SessionExportState
import pl.edu.activitytracker.domain.Transport
import pl.edu.activitytracker.ui.data.MultiDataCollectionScreen
import pl.edu.activitytracker.ui.history.HistoryScreen
import pl.edu.activitytracker.ui.home.HomeScreen
import pl.edu.activitytracker.ui.map.HistoricalRouteMap

@RunWith(AndroidJUnit4::class)
class ThesisScreenshotsTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComposeTestActivity>()

    @Test
    fun generateHomeHistoryMapAndDataEvidence() {
        var page by mutableStateOf(Page.Home)
        composeRule.setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    when (page) {
                        Page.Home -> HomeScreen(
                            PaddingValues(), homeState(), false,
                            onConnect = {}, onDisconnect = {}, onStartSession = {}, onStopSession = {},
                            onRetrySave = {}, onOpenSavedSession = {},
                        )
                        Page.History -> HistoryScreen(PaddingValues(), history(), onOpen = {})
                        Page.Map -> Box {
                            HistoricalRouteMap(route(), Modifier.fillMaxSize())
                            Surface(
                                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                                tonalElevation = 4.dp,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("Historical route", style = MaterialTheme.typography.titleLarge)
                                    Text("5 GPS points · activity-colored segments")
                                }
                            }
                        }
                        Page.Data -> MultiDataCollectionScreen(
                            PaddingValues(), dataState(), false,
                            onScan = {}, onConnect = { _, _ -> }, onDisconnect = { _, _ -> },
                            onIdentify = {}, onStartBoth = { _, _, _, _, _ -> }, onStopBoth = {},
                            onRefresh = {}, onDownload = { _, _ -> }, onDelete = { _, _ -> },
                            onCancel = {}, onFolderSelected = {},
                        )
                    }
                }
            }
        }

        Page.entries.forEach { target ->
            composeRule.runOnIdle { page = target }
            composeRule.waitForIdle()
            assertTrue(capture(target.name.lowercase()))
        }
    }

    private fun capture(name: String): Boolean {
        val resolver = composeRule.activity.contentResolver
        val displayName = "activity_tracker_$name.png"
        resolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(displayName),
        )
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ActivityTrackerThesis")
            },
        ) ?: return false
        return resolver.openOutputStream(uri)?.use { stream ->
            composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, stream)
        } == true
    }

    private fun homeState() = TrackerState(
        connectionState = ConnectionState.Connected(Transport.Ble),
        isSessionRunning = true,
        sessionDurationSeconds = 754L,
        sessionSteps = 1_126,
        currentActivity = ActivityReading(ActivityType.Walking, 94, 82L, 0L),
        battery = BatteryReading(3_892, 82, 0L),
        caloriesKcal = 41.7,
        activityDurations = ActivityDurationBreakdown(
            walkingMillis = 420_000L, runningMillis = 95_000L, cyclingMillis = 120_000L,
            sittingMillis = 70_000L, lyingMillis = 20_000L, unknownMillis = 29_000L,
        ),
        sessionWeightKg = 70.0,
    )

    private fun history() = listOf(
        summary("8f8b4be0", HomeSessionStatus.Completed, ActivityType.Walking, 1_226_000L, 1_842, 67.3, SessionExportState.Exported("home_demo.json", "home_demo_route.csv")),
        summary("64b7bc22", HomeSessionStatus.Interrupted, ActivityType.Cycling, 483_000L, 0, 31.8, SessionExportState.PendingFolder),
        summary("10f4b902", HomeSessionStatus.Completed, ActivityType.Sitting, 901_000L, 0, 23.9, SessionExportState.Error("Folder permission was revoked")),
    )

    private fun summary(
        id: String, status: HomeSessionStatus, activity: ActivityType, duration: Long,
        steps: Int, calories: Double, export: SessionExportState,
    ) = HomeSessionSummary(
        id = id, status = status, deviceShortId = "18EE26A8",
        startedAtEpochMillis = 1_787_500_000_000L - duration,
        endedAtEpochMillis = 1_787_500_000_000L,
        durationMillis = duration, steps = steps, caloriesKcal = calories,
        dominantActivity = activity, routePointCount = 24, exportState = export,
    )

    private fun route() = listOf(
        RoutePoint(52.22970, 21.01220, 5f, 1L, ActivityType.Walking),
        RoutePoint(52.23010, 21.01290, 5f, 2L, ActivityType.Walking),
        RoutePoint(52.23055, 21.01335, 6f, 3L, ActivityType.Running),
        RoutePoint(52.23105, 21.01405, 6f, 4L, ActivityType.Cycling),
        RoutePoint(52.23120, 21.01430, 5f, 5L, ActivityType.Sitting),
    )

    private fun dataState(): MultiDeviceDatasetState {
        fun slot(value: DatasetDeviceSlot, shortId: String) = DatasetSlotState(
            slot = value,
            transportConnection = ConnectionState.Connected(Transport.Ble),
            dataset = DatasetState(
                connection = DatasetConnectionState.Ready(6, emptySet(), "00000000$shortId", shortId, "AA:BB:CC:DD"),
                collection = CollectionState.Idle(freeBytes = 7_250_000L),
                dataFolderUri = "content://activity-tracker-data",
            ),
            configuredAddress = "AA:BB:CC:DD",
            battery = BatteryReading(3_900, 84, 0L),
        )
        return MultiDeviceDatasetState(
            blue = slot(DatasetDeviceSlot.Blue, "872F1832"),
            green = slot(DatasetDeviceSlot.Green, "18EE26A8"),
            pairedOperationMessage = "Both devices are ready",
        )
    }

    private enum class Page { Home, History, Map, Data }
}
