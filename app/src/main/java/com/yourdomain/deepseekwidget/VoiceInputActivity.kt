package com.yourdomain.deepseekwidget

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.yourdomain.deepseekwidget.Constants.DEEPSEEK_PACKAGE
import com.yourdomain.deepseekwidget.Constants.EXTRA_LAUNCH_CAMERA
import com.yourdomain.deepseekwidget.Constants.EXTRA_LAUNCH_VOICE
import com.yourdomain.deepseekwidget.Constants.EXTRA_SKIP_VOICE
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trampoline activity for widget taps: camera capture, voice input, chat routing.
 *
 * Voice flow (one tap on the widget mic):
 *   1. RecognizerIntent (like the original widget) if a recognizer activity exists;
 *   2. otherwise SpeechRecognizer API directly — works through the system
 *      RecognitionService provided by Gboard / Speech Services, no Google app needed;
 *   3. recognized text is shared to DeepSeek as text/plain (DeepSeek does NOT accept
 *      audio files as voice messages — only text and images).
 */
class VoiceInputActivity : AppCompatActivity() {

    private var currentPhotoPath: String? = null

    // ── Voice recognition state ────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var listeningStartMs = 0L
    private var lastPartialText: String? = null
    private var stopFallbackRunnable: Runnable? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateListeningTimer()
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Restore photo path if we're coming back from a process death
        currentPhotoPath = savedInstanceState?.getString(Constants.KEY_PHOTO_PATH)

        val launchCamera = intent.getBooleanExtra(EXTRA_LAUNCH_CAMERA, false)
        val launchVoice = intent.getBooleanExtra(EXTRA_LAUNCH_VOICE, false)
        val skipVoice = intent.getBooleanExtra(EXTRA_SKIP_VOICE, false)

        when {
            launchCamera -> startCameraFlow()
            launchVoice  -> startVoiceFlow()
            skipVoice    -> routeToDeepSeekNative("chat")
            else         -> routeToDeepSeekNative("chat")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(Constants.KEY_PHOTO_PATH, currentPhotoPath)
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(timerRunnable)
        stopFallbackRunnable?.let { timerHandler.removeCallbacks(it) }
        if (isListening) {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }

    override fun onBackPressed() {
        timerHandler.removeCallbacks(timerRunnable)
        stopFallbackRunnable?.let { timerHandler.removeCallbacks(it) }
        if (isListening) {
            speechRecognizer?.cancel()
            speechRecognizer = null
            isListening = false
        }
        super.onBackPressed()
    }

    // ── Camera ──────────────────────────────────────────────────────────

    private fun startCameraFlow() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        } else {
            openCamera()
        }
    }

    private fun openCamera() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        try {
            val photoFile: File? = try {
                createImageFile()
            } catch (ex: Exception) {
                Log.e(TAG, "Error creating image file", ex)
                null
            }

            photoFile?.also {
                val photoURI: Uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    it
                )
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ── Voice input ─────────────────────────────────────────────────────

    private fun startVoiceFlow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERMISSION)
            return
        }

        // 1. RecognizerIntent — если на устройстве есть activity-распознаватель
        //    (Google app / Speech Services UI). Как в оригинальном виджете.
        val resolvers = packageManager.queryIntentActivities(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0
        )
        if (resolvers.isNotEmpty()) {
            startSystemVoiceRecognition()
            return
        }

        // 2. SpeechRecognizer API напрямую — работает через RecognitionService,
        //    который предоставляет Gboard / Speech Services (без Google app).
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            startInAppSpeechRecognition()
            return
        }

        // 3. Распознавание речи вообще недоступно
        Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Old path: system recognizer activity with its own UI. */
    private fun startSystemVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to DeepSeek")
        }
        try {
            startActivityForResult(intent, REQUEST_VOICE_RECOGNIZE)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Voice recognition not supported", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** In-app listening screen backed by SpeechRecognizer API (Gboard/Speech Services). */
    private fun startInAppSpeechRecognition() {
        setContentView(R.layout.activity_voice_record)

        findViewById<Button>(R.id.stop_button).setOnClickListener { stopListening() }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        lastPartialText = null
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                stopListening()
            }
            override fun onError(error: Int) {
                Log.e(TAG, "SpeechRecognizer error: $error")
                stopFallbackRunnable?.let { timerHandler.removeCallbacks(it) }
                stopFallbackRunnable = null
                timerHandler.removeCallbacks(timerRunnable)
                isListening = false
                speechRecognizer?.destroy()
                speechRecognizer = null
                Toast.makeText(
                    this@VoiceInputActivity,
                    "Ошибка распознавания ($error)",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                stopFallbackRunnable?.let { timerHandler.removeCallbacks(it) }
                stopFallbackRunnable = null
                timerHandler.removeCallbacks(timerRunnable)
                isListening = false
                speechRecognizer?.destroy()
                speechRecognizer = null
                if (!text.isNullOrBlank()) {
                    shareTextToDeepSeek(text)
                } else {
                    Toast.makeText(this@VoiceInputActivity, R.string.voice_unavailable, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                // Копим частично распознанный текст — пригодится, если stopListening
                // не вернёт onResults (некоторые распознаватели игнорируют его).
                lastPartialText = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            listeningStartMs = System.currentTimeMillis()
            timerHandler.post(timerRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateListeningTimer() {
        val elapsed = (System.currentTimeMillis() - listeningStartMs) / 1000
        val mm = elapsed / 60
        val ss = elapsed % 60
        findViewById<TextView>(R.id.recording_timer)?.text =
            String.format(Locale.US, "%d:%02d", mm, ss)
    }

    private fun stopListening() {
        if (!isListening) {
            finish()
            return
        }
        // Просим распознаватель остановиться (асинхронно).
        speechRecognizer?.stopListening()
        // Фолбэк: некоторые распознаватели (Gboard) игнорируют stopListening и
        // продолжают слушать до паузы. Если через 3 с результат не пришёл —
        // завершаем принудительно (используем последний частичный текст).
        val fallback = Runnable {
            if (isListening) {
                Log.d(TAG, "stopListening fallback: forcing finish")
                timerHandler.removeCallbacks(timerRunnable)
                isListening = false
                speechRecognizer?.destroy()
                speechRecognizer = null
                val partial = lastPartialText
                if (!partial.isNullOrBlank()) {
                    shareTextToDeepSeek(partial)
                } else {
                    Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
        stopFallbackRunnable?.let { timerHandler.removeCallbacks(it) }
        stopFallbackRunnable = fallback
        timerHandler.postDelayed(fallback, 3000)
    }

    // ── Permissions ─────────────────────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CAMERA_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    openCamera()
                } else {
                    Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            REQUEST_AUDIO_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startVoiceFlow()
                } else {
                    Toast.makeText(this, R.string.perm_audio_denied, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    // ── Activity results (camera, system voice) ─────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                REQUEST_IMAGE_CAPTURE -> {
                    currentPhotoPath?.let { path ->
                        val file = File(path)
                        val uri = FileProvider.getUriForFile(
                            this,
                            "${packageName}.fileprovider",
                            file
                        )
                        shareToDeepSeek(uri, "image/jpeg")
                    } ?: finish()
                }
                REQUEST_VOICE_RECOGNIZE -> {
                    val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    val spokenText = results?.get(0)
                    if (spokenText != null) {
                        shareTextToDeepSeek(spokenText)
                    } else {
                        finish()
                    }
                }
            }
        } else {
            finish()
        }
    }

    // ── Sharing to DeepSeek ─────────────────────────────────────────────

    private fun shareToDeepSeek(contentUri: Uri, mimeType: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            setPackage(DEEPSEEK_PACKAGE)
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(shareIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share to DeepSeek", e)
            Toast.makeText(this, "DeepSeek app not found", Toast.LENGTH_SHORT).show()
        } finally {
            finish()
        }
    }

    private fun shareTextToDeepSeek(text: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            setPackage(DEEPSEEK_PACKAGE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(shareIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share text to DeepSeek", e)
            Toast.makeText(this, "DeepSeek app not found", Toast.LENGTH_SHORT).show()
        } finally {
            finish()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File? = getExternalFilesDir(null)
        return File.createTempFile(
            "JPEG_${timeStamp}_",
            ".jpg",
            storageDir
        ).apply {
            currentPhotoPath = absolutePath
        }
    }

    /**
     * Routes the user to a specific feature within the DeepSeek app.
     * Uses a combination of custom URI schemes and Package Manager launch intents.
     */
    private fun routeToDeepSeekNative(feature: String) {
        // DeepSeek app package ID
        val packageId = DEEPSEEK_PACKAGE

        // Define feature-specific URIs.
        // DeepSeek app registers for chat.deepseek.com as a verified host.
        val uri = when (feature) {
            "camera" -> Uri.parse("https://chat.deepseek.com/chat?action=camera")
            "voice"  -> Uri.parse("https://chat.deepseek.com/chat?action=voice")
            else     -> Uri.parse("https://chat.deepseek.com")
        }

        try {
            // 1. Attempt to find the launch intent for the package.
            val launchIntent = packageManager.getLaunchIntentForPackage(packageId)

            if (launchIntent != null) {
                // 2. Create a specific VIEW intent for the feature.
                val actionIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(packageId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

                try {
                    startActivity(actionIntent)
                } catch (e: ActivityNotFoundException) {
                    // 3. If deep link action fails, launch the app's main entry point.
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                }
            } else {
                // 4. Fallback to Web if app is not installed.
                launchWebFallback(uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Routing to DeepSeek failed", e)
            launchWebFallback(uri)
        } finally {
            finish()
        }
    }

    private fun launchWebFallback(uri: Uri) {
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(webIntent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.deepseek_open_error, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "VoiceInputActivity"
        private const val REQUEST_IMAGE_CAPTURE = 1001
        private const val REQUEST_VOICE_RECOGNIZE = 1002
        private const val REQUEST_CAMERA_PERMISSION = 1003
        private const val REQUEST_AUDIO_PERMISSION = 1004
    }
}
