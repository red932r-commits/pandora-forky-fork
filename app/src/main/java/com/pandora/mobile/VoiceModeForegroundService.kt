package com.pandora.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Android lifecycle adapter for an explicitly started background Voice Mode session. */
class VoiceModeForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var voiceStarted = false
    private var stopping = false

    private val app: PandoraApplication
        get() = application as PandoraApplication

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice Mode", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps an explicitly started hands-free Codex conversation active"
            },
        )
        stateJob = scope.launch {
            app.voiceMode.state.collectLatest { state ->
                when {
                    state.active -> notifyState(state)
                    voiceStarted -> stopVoiceMode()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVoiceMode()
            return START_NOT_STICKY
        }

        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val session = app.chatSessions.find(sessionId)
        if (session == null) {
            stopVoiceMode()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(VoiceModeState(active = true, sessionId = sessionId, status = "Starting Voice Mode…")),
            foregroundServiceTypes(),
        )
        voiceStarted = true
        app.voiceMode.start(CodexVoiceModeChatAdapter(session))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopVoiceMode()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (voiceStarted) app.voiceMode.stop()
        stateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun notifyState(state: VoiceModeState) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(state),
        )
    }

    private fun foregroundServiceTypes(): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        else -> 0
    }

    private fun buildNotification(state: VoiceModeState): Notification {
        val openPandora = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopVoiceMode = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceModeForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("Pandora Voice Mode")
            .setContentText(state.status)
            .setContentIntent(openPandora)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop", stopVoiceMode).build())
            .build()
    }

    private fun stopVoiceMode() {
        if (stopping) return
        stopping = true
        voiceStarted = false
        app.voiceMode.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "voice_mode"
        private const val NOTIFICATION_ID = 4103
        private const val ACTION_START = "com.pandora.mobile.voice.START"
        private const val ACTION_STOP = "com.pandora.mobile.voice.STOP"
        private const val EXTRA_SESSION_ID = "session_id"

        fun start(context: Context, sessionId: String) {
            val intent = Intent(context, VoiceModeForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            (context.applicationContext as? PandoraApplication)?.voiceMode?.stop()
            context.stopService(Intent(context, VoiceModeForegroundService::class.java))
        }
    }
}
