package pl.edu.activitytracker.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import pl.edu.activitytracker.domain.ActivityDurationBreakdown
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.HomeSession
import pl.edu.activitytracker.domain.SessionExportState
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface HomeSessionExportResult {
    data class Success(val state: SessionExportState.Exported) : HomeSessionExportResult
    data class Failure(val message: String) : HomeSessionExportResult
}

interface HomeSessionExportDataSource {
    fun export(session: HomeSession, folderUri: String?): HomeSessionExportResult
}

class SafHomeSessionExporter(context: Context) : HomeSessionExportDataSource {
    private val appContext = context.applicationContext

    override fun export(session: HomeSession, folderUri: String?): HomeSessionExportResult {
        if (folderUri.isNullOrBlank()) return HomeSessionExportResult.Failure("Select the data folder first.")
        return try {
            val root = DocumentFile.fromTreeUri(appContext, Uri.parse(folderUri))
                ?: return HomeSessionExportResult.Failure("The selected data folder is unavailable.")
            val directory = root.findFile(DIRECTORY_NAME)
                ?: root.createDirectory(DIRECTORY_NAME)
                ?: return HomeSessionExportResult.Failure("Cannot create the home_sessions folder.")
            val baseName = baseName(session)
            val summaryName = "$baseName.json"
            val routeName = "${baseName}_route.csv"
            writeAtomically(directory, summaryName, HomeSessionExportSerializer.summaryJson(session).toByteArray(Charsets.UTF_8))
            writeAtomically(directory, routeName, HomeSessionExportSerializer.routeCsv(session).toByteArray(Charsets.UTF_8))
            HomeSessionExportResult.Success(SessionExportState.Exported(summaryName, routeName))
        } catch (error: Exception) {
            HomeSessionExportResult.Failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun writeAtomically(directory: DocumentFile, finalName: String, bytes: ByteArray) {
        val partName = "$finalName.part"
        directory.findFile(partName)?.delete()
        val part = directory.createFile("application/octet-stream", partName) ?: error("Cannot create $partName")
        val descriptor = appContext.contentResolver.openFileDescriptor(part.uri, "w")
            ?: error("Cannot open $partName")
        descriptor.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
        }
        directory.findFile(finalName)?.delete()
        check(part.renameTo(finalName)) { "Cannot finalize $finalName" }
    }

    private fun baseName(session: HomeSession): String {
        val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(session.startedAtEpochMillis))
        return "home_${date}_${session.id.take(8)}"
    }

    companion object { private const val DIRECTORY_NAME = "home_sessions" }
}

object HomeSessionExportSerializer {
    fun summaryJson(session: HomeSession): String = JSONObject().apply {
        put("schema_version", 1)
        put("id", session.id)
        put("status", session.status.name.lowercase())
        put("device_id", session.deviceShortId ?: JSONObject.NULL)
        put("started_epoch_ms", session.startedAtEpochMillis)
        put("ended_epoch_ms", session.endedAtEpochMillis ?: JSONObject.NULL)
        put("duration_ms", session.durationMillis)
        put("weight_kg", session.weightKg)
        put("steps", session.steps)
        put("calories_kcal", session.caloriesKcal)
        put("calorie_method", session.calorieMethod)
        put("met_values", JSONObject().apply {
            ActivityDurationBreakdown.RECOGNIZED_ACTIVITIES.forEach { put(it.wireName, metFor(it)) }
        })
        put("activity_duration_ms", JSONObject().apply {
            ActivityType.entries.forEach { put(it.wireName, session.durations.forActivity(it)) }
        })
        put("route_point_count", session.route.size)
    }.toString(2)

    fun routeCsv(session: HomeSession): String = buildString {
        appendLine("timestamp_epoch_ms,latitude,longitude,accuracy_m,activity")
        session.route.forEach { point ->
            append(point.timestampMillis).append(',')
            append(point.latitude).append(',').append(point.longitude).append(',')
            append(point.accuracyMeters ?: "").append(',').append(point.activity.wireName).append('\n')
        }
    }

    private fun metFor(activity: ActivityType): Double = when (activity) {
        ActivityType.Walking -> 3.5
        ActivityType.Running -> 8.0
        ActivityType.Cycling -> 6.8
        ActivityType.Sitting -> 1.3
        ActivityType.Lying -> 1.0
        ActivityType.Unknown -> 0.0
    }

}
