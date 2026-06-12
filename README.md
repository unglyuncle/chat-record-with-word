# 会议录音转写 App (MeetingTranscriber)

Android 会议录音 + 实时转写 + 说话人分离，四引擎可切换。Kotlin + Jetpack Compose。

> 本工程由我（Claude）在没有 Android SDK 的机器上生成，**未经过编译验证**。请在 Android Studio 中打开、Gradle 同步并按下面步骤补齐一个二进制依赖后构建。文末「已知限制 / 待验证点」列出了需要你关注的地方。

---

## 1. 能做什么

- **前台服务持续录音**，支持 1 小时以上的长会议（`RecordingService` + `PARTIAL_WAKE_LOCK`）。
- **边录边出文字**，显示在可滚动列表里；用户手动上滑时**暂停自动滚动**，回到底部或点「↓」按钮**恢复跟随**。
- **说话人分离**：标注「说话人 1 / 2 …」。
- **四引擎自由切换**，统一 `ASREngine` 接口，UI 只依赖接口。

### 引擎能力矩阵

| 引擎 | 联网 | 实时 | 说话人分离 | 情绪 | 备注 |
|---|---|---|---|---|---|
| **离线 sherpa-onnx**（默认） | 否 | VAD 伪流式 | ✅ 边录占位 + 录后校正 | SenseVoice 自带 | 推荐首选 |
| 讯飞 RTASR | 是 | ✅ | ✅ 服务端(rl) | — | 最完整的在线引擎 |
| 阿里 Fun-ASR | 是 | ✅ | ❌（实时接口不支持） | — | 见待验证点 |
| 火山豆包 ASR | 是 | ✅ | ⚠️ 字段待确认 | ⚠️ | 二进制协议骨架 |

离线引擎可在设置里切换 **SenseVoice-Small**（多语言 + 情绪 + 标点）与 **Paraformer-Large**（中文更强，自动附带 CT-Transformer 标点）。

---

## 2. 环境要求

- Android Studio（近一年版本均可，自带 Gradle 8.10.2 + Android SDK）
- JDK 17（Android Studio 内置；本工程 `compileSdk 35 / minSdk 24`）
- 一台 **arm64** 真机（推荐）。离线引擎建议 4GB+ 内存。

---

## 3. 构建步骤

### 3.1 放入 sherpa-onnx 原生库（必须，否则离线引擎无法编译）

sherpa-onnx **没有官方 Maven 坐标**，需手动放入预编译 `.aar`。任选其一：

**方式 A：用脚本下载（最简单）**
```bash
# Windows PowerShell
powershell -ExecutionPolicy Bypass -File scripts/fetch-sherpa-aar.ps1
# macOS / Linux / Git-Bash
bash scripts/fetch-sherpa-aar.sh
```
脚本会把 `sherpa-onnx-1.13.2.aar` 下载到 `app/libs/`。`app/build.gradle.kts` 已用 `fileTree("libs")` 自动引入该目录下的 aar。

**方式 B：手动**
1. 打开 <https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.2>
2. 下载 `sherpa-onnx-1.13.2.aar`，放到 `app/libs/`。
3. 若该 release 未直接附带 aar，则下载 `sherpa-onnx-v1.13.2-android.tar.bz2`，解压后把 `jniLibs/`（至少 `arm64-v8a`）拷到 `app/src/main/jniLibs/`，并把 `sherpa-onnx/kotlin-api/*.kt` 拷到本工程 `com/k2fsa/sherpa/onnx/` 包下（二选一即可，不要同时用）。

### 3.2 同步并运行

1. Android Studio → Open → 选择本目录。
2. 等待 Gradle 同步。
3. 连真机，Run。

### 3.3 首次使用：下载离线模型

进入 **设置 → 离线模型管理**，点「下载」获取所需模型（应用内通过 GitHub release 下载到 `filesDir/models`，自动解压 `.tar.bz2`）：

| 用途 | 组件 | 大小 |
|---|---|---|
| SenseVoice 方案 | SenseVoice-Small + Silero VAD | ~232MB |
| Paraformer 方案 | Paraformer-Large + Silero VAD + 中文标点 | ~500MB |
| 说话人分离 | 说话人分段(pyannote) + CAM++ 声纹 | ~34MB |

> 模型较大，建议 Wi-Fi 下载。下载是断点不续传的：失败会清理残留，重试即可。

### 3.4 在线引擎密钥

两种填法（任选）：
- **运行时**：设置页对应引擎下填入。
- **编译期**：复制 `local.properties.template` 的字段到 `local.properties`（不会进版本库），重新构建即可作为默认值。

---

## 4. 架构

```
audio/RecordingService ──PCM──▶ EngineController ──▶ 活动 ASREngine
        (前台服务/麦克风)            (单例, 持有引擎,        │ (offline / 讯飞 / 阿里 / 火山)
                                     发布 StateFlow)        ▼
UI (Compose) ◀── segments/partial/state/errors ◀── ASRListener 回调
```

- `asr/ASREngine`：统一接口 `setListener / init / startStreaming / feedAudio / stop / release`。
- `asr/EngineController`：进程级单例，切换引擎时 `stop()→release()→init()`，向 UI 暴露 `StateFlow`。
- `asr/offline/SherpaOfflineEngine`：silero VAD 切句 → 离线识别（SenseVoice/Paraformer）→ 实时声纹聚类占位 → 停止后整段 `OfflineSpeakerDiarization` 校正说话人。
- `asr/online/*`：三个在线引擎，均走 OkHttp WebSocket（不依赖厂商 AAR，保证可编译）。

详见各文件顶部注释。

---

## 5. 说话人分离（离线）如何工作

1. **录制中（占位）**：每段 VAD 语音用 CAM++ 声纹做在线贪心聚类，立即给出「说话人 N」。快但粗。
2. **停止后（校正）**：对整段录音跑 pyannote 分段 + CAM++ + 聚类，按时间重叠把权威说话人标签覆盖回每句（`onSpeakersCorrected`）。

阈值可在设置里调（越低分出的人越多）。

---

## 6. 已知限制 / 待验证点

**需要你留意（研究阶段已明确标注的不确定项）：**

1. **sherpa-onnx Kotlin API 名称**：类/字段名按 v1.13.2 源码填写，但本机无法编译验证。放入 aar 后若个别构造参数名（如 `assetManager`、`OnlineStream.inputFinished()`）对不上，按编译器提示微调即可——逻辑结构是对的。
2. **讯飞角色分离开关参数**：`rl` 字段解析已实现且正确；但「开启角色分离」的 query 参数因产品（标准版 / 大模型）而异，官方文档未统一，代码里 `roleType=2` 仅为占位，请按你开通的产品核对。
3. **阿里百炼实时不支持说话人分离**：实时 WebSocket 模型无 diarization，离线只能单一说话人。需要分离请用讯飞/火山或离线引擎。
4. **火山 speaker / 情绪字段**：流式接口的说话人/情绪字段名未在官方文档确认，代码按 `utterances[].speaker` 容错解析，存在则用，否则为未知。
5. **长会议内存**：录后整段离线分离需把整段音频读成 FloatArray（~230MB/小时）。已开 `largeHeap` 并对 OOM 兜底（保留实时标注）。超长会议（>~40min）可能跳过全局分离——后续可改成分块 + 跨块声纹对齐。
6. **本机无 Android SDK**：工程未编译验证；`gradle-wrapper.jar` 未包含，Android Studio 会自动补齐（或先跑一次 `gradle wrapper`）。

---

## 7. 目录结构

```
app/src/main/java/com/overmind/meetingscribe/
├── MeetingApp.kt / MainActivity.kt
├── asr/                 接口、控制器、工厂、数据模型
│   ├── offline/         sherpa 离线引擎 + 声纹/分离
│   └── online/          讯飞 / 阿里 / 火山
├── audio/               录音器 + 前台服务 + WAV 读写
├── data/                设置(DataStore) + 模型下载/管理
├── ui/                  Compose 界面（转写 / 设置）+ 主题
└── util/                OkHttp、PCM、Gzip
```
