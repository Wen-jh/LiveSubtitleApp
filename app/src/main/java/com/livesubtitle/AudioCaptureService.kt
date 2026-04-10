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
import kotlinx.coroutines.selects.select

/**
 * 音频捕获服务
 * 使用 AudioPlaybackCapture API 捕获系统音频
 */
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
        private const val CHANNEL_ID = "AudioCaptureChannel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "AudioCaptureService"
        private const val SEND_INTERVAL_MS = 3000L

        // 使用 ConcurrentLinkedQueue 或 StateFlow 来更好地管理音频数据
        private val _audioDataChannel = Channel<ByteArray>(capacity = Channel.RENDEZVOUS)
        val audioDataChannel: Channel<ByteArray>
            get() {
                // 如果 channel 已关闭，创建一个新的
                if (_audioDataChannel.isClosedForSend) {
                    return Channel<ByteArray>(Channel.RENDEZVOUS)
                }
                return _audioDataChannel
            }

        @Volatile
        var isServiceRunning = false
            private set

        // 重置 Channel（用于服务重启时）
        fun resetChannel() {
            if (_audioDataChannel.isClosedForSend) {
                // Channel 已被关闭，需要在下一次访问时创建新的
            }
        }

        fun stopService() {
            isServiceRunning = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "Android 10+ required")
            stopSelf()
            return START_NOT_STICKY
        }

        // 从 Application 类获取 MediaProjection 数据
        val resultCode = LiveSubtitleApp.mediaProjectionResultCode
        val data = LiveSubtitleApp.mediaProjectionData

        Log.d(TAG, "onStartCommand: resultCode=$resultCode, data=${data != null}")

        if (resultCode == Activity.RESULT_OK && data != null) {
            startForeground(NOTIFICATION_ID, createNotification())
            setupMediaProjection(resultCode, data)
            startCapture()
        } else {
            Log.e(TAG, "Invalid MediaProjection data, stopping service")
            stopSelf()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        stopCapture()
        serviceScope.cancel()

        // 清除 Application 中的 MediaProjection 数据
        LiveSubtitleApp.clearMediaProjection()

        mainHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Service destroyed")
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
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    mainHandler.post {
                        stopCapture()
                        stopSelf()
                    }
                }
            }, mainHandler)

            Log.d(TAG, "MediaProjection setup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup MediaProjection", e)
            stopSelf()
        }
    }

    private fun startCapture() {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        val projection = mediaProjection ?: run {
            Log.e(TAG, "MediaProjection is null")
            stopSelf()
            return
        }

        try {
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val minBufferSize = sampleRate * 2  // 至少 1 秒的缓冲
            bufferSize = maxOf(bufferSize, minBufferSize)

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
                Log.e(TAG, "AudioRecord initialization failed")
                stopSelf()
                return
            }

            isRecording = true
            record.startRecording()
            Log.d(TAG, "Recording started")

            serviceScope.launch {
                captureAudioLoop()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Missing audio capture permission", e)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            stopSelf()
        }
    }

    private fun stopCapture() {
        if (!isRecording) return

        isRecording = false

        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioRecord", e)
            }
        }
        audioRecord = null

        mediaProjection?.let { projection ->
            try {
                projection.unregisterCallback(projectionCallback)
                projection.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MediaProjection", e)
            }
        }
        mediaProjection = null

        Log.d(TAG, "Recording stopped")
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "Projection callback onStop")
            mainHandler.post {
                stopCapture()
            }
        }
    }

    private suspend fun captureAudioLoop() {
        val record = audioRecord ?: return
        val buffer = ByteArray(bufferSize)
        val audioChunks = mutableListOf<ByteArray>()
        var lastSendTime = System.currentTimeMillis()

        while (isRecording && serviceScope.isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                val bytesRead = record.read(buffer, 0, bufferSize)

                if (bytesRead > 0) {
                    val validChunk = buffer.copyOf(bytesRead)
                    audioChunks.add(validChunk)

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastSendTime >= SEND_INTERVAL_MS) {
                        if (audioChunks.isNotEmpty()) {
                            sendAudioData(audioChunks)
                            audioChunks.clear()
                        }
                        lastSendTime = currentTime
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading audio", e)
            }

            delay(10)
        }

        // 发送剩余数据
        if (audioChunks.isNotEmpty() && isServiceRunning) {
            sendAudioData(audioChunks)
        }
    }

    private fun sendAudioData(chunks: List<ByteArray>) {
        if (chunks.isEmpty()) return

        try {
            val totalSize = chunks.sumOf { it.size }
            val audioData = ByteArray(totalSize)
            var offset = 0

            for (chunk in chunks) {
                System.arraycopy(chunk, 0, audioData, offset, chunk.size)
                offset += chunk.size
            }

            // 非阻塞发送，如果 channel 满了就丢弃旧数据
            if (!_audioDataChannel.isClosedForSend) {
                _audioDataChannel.trySend(audioData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio data", e)
        }
    }
}
