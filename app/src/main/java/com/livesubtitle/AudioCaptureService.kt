package com.livesubtitle

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.trySendBlocking

class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var bufferSize = 0

    companion object {
        const val CHANNEL_ID = "AudioCaptureChannel"
        const val NOTIFICATION_ID = 1001
        const val TAG = "AudioCaptureService"
        const val SEND_INTERVAL_MS = 3000L
        
        private val _audioDataChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        val audioDataChannel: Channel<ByteArray> = _audioDataChannel

        fun closeAudioChannel() {
            if (!_audioDataChannel.isClosedForSend) {
                _audioDataChannel.close()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode == Activity.RESULT_OK && data != null) {
            startForeground(NOTIFICATION_ID, createNotification())
            setupMediaProjection(resultCode, data)
            startCapture()
        } else {
            stopSelf()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        serviceScope.cancel()
        Companion.closeAudioChannel()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "实时字幕翻译",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "音频捕获服务正在运行"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("实时字幕翻译")
            .setContentText("正在捕获音频...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data).also { projection ->
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        mainHandler.post { stopCapture() }
                    }
                },
                mainHandler
            )
        }
    }

    private fun startCapture() {
        if (isRecording) return

        val projection = mediaProjection ?: run {
            stopSelf()
            return
        }

        try {
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val minBufferSize = sampleRate * 2
            bufferSize = if (bufferSize < minBufferSize) minBufferSize else bufferSize

            // ✅ 修复：删除了报错的 addMatchingContentType，只保留兼容的用法
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 4)
                .build()

            val record = audioRecord ?: return
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                stopSelf()
                return
            }

            isRecording = true
            record.startRecording()

            serviceScope.launch {
                captureAudioLoop()
            }

        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun stopCapture() {
        if (!isRecording) return

        isRecording = false
        audioRecord?.apply {
            try {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            } catch (e: Exception) {}
        }
        audioRecord = null

        mediaProjection?.apply {
            unregisterCallback(null)
            stop()
        }
        mediaProjection = null
    }

    private suspend fun captureAudioLoop() {
        val record = audioRecord ?: return
        val buffer = ByteArray(bufferSize)
        val audioChunks = mutableListOf<ByteArray>()
        var lastSendTime = System.currentTimeMillis()

        while (isRecording && isActive) {
            val bytesRead = record.read(buffer, 0, bufferSize)
            if (bytesRead <= 0) {
                delay(10)
                continue
            }

            val validChunk = buffer.copyOf(bytesRead)
            audioChunks.add(validChunk)

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSendTime >= SEND_INTERVAL_MS) {
                sendAudioData(audioChunks)
                audioChunks.clear()
                lastSendTime = currentTime
            }
            delay(10)
        }

        if (audioChunks.isNotEmpty()) {
            sendAudioData(audioChunks)
        }
    }

    private fun sendAudioData(chunks: List<ByteArray>) {
        try {
            val totalSize = chunks.sumOf { it.size }
            val audioData = ByteArray(totalSize)
            var offset = 0

            for (chunk in chunks) {
                System.arraycopy(chunk, 0, audioData, offset, chunk.size)
                offset += chunk.size
            }

            _audioDataChannel.trySendBlocking(audioData)
        } catch (e: ClosedSendChannelException) {
        } catch (e: Exception) {}
    }
}
