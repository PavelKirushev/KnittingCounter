package com.example.knittingcounter

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.util.Log

class MainActivity : AppCompatActivity(), CounterService.CounterListener {

    private val REQ_PERMISSIONS = 1001

    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var counterText: TextView
    private lateinit var targetDisplay: TextView
    private lateinit var heardWord: TextView
    private lateinit var targetInput: EditText

    private var service: CounterService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? CounterService.LocalBinder ?: return
            service = local.service
            bound = true
            local.registerListener(this@MainActivity)
            pushInitialState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        createRowChannel()
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
            val s = service ?: return@setOnClickListener
            val raw = targetInput.text.toString().trim()
            s.binder.setTarget(
                if (raw.isEmpty()) null else raw.toIntOrNull()?.takeIf { it > 0 }
            )
            counterText.text = "0"
            service?.binder?.clearCount()
            targetInput.text.clear()
        }

        findViewById<Button>(R.id.resetButton).setOnClickListener {
            service?.binder?.reset()
        }

        findViewById<Button>(R.id.decreaseButton).setOnClickListener {
            service?.binder?.decrement()
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
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
        } else {
            startAndBind()
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
                startAndBind()
            } else {
                statusText.text = getString(R.string.need_permission)
                statusDetail.text = ""
                Toast.makeText(this, R.string.need_permission, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startAndBind() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            ContextCompat.startForegroundService(this, Intent(this, CounterService::class.java))
        }
        if (!bound) {
            try {
                bindService(
                    Intent(this, CounterService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            } catch (e: Exception) {
                Log.e(TAG, "bind failed: ${e.message}")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startAndBind()
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            val s = service
            if (s != null) s.binder.unregisterListener(this)
            try {
                unbindService(connection)
            } catch (_: Exception) {
            }
            bound = false
            service = null
        }
    }

    override fun onState(
        count: Int,
        target: Int?,
        rowDone: Boolean,
        heard: String,
        title: String,
        detail: String
    ) {
        counterText.text = count.toString()
        heardWord.text = heard
        statusText.text = title
        if (statusDetail.text.toString() != detail) {
            statusDetail.text = detail
        }
        targetDisplay.text = if (target != null) {
            getString(R.string.target_set, target)
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

    private fun pushInitialState() {
        val s = service ?: return
        onState(
            count = s.binder.getCount(),
            target = s.binder.getTarget(),
            rowDone = s.binder.getRowDone(),
            heard = s.binder.getLastHeard(),
            title = statusText.text.toString(),
            detail = statusDetail.text.toString()
        )
    }

    private fun createRowChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                "rows_channel",
                getString(R.string.row_ready_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "KnittingCounter"
    }
}