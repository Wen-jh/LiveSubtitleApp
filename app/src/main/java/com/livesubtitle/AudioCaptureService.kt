package com.livesubtitle

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
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking

/**
 * 音频捕获服务
 * 使用 AudioPlaybackCapture API 捕获系统内部音频
 */
class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 音频参数
    private val sampleRate = 16000  // Whisper 推荐采样率
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // 缓冲区大小
    private var bufferSize = 0

    companion object {
        const val CHANNEL_ID = "AudioCaptureChannel"
        const val NOTIFICATION_ID = 1001
        const val TAG = "AudioCaptureService"
        
        // 每 3 秒发送一次数据进行识别（平衡延迟和准确率）
        const val SEND_INTERVAL_MS = 3000L
        
        // 使用 Channel 传递音频数据
        val audioDataChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            .build()
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stopCapture()
            }
        }, null)
    }

    private fun startCapture() {
        if (isRecording) return

        try {
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            // 确保缓冲区足够大（至少 1 秒的数据）
            val minBufferSize = sampleRate * 2  // 16bit = 2 bytes
            if (bufferSize < minBufferSize) {
                bufferSize = minBufferSize
            }

            // 创建 AudioPlaybackCapture 配置
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .addMatchingContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()

            // 创建 AudioRecord
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 4)
                .build()

            audioRecord?.let { record ->
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord 初始化失败")
                    return
                }

                isRecording = true
                record.startRecording()

                // 启动音频采集协程
                serviceScope.launch {
                    captureAudioLoop()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "安全异常: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "启动捕获失败: ${e.message}")
        }
    }

    private fun stopCapture() {
        isRecording = false

        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                Log.e(TAG, "停止录音异常: ${e.message}")
            }
        }
        audioRecord = null

        mediaProjection?.stop()
        mediaProjection = null
    }

    /**
     * 音频采集循环
     * 收集音频数据，定期发送给识别服务
     */
    private suspend fun captureAudioLoop() {
        val buffer = ByteArray(bufferSize)
        val audioChunks = mutableListOf<ByteArray>()
        var lastSendTime = System.currentTimeMillis()

        while (isRecording && audioRecord != null) {
            val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: -1

            if (bytesRead > 0) {
                // 复制数据
                val chunk = buffer.copyOf(bytesRead)
                audioChunks.add(chunk)

                // 检查是否需要发送
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastSendTime >= SEND_INTERVAL_MS) {
                    // 合并音频数据
                    val totalSize = audioChunks.sumOf { it.size }
                    val audioData = ByteArray(totalSize)
                    var offset = 0
                    for (chunk in audioChunks) {
                        System.arraycopy(chunk, 0, audioData, offset, chunk.size)
                        offset += chunk.size
                    }

                    // 发送到识别通道
                    audioDataChannel.trySendBlocking(audioData)

                    // 重置
                    audioChunks.clear()
                    lastSendTime = currentTime

                    Log.d(TAG, "发送音频数据: ${totalSize} bytes (${totalSize / (sampleRate * 2)} 秒)")
                }
            } else if (bytesRead < 0) {
                Log.e(TAG, "读取音频失败: $bytesRead")
                break
            }

            delay(10)  // 避免空转
        }

        // 发送剩余数据
        if (audioChunks.isNotEmpty()) {
            val totalSize = audioChunks.sumOf { it.size }
            val audioData = ByteArray(totalSize)
            var offset = 0
            for (chunk in audioChunks) {
                System.arraycopy(chunk, 0, audioData, offset, chunk.size)
                offset += chunk.size
            }
            audioDataChannel.trySendBlocking(audioData)
        }
    }
}
