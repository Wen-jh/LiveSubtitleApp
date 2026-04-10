package com.livesubtitle

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * 悬浮窗字幕服务
 * 显示实时翻译的字幕
 */
class FloatingSubtitleService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var tvOriginal: TextView? = null
    private var tvTranslated: TextView? = null
    private var overlayContainer: FrameLayout? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isRunning = false

    companion object {
        private const val CHANNEL_ID = "FloatingSubtitleChannel"
        private const val NOTIFICATION_ID = 1002
        private const val TAG = "FloatingSubtitleService"
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        createOverlay()
        startListening()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            showOverlay()
            isRunning = true
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        hideOverlay()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮字幕",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮字幕服务"
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
            .setContentText("悬浮字幕已显示")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * 创建悬浮窗
     */
    @SuppressLint("ClickableViewAccessibility", "InflateParams", "WrongConstant")
    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutInflater = LayoutInflater.from(this)
        overlayView = layoutInflater.inflate(R.layout.overlay_subtitle, null)

        tvOriginal = overlayView?.findViewById(R.id.tvOriginal)
        tvTranslated = overlayView?.findViewById(R.id.tvTranslated)
        overlayContainer = overlayView?.findViewById(R.id.overlayContainer)

        // 设置悬浮窗参数
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 150
        }

        // 添加拖动功能
        setupDragListener(overlayView, params)

        overlayView?.let { view ->
            windowManager?.addView(view, params)
            Log.d(TAG, "Overlay added to window")
        }
    }

    private fun setupDragListener(view: View?, params: WindowManager.LayoutParams) {
        if (view == null) return

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()

                    if (!isDragging && (kotlin.math.abs(deltaX) > 10 || kotlin.math.abs(deltaY) > 10)) {
                        isDragging = true
                    }

                    if (isDragging) {
                        params.x = initialX + deltaX
                        params.y = initialY - deltaY
                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating view layout", e)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && overlayContainer?.isPressed == true) {
                        // 点击事件 - 可以用于切换显示模式等
                    }
                    !isDragging
                }
                else -> false
            }
        }
    }

    private fun showOverlay() {
        overlayView?.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        overlayView?.let { view ->
            try {
                windowManager?.removeViewImmediate(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
        }
        overlayView = null
        tvOriginal = null
        tvTranslated = null
        overlayContainer = null
    }

    /**
     * 监听音频识别结果
     */
    private fun startListening() {
        serviceScope.launch {
            try {
                while (isRunning) {
                    try {
                        val channel = AudioCaptureService.audioDataChannel
                        if (channel.isClosedForReceive) {
                            Log.w(TAG, "Channel closed, waiting for new channel")
                            delay(1000)
                            continue
                        }

                        // 使用 select 配合超时，防止永久阻塞
                        select<Unit> {
                            channel.onReceive { audioData ->
                                if (isRunning && audioData.isNotEmpty()) {
                                    processAudio(audioData)
                                }
                            }
                            onTimeout(5000) {
                                // 超时检查服务是否仍在运行
                                if (!AudioCaptureService.isServiceRunning && isRunning) {
                                    Log.w(TAG, "AudioCaptureService stopped")
                                }
                            }
                        }
                    } catch (e: ClosedReceiveChannelException) {
                        Log.w(TAG, "Channel closed, will retry")
                        delay(1000)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in listening loop", e)
                        delay(1000)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Listening cancelled")
            }
        }
    }

    /**
     * 处理音频数据
     */
    private suspend fun processAudio(audioData: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                val result = OpenAIClient.transcribeAndTranslate(
                    audioData,
                    MainActivity.sourceLanguage,
                    MainActivity.targetLanguage
                )

                withContext(Dispatchers.Main) {
                    val originalText = result.first
                    val translatedText = result.second

                    if (!originalText.isNullOrBlank()) {
                        updateSubtitle(originalText, translatedText)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing audio", e)
            }
        }
    }

    /**
     * 更新字幕显示
     */
    private fun updateSubtitle(original: String?, translated: String?) {
        // 显示译文（主要）
        if (!translated.isNullOrBlank()) {
            tvTranslated?.text = translated
            tvTranslated?.visibility = View.VISIBLE
        } else {
            tvTranslated?.visibility = View.GONE
        }

        // 显示原文（可选，默认隐藏）
        if (!original.isNullOrBlank() && original != translated) {
            tvOriginal?.text = original
        }
    }
}
