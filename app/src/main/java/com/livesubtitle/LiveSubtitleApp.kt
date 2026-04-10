package com.livesubtitle

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.util.Log

/**
 * Application 类 - 全局状态管理
 * 解决服务间共享 MediaProjection 数据的问题
 */
class LiveSubtitleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: LiveSubtitleApp
            private set

        // MediaProjection 数据（跨服务共享）
        @Volatile
        private var _mediaProjectionResultCode: Int = 0

        @Volatile
        private var _mediaProjectionData: Intent? = null

        @Volatile
        private var _isProjectionActive: Boolean = false

        @Synchronized
        fun setMediaProjection(resultCode: Int, data: Intent?) {
            _mediaProjectionResultCode = resultCode
            _mediaProjectionData = data
            _isProjectionActive = resultCode == Activity.RESULT_OK && data != null
            Log.d("LiveSubtitleApp", "MediaProjection set: active=$_isProjectionActive")
        }

        @Synchronized
        fun clearMediaProjection() {
            _mediaProjectionData = null
            _isProjectionActive = false
            Log.d("LiveSubtitleApp", "MediaProjection cleared")
        }

        val mediaProjectionResultCode: Int
            @Synchronized get() = _mediaProjectionResultCode

        val mediaProjectionData: Intent?
            @Synchronized get() = _mediaProjectionData

        val isProjectionActive: Boolean
            @Synchronized get() = _isProjectionActive
    }
}
