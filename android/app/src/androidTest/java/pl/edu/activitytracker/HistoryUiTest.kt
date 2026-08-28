package pl.edu.activitytracker

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.edu.activitytracker.domain.ActivityDurationBreakdown
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.HomeSession
import pl.edu.activitytracker.domain.HomeSessionStatus
import pl.edu.activitytracker.domain.SessionExportState
import pl.edu.activitytracker.domain.toSummary
import pl.edu.activitytracker.ui.history.HistoryScreen
import pl.edu.activitytracker.ui.history.SessionDetailScreen

@RunWith(AndroidJUnit4::class)
class HistoryUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComposeTestActivity>()

    @Test
    fun historyOpensSavedSessionAndNoGpsDetailSupportsRetryAndDelete() {
        val session = testSession()
        var opened: String? = null
        var showDetail by mutableStateOf(false)
        var retry = false
        var deleted = false
        composeRule.setContent {
            MaterialTheme {
                if (showDetail) {
                    SessionDetailScreen(
                        PaddingValues(), session,
                        onBack = { showDetail = false },
                        onRetryExport = { retry = true },
                        onDelete = { deleted = true },
                    )
                } else {
                    HistoryScreen(PaddingValues(), listOf(session.toSummary()), onOpen = {
                        opened = it
                        showDetail = true
                    })
                }
            }
        }
        composeRule.onNodeWithText("Walking · 01:30").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(session.id, opened) }

        composeRule.onNodeWithText("No GPS points were recorded for this session.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Retry export").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete internal session").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.runOnIdle { assertTrue(retry); assertTrue(deleted) }
    }

    private fun testSession() = HomeSession(
        id = "abcdef12-1234-1234-1234-123456789abc",
        status = HomeSessionStatus.Completed,
        deviceShortId = "18EE26A8",
        startedAtEpochMillis = 1_700_000_000_000L,
        endedAtEpochMillis = 1_700_000_090_000L,
        lastCheckpointEpochMillis = 1_700_000_090_000L,
        weightKg = 70.0,
        durations = ActivityDurationBreakdown(walkingMillis = 70_000L, unknownMillis = 20_000L),
        steps = 96,
        caloriesKcal = 4.2,
        route = emptyList(),
        exportState = SessionExportState.Error("Folder permission was revoked"),
    )
}
