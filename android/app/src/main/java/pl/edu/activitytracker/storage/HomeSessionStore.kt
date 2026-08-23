package pl.edu.activitytracker.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.edu.activitytracker.domain.ActivityDurationBreakdown
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.HomeSession
import pl.edu.activitytracker.domain.HomeSessionStatus
import pl.edu.activitytracker.domain.HomeSessionSummary
import pl.edu.activitytracker.domain.RoutePoint
import pl.edu.activitytracker.domain.SessionExportState

interface HomeSessionDataSource {
    val summaries: StateFlow<List<HomeSessionSummary>>
    fun save(session: HomeSession, routeStartIndex: Int = 0)
    fun get(sessionId: String): HomeSession?
    fun markActiveSessionsInterrupted(): Int
    fun updateExportState(sessionId: String, state: SessionExportState)
    fun delete(sessionId: String)
    fun refresh()
}

class SQLiteHomeSessionStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
), HomeSessionDataSource {
    private val _summaries = MutableStateFlow<List<HomeSessionSummary>>(emptyList())
    override val summaries: StateFlow<List<HomeSessionSummary>> = _summaries.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE home_sessions (
                id TEXT PRIMARY KEY NOT NULL,
                status TEXT NOT NULL,
                device_short_id TEXT,
                started_epoch_ms INTEGER NOT NULL,
                ended_epoch_ms INTEGER,
                checkpoint_epoch_ms INTEGER NOT NULL,
                weight_kg REAL NOT NULL,
                walking_ms INTEGER NOT NULL,
                running_ms INTEGER NOT NULL,
                cycling_ms INTEGER NOT NULL,
                sitting_ms INTEGER NOT NULL,
                lying_ms INTEGER NOT NULL,
                unknown_ms INTEGER NOT NULL,
                steps INTEGER NOT NULL,
                calories REAL NOT NULL,
                calorie_method TEXT NOT NULL,
                export_state TEXT NOT NULL,
                export_summary_name TEXT,
                export_route_name TEXT,
                export_error TEXT,
                route_point_count INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE home_route_points (
                session_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                timestamp_epoch_ms INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                accuracy_m REAL,
                activity TEXT NOT NULL,
                PRIMARY KEY(session_id, sequence),
                FOREIGN KEY(session_id) REFERENCES home_sessions(id) ON DELETE CASCADE
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_home_sessions_started ON home_sessions(started_epoch_ms DESC)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun save(session: HomeSession, routeStartIndex: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = sessionValues(session)
            val where = if (session.status == HomeSessionStatus.Active) "id = ? AND status = ?" else "id = ?"
            val args = if (session.status == HomeSessionStatus.Active) {
                arrayOf(session.id, HomeSessionStatus.Active.name)
            } else arrayOf(session.id)
            val updated = db.update("home_sessions", values, where, args)
            if (updated == 0 && !sessionExists(db, session.id)) db.insertOrThrow("home_sessions", null, values)
            session.route.drop(routeStartIndex.coerceAtLeast(0)).forEachIndexed { index, point ->
                val sequence = routeStartIndex.coerceAtLeast(0) + index
                db.insertWithOnConflict(
                    "home_route_points",
                    null,
                    routeValues(session.id, sequence, point),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refresh()
    }

    override fun get(sessionId: String): HomeSession? {
        val session = readableDatabase.query(
            "home_sessions", null, "id = ?", arrayOf(sessionId), null, null, null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toSession(emptyList()) else null } ?: return null
        val route = readableDatabase.query(
            "home_route_points", null, "session_id = ?", arrayOf(sessionId), null, null, "sequence ASC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRoutePoint()) } }
        return session.copy(route = route)
    }

    override fun markActiveSessionsInterrupted(): Int {
        val statement = writableDatabase.compileStatement(
            "UPDATE home_sessions SET status = ?, ended_epoch_ms = checkpoint_epoch_ms WHERE status = ?",
        )
        statement.bindString(1, HomeSessionStatus.Interrupted.name)
        statement.bindString(2, HomeSessionStatus.Active.name)
        val count = statement.executeUpdateDelete()
        refresh()
        return count
    }

    override fun updateExportState(sessionId: String, state: SessionExportState) {
        val values = exportValues(state)
        writableDatabase.update("home_sessions", values, "id = ?", arrayOf(sessionId))
        refresh()
    }

    override fun delete(sessionId: String) {
        writableDatabase.delete("home_sessions", "id = ?", arrayOf(sessionId))
        refresh()
    }

    override fun refresh() {
        _summaries.value = readableDatabase.query(
            "home_sessions", null, null, null, null, null, "started_epoch_ms DESC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toSummary()) } }
    }

    private fun sessionValues(session: HomeSession) = ContentValues().apply {
        put("id", session.id)
        put("status", session.status.name)
        put("device_short_id", session.deviceShortId)
        put("started_epoch_ms", session.startedAtEpochMillis)
        session.endedAtEpochMillis?.let { put("ended_epoch_ms", it) } ?: putNull("ended_epoch_ms")
        put("checkpoint_epoch_ms", session.lastCheckpointEpochMillis)
        put("weight_kg", session.weightKg)
        put("walking_ms", session.durations.walkingMillis)
        put("running_ms", session.durations.runningMillis)
        put("cycling_ms", session.durations.cyclingMillis)
        put("sitting_ms", session.durations.sittingMillis)
        put("lying_ms", session.durations.lyingMillis)
        put("unknown_ms", session.durations.unknownMillis)
        put("steps", session.steps)
        put("calories", session.caloriesKcal)
        put("calorie_method", session.calorieMethod)
        putAll(exportValues(session.exportState))
        put("route_point_count", session.route.size)
    }

    private fun routeValues(sessionId: String, sequence: Int, point: RoutePoint) = ContentValues().apply {
        put("session_id", sessionId)
        put("sequence", sequence)
        put("timestamp_epoch_ms", point.timestampMillis)
        put("latitude", point.latitude)
        put("longitude", point.longitude)
        point.accuracyMeters?.let { put("accuracy_m", it) } ?: putNull("accuracy_m")
        put("activity", point.activity.wireName)
    }

    private fun sessionExists(db: SQLiteDatabase, sessionId: String): Boolean = db.query(
        "home_sessions", arrayOf("id"), "id = ?", arrayOf(sessionId), null, null, null, "1",
    ).use(Cursor::moveToFirst)

    private fun exportValues(state: SessionExportState) = ContentValues().apply {
        put("export_state", when (state) {
            SessionExportState.PendingFolder -> "PENDING_FOLDER"
            SessionExportState.Pending -> "PENDING"
            SessionExportState.Exporting -> "EXPORTING"
            is SessionExportState.Exported -> "EXPORTED"
            is SessionExportState.Error -> "ERROR"
        })
        if (state is SessionExportState.Exported) {
            put("export_summary_name", state.summaryFileName)
            put("export_route_name", state.routeFileName)
        } else {
            putNull("export_summary_name")
            putNull("export_route_name")
        }
        if (state is SessionExportState.Error) put("export_error", state.message) else putNull("export_error")
    }

    private fun Cursor.toSession(route: List<RoutePoint>): HomeSession {
        val durations = readDurations()
        return HomeSession(
            id = string("id"),
            status = HomeSessionStatus.valueOf(string("status")),
            deviceShortId = nullableString("device_short_id"),
            startedAtEpochMillis = long("started_epoch_ms"),
            endedAtEpochMillis = nullableLong("ended_epoch_ms"),
            lastCheckpointEpochMillis = long("checkpoint_epoch_ms"),
            weightKg = double("weight_kg"),
            durations = durations,
            steps = int("steps"),
            caloriesKcal = double("calories"),
            route = route,
            exportState = readExportState(),
            calorieMethod = string("calorie_method"),
        )
    }

    private fun Cursor.toSummary(): HomeSessionSummary {
        val durations = readDurations()
        return HomeSessionSummary(
            id = string("id"),
            status = HomeSessionStatus.valueOf(string("status")),
            deviceShortId = nullableString("device_short_id"),
            startedAtEpochMillis = long("started_epoch_ms"),
            endedAtEpochMillis = nullableLong("ended_epoch_ms"),
            durationMillis = durations.totalMillis,
            steps = int("steps"),
            caloriesKcal = double("calories"),
            dominantActivity = durations.dominantRecognizedActivity(),
            routePointCount = int("route_point_count"),
            exportState = readExportState(),
        )
    }

    private fun Cursor.readDurations() = ActivityDurationBreakdown(
        walkingMillis = long("walking_ms"), runningMillis = long("running_ms"),
        cyclingMillis = long("cycling_ms"), sittingMillis = long("sitting_ms"),
        lyingMillis = long("lying_ms"), unknownMillis = long("unknown_ms"),
    )

    private fun Cursor.readExportState(): SessionExportState = when (string("export_state")) {
        "PENDING" -> SessionExportState.Pending
        "EXPORTING" -> SessionExportState.Exporting
        "EXPORTED" -> SessionExportState.Exported(
            nullableString("export_summary_name").orEmpty(), nullableString("export_route_name").orEmpty(),
        )
        "ERROR" -> SessionExportState.Error(nullableString("export_error") ?: "Unknown export error")
        else -> SessionExportState.PendingFolder
    }

    private fun Cursor.toRoutePoint() = RoutePoint(
        latitude = double("latitude"), longitude = double("longitude"),
        accuracyMeters = nullableDouble("accuracy_m")?.toFloat(),
        timestampMillis = long("timestamp_epoch_ms"),
        activity = ActivityType.fromWire(string("activity")),
    )

    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.double(name: String) = getDouble(getColumnIndexOrThrow(name))
    private fun Cursor.nullableString(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.nullableLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.nullableDouble(name: String): Double? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getDouble(it) }

    companion object {
        private const val DATABASE_NAME = "activity_tracker.db"
        private const val DATABASE_VERSION = 1
    }
}
