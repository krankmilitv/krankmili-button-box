package com.krankmili.buttonbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.krankmili.buttonbox.data.VoiceControl

/** Bridge between the service (recognizer) and whoever holds the PC connection (the ViewModel). */
object VoiceHub {
    @Volatile var onHeard: ((List<String>) -> Unit)? = null
    @Volatile var onState: ((String) -> Unit)? = null
    @Volatile var running = false
}

/**
 * Foreground service (microphone type) that keeps the voice recognizer alive while the app is in the background
 * or the screen is off. It must be started while the app is visible (Android 14+ rule).
 */
class VoiceService : Service() {
    private var voice: VoiceControl? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastHeard = ""
    private var builder: Notification.Builder? = null

    private fun notify(state: String) {
        val b = builder ?: return
        val text = if (lastHeard.isEmpty()) state else "$state · son: $lastHeard"
        b.setContentText(text)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, b.build())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("buttonbox", Context.MODE_PRIVATE).edit().putBoolean("voice_on", false).apply()
            VoiceHub.onState?.invoke("kapalı")
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (voice == null) {
            voice = VoiceControl(this, { heard ->
                lastHeard = heard.firstOrNull().orEmpty()
                VoiceHub.onHeard?.invoke(heard)
            }, { st ->
                // Mirror the recognizer state into the notification so background behaviour is visible without the app open.
                if (st.isNotEmpty()) notify(st)
                VoiceHub.onState?.invoke(st)
            })
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "krankmili:voice").apply { setReferenceCounted(false); acquire() }
            VoiceHub.running = true
            voice?.start()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sesli komut", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val nb = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Sesli komut dinliyor")
            .setContentText("\"Hey Krank …\" — durdurmak için dokun")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Durdur", stop).build())
            .setOngoing(true)
        builder = nb
        val n = nb.build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(ID, n)
        }
    }

    override fun onDestroy() {
        voice?.stop()
        voice = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        VoiceHub.running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.krankmili.buttonbox.STOP_VOICE"
        private const val CHANNEL = "voice"
        private const val ID = 71

        fun start(ctx: Context) {
            val i = Intent(ctx, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, VoiceService::class.java))
        }
    }
}
