package com.example.knittingcounter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class CounterService : Service() {

    val binder = LocalBinder()
    private lateinit var vosk: VoskEngine

    private var count = 0
    private var target: Int? = null
    private var rowDone = false
    private var lastHeard = ""

    private var statusTitle: String = "🎤 Слушаю..."
    private var statusDetail: String = ""

    private val listeners = CopyOnWriteArrayList<CounterListener>()
    private var wakeLock: PowerManager.WakeLock? = null

    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }
    }

    interface CounterListener {
        fun onState(
            count: Int,
            target: Int?,
            rowDone: Boolean,
            heard: String,
            title: String,
            detail: String
        )
    }

    inner class LocalBinder : Binder() {
        val service: CounterService get() = this@CounterService

        fun registerListener(l: CounterListener) = listeners.add(l)
        fun unregisterListener(l: CounterListener) = listeners.remove(l)

        fun getCount(): Int = count
        fun getTarget(): Int? = target
        fun getRowDone(): Boolean = rowDone
        fun getLastHeard(): String = lastHeard

        fun setTarget(t: Int?) {
            target = t
            rowDone = false
            updateState()
        }

        fun clearCount() {
            count = 0
        }

        fun reset() {
            count = 0
            rowDone = false
            target = null
            lastHeard = ""
            updateState()
        }

        fun decrement() {
            if (count > 0) count--
            rowDone = false
            updateState()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createChannels()
        startForeground(NOTIFICATION_ID, buildForegroundNotification().build())

        vosk = VoskEngine(this).apply {
            onStatus = { title, detail ->
                statusTitle = title
                statusDetail = detail
                updateState()
            }
            onText = { handleCommand(it) }
        }
        vosk.initialize { ok ->
            if (ok) {
                vosk.start()
            } else {
                statusTitle = getString(R.string.error_model)
                updateState()
            }
        }

        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        vosk.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun handleCommand(spoken: String) {
        lastHeard = "«$spoken»"
        statusTitle = getString(R.string.listening)
        statusDetail = ""

        val tokens = spoken.lowercase(Locale.ROOT)
            .replace(',', ' ').replace('.', ' ')
            .split(Regex("\\s+")).filter { it.isNotBlank() }

        if (tokens.any { it in RESET_WORDS }) {
            Log.d(TAG, "RESET command: $spoken")
            count = 0
            rowDone = false
            target = null
            updateState()
            return
        }

        val value = tokens.firstNotNullOfOrNull { parseNumber(it) }
        Log.d(TAG, "speech '$spoken' -> value=$value")
        if (value != null) {
            incrementBy(value)
        } else {
            updateState()
        }
    }

    private fun parseNumber(word: String): Int? {
        NUMBER_WORDS[word]?.let { return it }
        word.toIntOrNull()?.let { if (it in 1..10) return it }
        return null
    }

    private fun incrementBy(value: Int) {
        if (rowDone) return
        count += value
        shortBuzz()
        updateState()

        val t = target
        if (t != null && count >= t) {
            rowDone = true
            statusTitle = getString(R.string.row_ready_notification_title)
            statusDetail = getString(R.string.status_ready_hint)
            specialBuzz()
            showRowReadyNotification()
            updateState()
        }
    }

    private fun updateState() {
        updateForegroundNotification()
        val state = State(count, target, rowDone, lastHeard, statusTitle, statusDetail)
        for (l in listeners) {
            try {
                l.onState(state.count, state.target, state.rowDone, state.heard, state.title, state.detail)
            } catch (_: Exception) {
            }
        }
    }

    private fun updateForegroundNotification() {
        try {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildForegroundNotification().build())
        } catch (_: SecurityException) {
        }
    }

    private fun buildForegroundNotification(): NotificationCompat.Builder {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text, count))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun showRowReadyNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val capacity = target ?: return
        val notification = NotificationCompat.Builder(this, ROW_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.row_ready_notification_title))
            .setContentText(getString(R.string.row_ready_notification_text, capacity))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(ROW_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun shortBuzz() {
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private fun specialBuzz() {
        try {
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250, 120, 500), -1)
            )
        } catch (_: Exception) {
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val svc = NotificationChannel(
                SERVICE_CHANNEL_ID,
                getString(R.string.service_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(svc)

            val rows = NotificationChannel(
                ROW_CHANNEL_ID,
                getString(R.string.row_ready_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(rows)
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "KnittingCounter::ListeningWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    data class State(
        val count: Int,
        val target: Int?,
        val rowDone: Boolean,
        val heard: String,
        val title: String,
        val detail: String
    )

    companion object {
        private const val TAG = "CounterService"
        private const val SERVICE_CHANNEL_ID = "counter_service"
        private const val ROW_CHANNEL_ID = "rows_channel"
        private const val NOTIFICATION_ID = 2
        private const val ROW_NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L

        private val NUMBER_WORDS = linkedMapOf(
            "раз" to 1,
            "один" to 1,
            "одна" to 1,
            "два" to 2,
            "две" to 2,
            "три" to 3,
            "четыре" to 4,
            "пять" to 5,
            "шесть" to 6,
            "семь" to 7,
            "восемь" to 8,
            "девять" to 9,
            "десять" to 10,
        )

        private val RESET_WORDS = setOf(
            "сброс", "сбросить", "обнулить", "обнули", "ноль", "заново"
        )
    }
}