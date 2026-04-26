package pl.edu.activitytracker.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import pl.edu.activitytracker.ActivityTrackerApplication
import pl.edu.activitytracker.MainActivity
import pl.edu.activitytracker.R

class SessionRecordingService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            (application as ActivityTrackerApplication)
                .appContainer
                .repository
                .startLocationIfSessionRunning()
        } catch (_: SecurityException) {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        (application as? ActivityTrackerApplication)
            ?.appContainer
            ?.repository
            ?.stopLocation()
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recording session")
            .setContentText("Activity Tracker is recording GPS for this session.")
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Session recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows that Activity Tracker is recording a session."
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "session_recording"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "pl.edu.activitytracker.session.START"

        fun start(context: Context) {
            val intent = Intent(context, SessionRecordingService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionRecordingService::class.java))
        }
    }
}
