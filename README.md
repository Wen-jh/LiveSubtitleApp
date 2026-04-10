# 🎬 实时字幕翻译 App

Android 实时字幕翻译应用，使用 OpenAI Whisper + GPT-4o-mini 实现语音识别和翻译。

## ✨ 功能特性

- 🎯 **实时语音识别** - 捕获手机内部音频（视频、音乐等）
- 🌍 **多语言翻译** - 日语、英语、韩语等 → 中文
- 📱 **悬浮字幕** - 半透明悬浮窗，可拖动位置
- ⚡ **低延迟** - 优化的音频分片策略，约 3~5 秒延迟

## 🔧 技术方案

| 组件 | 技术 |
|------|------|
| 音频捕获 | AudioPlaybackCapture + MediaProjection API |
| 语音识别 | OpenAI Whisper API |
| 翻译 | OpenAI GPT-4o-mini |
| 字幕显示 | 悬浮窗 (WindowManager) |

## 📋 系统要求

- Android 10 (API 29) 及以上
- 需要以下权限：
  - 录音权限
  - 悬浮窗权限
  - 屏幕录制权限（MediaProjection）

## 🚀 使用步骤

### 1. 获取 OpenAI API Key

访问 [platform.openai.com](https://platform.openai.com) 注册并获取 API Key。

### 2. 编译安装

```bash
# 使用 Android Studio 打开项目
# 或使用命令行编译
./gradlew assembleDebug
```

### 3. 授权并启动

1. 打开 App，输入 OpenAI API Key
2. 选择源语言和目标语言
3. 点击「开始翻译」
4. 授权悬浮窗权限
5. 授权录音权限
6. 授权屏幕录制（选择「立即开始」）
7. 悬浮字幕窗口出现
8. 打开 115网盘 或其他视频 App 播放视频

### 4. 调整字幕位置

长按悬浮字幕可拖动到任意位置。

## 💰 费用估算

| 服务 | 价格 | 每小时成本 |
|------|------|-----------|
| Whisper API | $0.006/分钟 | ~￥2 |
| GPT-4o-mini | $0.15/百万 token | ~￥1 |
| **合计** | | **~￥3~5/小时** |

## 📁 项目结构

```
app/src/main/java/com/livesubtitle/
├── MainActivity.kt           # 主界面，API Key 设置
├── AudioCaptureService.kt    # 音频捕获服务
├── FloatingSubtitleService.kt # 悬浮窗服务
└── OpenAIClient.kt          # OpenAI API 封装
```

## 🔑 核心代码说明

### AudioPlaybackCapture 配置

```kotlin
val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
    .addMatchingContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
    .build()
```

这会捕获所有媒体类音频（视频、音乐），但不包括通知、通话等。

### 音频分片策略

```kotlin
const val SEND_INTERVAL_MS = 3000L  // 每 3 秒发送一次
const val sampleRate = 16000        // Whisper 推荐采样率
```

3 秒分片平衡了延迟和识别准确率。

## ⚠️ 注意事项

1. **隐私** - API Key 存储在本地 SharedPreferences，不会上传
2. **流量** - 音频数据会上传到 OpenAI 服务器
3. **电池** - 长时间运行会消耗较多电量
4. **兼容性** - 部分系统（如 MIUI）可能限制后台录音

## 🔄 替代方案

如果 OpenAI API 访问不稳定，可替换为：

| 方案 | 语音识别 | 翻译 | 延迟 |
|------|---------|------|------|
| 腾讯云 | ASR | 机器翻译 | ~2秒 |
| 百度智能云 | 语音识别 | 翻译 API | ~2秒 |
| 讯飞开放平台 | 语音听写 | 翻译 | ~1秒 |

只需修改 `OpenAIClient.kt` 中的 API 地址即可。

## 📜 License

MIT License
