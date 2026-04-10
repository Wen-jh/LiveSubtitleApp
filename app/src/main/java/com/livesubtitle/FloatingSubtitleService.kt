package com.livesubtitle

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach

/**
 * 悬浮窗字幕服务
 * 显示实时翻译的字幕
 */
class FloatingSubtitleService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var tvOriginal: TextView? = null
    private var tvTranslated: TextView? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        createOverlay()
        startListening()
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
        hideOverlay()
        serviceScope.cancel()
        isRunning = false
    }

    /**
     * 创建悬浮窗
     */
    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutInflater = LayoutInflater.from(this)
        overlayView = layoutInflater.inflate(R.layout.overlay_subtitle, null)

        tvOriginal = overlayView?.findViewById(R.id.tvOriginal)
        tvTranslated = overlayView?.findViewById(R.id.tvTranslated)

        // 设置悬浮窗参数
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 150  // 距离底部 150px
        }

        // 添加拖动功能
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        overlayView?.setOnTouchListener { _, event ->
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
                    
                    if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                        isDragging = true
                        params.x = initialX + deltaX
                        params.y = initialY - deltaY
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    !isDragging
                }
                else -> false
            }
        }

        overlayView?.let {
            windowManager?.addView(it, params)
        }
    }

    private fun showOverlay() {
        overlayView?.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        overlayView?.let {
            windowManager?.removeViewImmediate(it)
        }
        overlayView = null
    }

    /**
     * 监听音频识别结果
     */
    private fun startListening() {
        serviceScope.launch {
            AudioCaptureService.audioDataChannel.consumeEach { audioData ->
                // 调用 OpenAI API 进行识别和翻译
                processAudio(audioData)
            }
        }
    }

    /**
     * 处理音频数据
     */
    private suspend fun processAudio(audioData: ByteArray) {
        withContext(Dispatchers.IO) {
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
            // 如果需要显示原文，取消注释下一行
            // tvOriginal?.visibility = View.VISIBLE
        }
    }

    /**
     * 清空字幕
     */
    private fun clearSubtitle() {
        tvOriginal?.text = ""
        tvTranslated?.text = ""
        tvOriginal?.visibility = View.GONE
    }
}
