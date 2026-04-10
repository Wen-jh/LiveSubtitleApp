package com.livesubtitle

import android.app.Activity
import android.app.Application
import android.content.Intent

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

        var mediaProjectionResultCode: Int
            @Volatile get() = _mediaProjectionResultCode

        var mediaProjectionData: Intent?
            @Volatile get() = _mediaProjectionData

        var isProjectionActive: Boolean
            @Volatile get() = _isProjectionActive

        /**
         * 设置 MediaProjection 数据（从 Activity 调用）
         */
        fun setMediaProjection(resultCode: Int, data: Intent?) {
            _mediaProjectionResultCode = resultCode
            _mediaProjectionData = data
            _isProjectionActive = resultCode == Activity.RESULT_OK && data != null
        }

        /**
         * 清除 MediaProjection 数据（从服务调用）
         */
        fun clearMediaProjection() {
            _mediaProjectionData = null
            _isProjectionActive = false
        }
    }
}
