package com.livesubtitle

import android.app.Application
import android.content.Intent

/**
 * Application 类 - 全局状态管理
 * 解决服务间共享 MediaProjection 数据的问题
 */
class LiveSubtitleApp : Application() {

    companion object {
        // MediaProjection 数据（跨服务共享）
        private var _mediaProjectionResultCode: Int = 0
        private var _mediaProjectionData: Intent? = null
        private var _isProjectionActive: Boolean = false

        @Volatile
        var mediaProjectionResultCode: Int
            get() = _mediaProjectionResultCode
            private set(value) {
                _mediaProjectionResultCode = value
            }

        @Volatile
        var mediaProjectionData: Intent?
            get() = _mediaProjectionData
            private set(value) {
                _mediaProjectionData = value
            }

        @Volatile
        var isProjectionActive: Boolean
            get() = _isProjectionActive
            private set(value) {
                _isProjectionActive = value
            }

        /**
         * 设置 MediaProjection 数据（从 Activity 调用）
         */
        fun setMediaProjection(resultCode: Int, data: Intent?) {
            mediaProjectionResultCode = resultCode
            mediaProjectionData = data
            isProjectionActive = resultCode == android.app.Activity.RESULT_OK && data != null
        }

        /**
         * 清除 MediaProjection 数据（从服务调用）
         */
        fun clearMediaProjection() {
            mediaProjectionData = null
            isProjectionActive = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: LiveSubtitleApp
            private set
    }
}
