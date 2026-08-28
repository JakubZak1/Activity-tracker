package pl.edu.activitytracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.edu.activitytracker.domain.ActivityDurationBreakdown
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.HomeSession
import pl.edu.activitytracker.domain.HomeSessionStatus
import pl.edu.activitytracker.domain.RoutePoint
import pl.edu.activitytracker.domain.SessionExportState
import pl.edu.activitytracker.storage.HomeSessionExportSerializer
import pl.edu.activitytracker.storage.SQLiteHomeSessionStore

@RunWith(AndroidJUnit4::class)
class HomeSessionStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: SQLiteHomeSessionStore

    @Before fun setUp() {
        context.deleteDatabase("activity_tracker.db")
        store = SQLiteHomeSessionStore(context)
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase("activity_tracker.db")
    }

    @Test
    fun checkpointRouteRecoveryAndDeleteAreAtomic() {
        val firstRoute = point(1L, ActivityType.Walking)
        val active = session(HomeSessionStatus.Active, listOf(firstRoute))
        store.save(active, routeStartIndex = 0)
        val secondRoute = point(2L, ActivityType.Running)
        store.save(active.copy(route = listOf(firstRoute, secondRoute)), routeStartIndex = 1)

        assertEquals(2, store.get(active.id)?.route?.size)
        assertEquals(1, store.markActiveSessionsInterrupted())
        val recovered = store.get(active.id)!!
        assertEquals(HomeSessionStatus.Interrupted, recovered.status)
        assertEquals(active.lastCheckpointEpochMillis, recovered.endedAtEpochMillis)
        assertEquals(2, recovered.route.size)

        store.delete(active.id)
        assertTrue(store.summaries.value.isEmpty())
    }

    @Test
    fun staleActiveCheckpointCannotDowngradeCompletedSession() {
        val active = session(HomeSessionStatus.Active, emptyList())
        store.save(active)
        store.save(active.copy(status = HomeSessionStatus.Completed, endedAtEpochMillis = 2_000L))
        store.save(active)
        assertEquals(HomeSessionStatus.Completed, store.get(active.id)?.status)
    }

    @Test
    fun jsonAndHeaderOnlyCsvContainThePublicContract() {
        val value = session(HomeSessionStatus.Completed, emptyList())
        val json = JSONObject(HomeSessionExportSerializer.summaryJson(value))
        assertEquals(value.id, json.getString("id"))
        assertEquals(value.durations.totalMillis, json.getLong("duration_ms"))
        assertEquals("MET_v1", json.getString("calorie_method"))
        assertEquals(0, json.getInt("route_point_count"))
        assertEquals(
            "timestamp_epoch_ms,latitude,longitude,accuracy_m,activity\n",
            HomeSessionExportSerializer.routeCsv(value),
        )
    }

    private fun session(status: HomeSessionStatus, route: List<RoutePoint>) = HomeSession(
        id = "12345678-1234-1234-1234-123456789abc",
        status = status,
        deviceShortId = "18EE26A8",
        startedAtEpochMillis = 1_000L,
        endedAtEpochMillis = null,
        lastCheckpointEpochMillis = 1_500L,
        weightKg = 70.0,
        durations = ActivityDurationBreakdown(walkingMillis = 500L, unknownMillis = 250L),
        steps = 12,
        caloriesKcal = 0.1,
        route = route,
        exportState = SessionExportState.Pending,
    )

    private fun point(timestamp: Long, activity: ActivityType) = RoutePoint(
        latitude = 52.0, longitude = 21.0, accuracyMeters = 4f,
        timestampMillis = timestamp, activity = activity,
    )
}
