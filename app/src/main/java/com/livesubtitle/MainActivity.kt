package com.livesubtitle

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText
    private lateinit var spinnerSourceLang: Spinner
    private lateinit var spinnerTargetLang: Spinner
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvDebug: TextView

    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionData: Intent? = null

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val REQUEST_OVERLAY_PERMISSION = 1002
        const val REQUEST_AUDIO_PERMISSION = 1003

        var apiKey: String = ""
        var sourceLanguage: String = "japanese"
        var targetLanguage: String = "chinese"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupSpinners()
        loadSavedSettings()
        setupClickListeners()
    }

    private fun initViews() {
        etApiKey = findViewById(R.id.etApiKey)
        spinnerSourceLang = findViewById(R.id.spinnerSourceLang)
        spinnerTargetLang = findViewById(R.id.spinnerTargetLang)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvDebug = findViewById(R.id.tvDebug)
    }

    private fun setupSpinners() {
        val sourceAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.source_languages,
            android.R.layout.simple_spinner_item
        )
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSourceLang.adapter = sourceAdapter

        val targetAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.target_languages,
            android.R.layout.simple_spinner_item
        )
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTargetLang.adapter = targetAdapter
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("LiveSubtitlePrefs", MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))
        spinnerSourceLang.setSelection(prefs.getInt("source_lang", 0))
        spinnerTargetLang.setSelection(prefs.getInt("target_lang", 0))
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("LiveSubtitlePrefs", MODE_PRIVATE)
        prefs.edit().apply {
            putString("api_key", etApiKey.text.toString())
            putInt("source_lang", spinnerSourceLang.selectedItemPosition)
            putInt("target_lang", spinnerTargetLang.selectedItemPosition)
            apply()
        }
    }

    private fun setupClickListeners() {
        btnStart.setOnClickListener {
            if (etApiKey.text.toString().isBlank()) {
                Toast.makeText(this, "请输入 API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveSettings()
            apiKey = etApiKey.text.toString()
            sourceLanguage = getLanguageCode(spinnerSourceLang.selectedItemPosition, true)
            targetLanguage = getLanguageCode(spinnerTargetLang.selectedItemPosition, false)

            checkPermissionsAndStart()
        }

        btnStop.setOnClickListener {
            stopServices()
        }
    }

    private fun getLanguageCode(position: Int, isSource: Boolean): String {
        return if (isSource) {
            when (position) {
                0 -> "japanese"
                1 -> "english"
                2 -> "korean"
                3 -> "french"
                4 -> "german"
                else -> "japanese"
            }
        } else {
            when (position) {
                0 -> "chinese"
                1 -> "english"
                2 -> "japanese"
                else -> "chinese"
            }
        }
    }

    private fun checkPermissionsAndStart() {
        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            return
        }

        // 检查录音权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO_PERMISSION
            )
            return
        }

        // 请求 MediaProjection
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    mediaProjectionResultCode = resultCode
                    mediaProjectionData = data
                    startServices()
                } else {
                    tvStatus.text = "状态: 需要授权屏幕录制"
                }
            }
            REQUEST_OVERLAY_PERMISSION -> {
                if (Settings.canDrawOverlays(this)) {
                    checkPermissionsAndStart()
                } else {
                    tvStatus.text = "状态: 需要悬浮窗权限"
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkPermissionsAndStart()
            } else {
                tvStatus.text = "状态: 需要录音权限"
            }
        }
    }

    private fun startServices() {
        // 启动悬浮窗服务
        val floatingIntent = Intent(this, FloatingSubtitleService::class.java)
        startService(floatingIntent)

        // 启动音频捕获服务
        val audioIntent = Intent(this, AudioCaptureService::class.java).apply {
            putExtra("resultCode", mediaProjectionResultCode)
            putExtra("data", mediaProjectionData)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForegroundService(audioIntent)
        } else {
            startService(audioIntent)
        }

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        tvStatus.text = "状态: 正在翻译..."
        
        appendDebug("服务已启动，开始捕获音频...")
    }

    private fun stopServices() {
        stopService(Intent(this, AudioCaptureService::class.java))
        stopService(Intent(this, FloatingSubtitleService::class.java))

        btnStart.isEnabled = true
        btnStop.isEnabled = false
        tvStatus.text = "状态: 已停止"
        
        appendDebug("服务已停止")
    }

    private fun appendDebug(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        tvDebug.append("[$timestamp] $message\n")
        
        // 限制日志行数
        val lines = tvDebug.text.toString().split("\n")
        if (lines.size > 5) {
            tvDebug.text = lines.takeLast(5).joinToString("\n")
        }
    }
}
