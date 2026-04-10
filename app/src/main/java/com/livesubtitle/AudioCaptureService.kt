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
import android.media.MediaRecorder
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

/**
 * 音频捕获服务
 * 使用 AudioPlaybackCapture API 捕获系统内部音频（仅支持 Android 10+ / API 29+）
 */
class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false // 标记为volatile，确保多线程可见性
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper()) // 用于MediaProjection回调

    // 音频参数（Whisper 推荐采样率）
    private val sampleRate = 16000
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
        
        // 限定Channel的接收方为单例，且添加关闭机制
        private val _audioDataChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        val audioDataChannel: Channel<ByteArray> = _audioDataChannel // 对外暴露只读Channel

        /** 关闭音频数据通道（在应用退出时调用） */
        fun closeAudioChannel() {
            if (!_audioDataChannel.isClosedForSend) {
                _audioDataChannel.close()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "AudioCaptureService 创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. 检查API版本（AudioPlaybackCapture仅支持Android 10+）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "AudioPlaybackCapture 仅支持 Android 10 (API 29) 及以上版本")
            stopSelf()
            return START_NOT_STICKY
        }

        // 2. 解析MediaProjection参数
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode == Activity.RESULT_OK && data != null) {
            // 启动前台服务（必须，否则Android 8+会崩溃）
            startForeground(NOTIFICATION_ID, createNotification())
            // 初始化MediaProjection并启动捕获
            setupMediaProjection(resultCode, data)
            startCapture()
        } else {
            Log.e(TAG, "MediaProjection 参数无效，停止服务")
            stopSelf()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AudioCaptureService 销毁")
        // 1. 停止音频捕获
        stopCapture()
        // 2. 取消协程作用域（取消所有子协程）
        serviceScope.cancel()
        // 3. 关闭数据通道（避免内存泄漏）
        Companion.closeAudioChannel()
        // 4. 清理Handler回调
        mainHandler.removeCallbacksAndMessages(null)
    }

    /**
     * 创建通知渠道（Android 8+ 必需）
     */
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

    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("实时字幕翻译")
            .setContentText("正在捕获音频...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // 系统内置图标，避免构建错误
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true) // 静音通知，避免打扰用户
            .build()
    }

    /**
     * 初始化MediaProjection（音频捕获的核心依赖）
     */
    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data).also { projection ->
            // 注册MediaProjection回调（指定主线程Handler，避免线程问题）
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d(TAG, "MediaProjection 已停止")
                        mainHandler.post { stopCapture() } // 切换到主线程停止捕获
                    }
                },
                mainHandler
            )
        }
    }

    /**
     * 启动音频捕获
     */
    private fun startCapture() {
        if (isRecording) {
            Log.w(TAG, "音频捕获已在运行，无需重复启动")
            return
        }

        // 空安全检查：MediaProjection必须初始化完成
        val projection = mediaProjection ?: run {
            Log.e(TAG, "MediaProjection 未初始化，启动捕获失败")
            stopSelf()
            return
        }

        try {
            // 计算最小缓冲区大小（兼容不同设备）
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            // 确保缓冲区至少能容纳1秒的音频数据（16bit=2字节/采样点）
            val minBufferSize = sampleRate * 2
            bufferSize = if (bufferSize < minBufferSize) minBufferSize else bufferSize

            // 配置AudioPlaybackCapture（仅捕获媒体/游戏音频）
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .addMatchingContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()

            // 构建AudioRecord（音频捕获核心类）
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 4) // 扩大缓冲区，避免数据丢失
                .build()

            // 检查AudioRecord初始化状态
            val record = audioRecord ?: return
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败，状态：${record.state}")
                stopSelf()
                return
            }

            // 开始录音并启动协程采集数据
            isRecording = true
            record.startRecording()
            Log.d(TAG, "音频捕获已启动，缓冲区大小：$bufferSize 字节")

            serviceScope.launch {
                captureAudioLoop()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "音频捕获权限不足：${e.message}", e)
            stopSelf()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "AudioRecord 参数错误：${e.message}", e)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "启动音频捕获失败：${e.message}", e)
            stopSelf()
        }
    }

    /**
     * 停止音频捕获并释放资源
     */
    private fun stopCapture() {
        if (!isRecording) return

        Log.d(TAG, "停止音频捕获")
        isRecording = false

        // 释放AudioRecord
        audioRecord?.apply {
            try {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            } catch (e: Exception) {
                Log.e(TAG, "释放AudioRecord失败：${e.message}", e)
            }
        }
        audioRecord = null

        // 停止MediaProjection
        mediaProjection?.apply {
            unregisterCallback(null) // 移除所有回调
            stop()
        }
        mediaProjection = null
    }

    /**
     * 音频采集循环：持续读取音频数据，定期发送到Channel
     */
    private suspend fun captureAudioLoop() {
        val record = audioRecord ?: run {
            Log.e(TAG, "AudioRecord 为空，退出采集循环")
            return
        }

        val buffer = ByteArray(bufferSize)
        val audioChunks = mutableListOf<ByteArray>()
        var lastSendTime = System.currentTimeMillis()

        // 循环采集（直到停止标记为false或协程取消）
        while (isRecording && isActive) {
            // 读取音频数据（处理不同错误码）
            val bytesRead = record.read(buffer, 0, bufferSize)
            when (bytesRead) {
                AudioRecord.ERROR_INVALID_OPERATION -> {
                    Log.e(TAG, "AudioRecord 操作无效（未初始化/已停止）")
                    break
                }
                AudioRecord.ERROR_BAD_VALUE -> {
                    Log.e(TAG, "AudioRecord 读取参数错误")
                    break
                }
                AudioRecord.ERROR_DEAD_OBJECT -> {
                    Log.e(TAG, "AudioRecord 底层对象已销毁")
                    break
                }
                -1 -> {
                    Log.e(TAG, "AudioRecord 未知读取错误")
                    break
                }
                0 -> {
                    // 读取到0字节，短暂延迟后重试
                    delay(10)
                    continue
                }
                else -> {
                    // 读取到有效数据，复制并缓存
                    val validChunk = buffer.copyOf(bytesRead)
                    audioChunks.add(validChunk)

                    // 达到发送间隔，合并数据并发送
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastSendTime >= SEND_INTERVAL_MS) {
                        sendAudioData(audioChunks)
                        // 重置缓存和时间
                        audioChunks.clear()
                        lastSendTime = currentTime
                    }
                }
            }

            // 避免CPU空转（10ms延迟）
            delay(10)
        }

        // 发送剩余未发送的音频数据
        if (audioChunks.isNotEmpty()) {
            sendAudioData(audioChunks)
        }

        Log.d(TAG, "音频采集循环结束")
    }

    /**
     * 合并音频数据并发送到Channel（封装成独立方法，便于维护）
     */
    private fun sendAudioData(chunks: List<ByteArray>) {
        try {
            val totalSize = chunks.sumOf { it.size }
            val audioData = ByteArray(totalSize)
            var offset = 0

            // 合并所有音频分片
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, audioData, offset, chunk.size)
                offset += chunk.size
            }

            // 发送到Channel（处理通道关闭异常）
            _audioDataChannel.trySendBlocking(audioData)
            Log.d(TAG, "发送音频数据：$totalSize 字节（约 ${totalSize / (sampleRate * 2)} 秒）")

        } catch (e: ClosedSendChannelException) {
            Log.w(TAG, "音频数据通道已关闭，跳过发送", e)
        } catch (e: Exception) {
            Log.e(TAG, "发送音频数据失败：${e.message}", e)
        }
    }
}
