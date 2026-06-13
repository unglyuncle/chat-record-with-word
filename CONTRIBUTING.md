# 贡献者指南 / Contributing Guide

## 仓库里有什么、没有什么

| 文件 / 目录 | 是否在 Git 中 | 说明 |
|---|:---:|---|
| Kotlin 源码、res 资源、Gradle 配置 | ✅ | 项目核心，约几百 KB |
| `scripts/fetch-sherpa-aar.*` | ✅ | 下载 sherpa-onnx AAR 的脚本 |
| `local.properties.template` | ✅ | API 密钥模板，克隆者参考用 |
| `README.md` / `USER_MANUAL.md` | ✅ | 项目说明和用户手册 |
| `app/libs/sherpa-onnx-*.aar` | ❌ | 原生库，几十 MB，通过脚本下载 |
| ASR 离线模型（ONNX） | ❌ | 几百 MB，App 内下载 |
| `local.properties` | ❌ | 含本机 SDK 路径，机器相关 |
| `build/` `.gradle/` `.idea/` | ❌ | 构建产物 / IDE 缓存 |

### 为什么不把 AAR 和模型也传上去？

- **sherpa-onnx AAR**：约几十 MB，放在 Git 会让仓库膨胀，且版本更新时需要手动替换。用脚本按需下载更合理。
- **ASR 模型**：SenseVoice 约 232MB、Paraformer 约 500MB，远超 GitHub 单文件 100MB 限制，且不同用户可能只需要其中一种方案。App 内已实现断点下载逻辑。
- **local.properties**：`sdk.dir` 路径因机器而异（如 `C:\Users\xxx\AppData\Local\Android\Sdk`），Android Studio 打开项目时会自动生成。

---

## 克隆后快速开始

```bash
# 1. 克隆
git clone https://github.com/unglyuncle/chat-record-with-word.git
cd chat-record-with-word

# 2. 下载 sherpa-onnx 原生库（必须，否则 Gradle sync 报错）
# Windows:
powershell -ExecutionPolicy Bypass -File scripts/fetch-sherpa-aar.ps1
# macOS / Linux:
bash scripts/fetch-sherpa-aar.sh

# 3. Android Studio 打开本目录 → 等待 Gradle Sync → 连真机运行
```

### 离线模型下载

首次使用离线引擎时，进入 **设置 → 离线模型管理**，点「下载」即可（Wi-Fi 环境推荐）。

| 方案 | 所需模型 | 大小 |
|---|---|---|
| SenseVoice（多语言 + 情绪） | SenseVoice-Small + Silero VAD | ~232MB |
| Paraformer（中文更强） | Paraformer-Large + Silero VAD + 中文标点 | ~500MB |
| 说话人分离 | pyannote 分段 + CAM++ 声纹 | ~34MB |

### 在线引擎密钥（可选）

仅讯飞 / 阿里 / 火山等在线引擎需要，离线引擎不需要任何密钥。

两种填法：
- **运行时**：App 设置页直接填写。
- **编译期**：复制 `local.properties.template` 到 `local.properties`，填入对应字段，重新构建。

---

## 常见问题

### Gradle Sync 报错：Could not find sherpa-onnx

**原因**：未下载 AAR 文件。

**解决**：运行 `scripts/fetch-sherpa-aar.ps1`（Windows）或 `scripts/fetch-sherpa-aar.sh`（macOS/Linux）。

### local.properties 能不能上传？

**不能，也不需要。** 原因：
1. `sdk.dir` 是你机器独有的路径，别人电脑上不同。
2. Android Studio 打开项目时会自动检测并生成。
3. API 密钥是可选的，可在运行时填写。

### 为什么 .gitignore 排除了 app/libs/*.aar？

AAR 文件是二进制产物，不适合放在 Git 中。通过 `scripts/fetch-sherpa-aar.*` 脚本按需下载，保证仓库轻量。
