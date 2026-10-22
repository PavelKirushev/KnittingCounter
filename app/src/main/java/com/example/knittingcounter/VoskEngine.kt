package com.example.knittingcounter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class VoskEngine(private val context: Context) {

    private var model: Model? = null
    @Volatile private var isRunning = false
    private var recThread: Thread? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var onText: ((String) -> Unit)? = null
    var onStatus: ((String, String) -> Unit)? = null

    fun isReady(): Boolean = model != null

    fun initialize(callback: (Boolean) -> Unit) {
        if (model != null) {
            callback(true)
            return
        }
        StorageService.unpack(context, "model-ru", "model",
            { ready ->
                this.model = ready
                Log.d(TAG, "Vosk model unpacked")
                callback(true)
            },
            { exception ->
                Log.e(TAG, "StorageService.unpack failed: ${exception.message}")
                try {
                    val model = Model("assets://model-ru")
                    this.model = model
                    Log.d(TAG, "Vosk model loaded directly from assets")
                    callback(true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load model from assets://: ${e.message}")
                    loadModelFromFilesDir("model-ru", callback)
                }
            }
        )
    }

    private fun loadModelFromFilesDir(modelAssetPath: String, callback: (Boolean) -> Unit) {
        try {
            val destDir = File(context.filesDir, VOCAB_MODEL_DIR)
            copyAssetDir(context.assets, modelAssetPath, destDir)
            val models = destDir.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?.joinToString(", ") ?: "none"
            Log.d(TAG, "Unpacked model, subdirs: $models")
            val model = Model(destDir.absolutePath)
            this.model = model
            Log.d(TAG, "Vosk model loaded from ${destDir.absolutePath}")
            callback(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model from files dir: ${e.message}")
            callback(false)
        }
    }

    private fun copyAssetDir(assets: AssetManager, path: String, destDir: File) {
        val entries = assets.list(path)
        if (entries == null) return
        for (entry in entries) {
            val assetPath = if (path.isEmpty()) entry else "$path/$entry"
            val dest = File(destDir, entry)
            if ((assets.list(assetPath)?.isNotEmpty()) == true) {
                dest.mkdirs()
                copyAssetDir(assets, assetPath, dest)
            } else {
                dest.parentFile?.mkdirs()
                assets.open(assetPath).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    fun start() {
        onStatus?.invoke("🎤 Слушаю...", getString(R.string.status_ready_hint))
        if (isRunning) return
        val m = model ?: run {
            initialize { if (it) start() }
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        isRunning = true

        recThread = thread(name = "vosk-recognition") {
            var rec: Recognizer? = null
            var record: AudioRecord? = null
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (bufferSize == AudioRecord.ERROR_BAD_VALUE ||
                    bufferSize == AudioRecord.ERROR
                ) {
                    Log.e(TAG, "Invalid buffer size for AudioRecord")
                    postStatus(getString(R.string.error_listen), "")
                    return@thread
                }

                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord not initialized")
                    postStatus(getString(R.string.error_listen), "")
                    return@thread
                }

                rec = Recognizer(m, SAMPLE_RATE.toFloat())

                record.startRecording()
                val buffer = ByteArray(bufferSize)
                while (isRunning) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read < 0) continue
                    val isFinal = rec.acceptWaveForm(buffer, read)
                    if (isFinal) {
                        val result = rec.result
                        val text = extractText(result)
                        if (text.isNotEmpty()) {
                            Log.d(TAG, "recognized: '$text'")
                            mainHandler.post { onText?.invoke(text) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recognition loop error: ${e.message}")
                postStatus(getString(R.string.error_listen), "")
            } finally {
                try { rec?.close() } catch (_: Exception) {}
                try { record?.stop() } catch (_: Exception) {}
                try { record?.release() } catch (_: Exception) {}
                recognizer = null
                audioRecord = null
            }
        }
    }

    fun stop() {
        isRunning = false
        recThread?.join(1500)
        recThread = null
        recognizer = null
        audioRecord = null
    }

    fun shutdown() {
        stop()
        model?.close()
        model = null
    }

    private fun extractText(json: String?): String {
        if (json.isNullOrBlank()) return ""
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return ""
        return try {
            JSONObject(trimmed).optString("text", "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun postStatus(title: String, detail: String) {
        mainHandler.post { onStatus?.invoke(title, detail) }
    }

    private fun getString(id: Int): String = context.getString(id)

    companion object {
        private const val TAG = "VoskEngine"
        private const val SAMPLE_RATE = 16000
        private const val VOCAB_MODEL_DIR = "vosk-model"
    }
}