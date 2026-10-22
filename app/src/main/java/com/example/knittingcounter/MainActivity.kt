package com.example.knittingcounter

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.util.Log
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val REQ_PERMISSIONS = 1001

    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var counterText: TextView
    private lateinit var targetDisplay: TextView
    private lateinit var heardWord: TextView
    private lateinit var targetInput: EditText

    private lateinit var vosk: VoskEngine

    private var count = 0
    private var target: Int? = null
    private var rowDone = false

    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        createNotificationChannel()

        vosk = VoskEngine(this).apply {
            onText = { text ->
                handleCommand(text)
            }
            onStatus = { title, detail ->
                renderListening(title, detail)
            }
        }
        vosk.initialize { success ->
            Log.d(TAG, "model init success=$success")
            if (!success) {
                renderListening(getString(R.string.error_model), "")
            }
        }

        Log.d(TAG, "onCreate done, requesting permissions")
        requestPermissionsIfNeeded()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        counterText = findViewById(R.id.counterText)
        targetDisplay = findViewById(R.id.targetDisplay)
        heardWord = findViewById(R.id.heardWord)
        targetInput = findViewById(R.id.targetInput)

        findViewById<Button>(R.id.setTargetButton).setOnClickListener {
            val raw = targetInput.text.toString().trim()
            if (raw.isEmpty()) {
                target = null
                targetInput.hint = getString(R.string.target_hint)
            } else {
                target = raw.toIntOrNull()?.takeIf { it > 0 }
            }
            targetInput.text.clear()
            rowDone = false
            renderCounter()
        }

        findViewById<Button>(R.id.resetButton).setOnClickListener { resetCount() }

        findViewById<Button>(R.id.decreaseButton).setOnClickListener {
            if (count > 0) count--
            rowDone = false
            renderCounter()
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                needed.toTypedArray(),
                REQ_PERMISSIONS
            )
        } else {
            startListening()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (micOk) {
                startListening()
            } else {
                renderListening(getString(R.string.need_permission), "")
                Toast.makeText(this, R.string.need_permission, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (!vosk.isReady()) {
            vosk.initialize { if (it) startListening() }
            return
        }
        vosk.start()
    }

    override fun onResume() {
        super.onResume()
        startListening()
    }

    override fun onPause() {
        super.onPause()
        vosk.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        vosk.shutdown()
    }

    private fun handleCommand(spoken: String) {
        heardWord.text = "«$spoken»"
        renderListening("🎤 Слушаю...", "")

        val low = spoken.lowercase(Locale.ROOT)
            .replace(',', ' ')
            .replace('.', ' ')
        val tokens = low.split(Regex("\\s+")).filter { it.isNotBlank() }

        if (tokens.any { it in RESET_WORDS }) {
            Log.d(TAG, "RESET command: $spoken")
            resetCount()
            return
        }

        val value = tokens.firstNotNullOfOrNull { parseNumber(it) }
        Log.d(TAG, "speech '$spoken' -> value=$value (tokens=$tokens)")
        if (value != null) {
            incrementBy(value)
        }
    }

    private fun parseNumber(word: String): Int? {
        if (word in NUMBER_WORDS) return NUMBER_WORDS[word]
        word.toIntOrNull()?.let {
            if (it in 1..10) return it
        }
        return null
    }

    private fun incrementBy(value: Int) {
        if (rowDone) return
        count += value
        shortBuzz()
        renderCounter()

        val t = target
        if (t != null && count >= t) {
            rowDone = true
            renderListening("Ряд готов! 🎉", getString(R.string.status_ready_hint))
            specialBuzz()
            showRowReadyNotification()
        }
    }

    private fun resetCount() {
        count = 0
        rowDone = false
        target = null
        heardWord.text = ""
        renderCounter()
    }

    private fun renderCounter() {
        counterText.text = count.toString()
        val t = target
        targetDisplay.text = if (t != null) {
            getString(R.string.target_set, t)
        } else {
            getString(R.string.target_unset)
        }
        counterText.setTextColor(
            ContextCompat.getColor(
                this,
                if (rowDone) R.color.listeningColor else R.color.colorAccent
            )
        )
    }

    private fun renderListening(title: String, detail: String) {
        statusText.text = title
        if (statusDetail.text.toString() != detail) {
            statusDetail.text = detail
        }
    }

    private fun shortBuzz() {
        try {
            val effect = VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        } catch (_: Exception) {
        }
    }

    private fun specialBuzz() {
        try {
            val pattern = longArrayOf(0, 250, 120, 250, 120, 500)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                ROW_CHANNEL_ID,
                getString(R.string.row_ready_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.row_ready_notification_text, 0)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showRowReadyNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
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
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val TAG = "KnittingCounter"
        private const val ROW_CHANNEL_ID = "rows_channel"
        private const val NOTIFICATION_ID = 1

        private val NUMBER_WORDS = linkedMapOf(
            "раз" to 1,
            "один" to 1,
            "два" to 2,
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
            "сброс",
            "сбросить",
            "обнулить",
            "обнули",
            "ноль",
            "заново",
        )
    }
}