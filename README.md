# Shadowing Trainer for Android

一个 **本地优先** 的英语 Shadowing（跟读）训练应用。项目基于 **Jetpack Compose + Room + Moonshine ASR（JNI）**，核心流程全部在 Android 端完成：

> 导入素材（文件夹/音视频/Demo）→ 句子训练 → 本地录音 → 本地转写 → 词级对比评分 → 保存历史结果

---

## 1. 项目概览

当前仓库是单一 Android 工程（无 Web/Python 运行时依赖）。

### 1.1 已实现能力

- 素材管理
  - 素材列表展示
  - 素材重命名、删除
- 三种导入方式
  - 导入素材包目录（`meta.json` + `sentences.json` + 可选媒体/clip）
  - 导入单个音频/视频（调用 Moonshine 自动切句）
  - 一键导入 Demo 素材
- 训练流程
  - 逐句播放
  - 速度切换（0.5x~1.5x）
  - 单句循环
  - 上下句切换
- 录音评估
  - 麦克风录音（WAV）
  - Moonshine 本地转写
  - 词级比对评分（命中/漏词/增词/错词）
- 历史结果
  - 保存练习记录
  - 句子列表显示最近一次得分与识别文本

### 1.2 当前架构特征

- 使用 `MaterialImportQueue` 做导入队列管理，支持并发导入（默认最多 2 个任务）。
- 导入媒体后会生成标准素材包，再复用目录导入流程落库。
- 媒体播放基于 Android `MediaPlayer`（非 Media3 ExoPlayer）。

---

## 2. 技术栈

- **UI**：Jetpack Compose + Material 3
- **架构**：ViewModel + StateFlow + Repository + UseCase
- **DI**：Hilt
- **数据库**：Room
- **媒体播放**：MediaPlayer（含视频 Surface 绑定）
- **录音**：AudioRecord（16kHz / mono / PCM16 WAV）
- **ASR**：Moonshine（JNI + C++ + ONNX Runtime，本地推理）
- **构建**：AGP 8.7.3、Kotlin 2.1.0、Java 17、NDK + CMake
- **系统要求**：minSdk 26，target/compileSdk 35

---

## 3. 页面与主要流程

### 3.1 MaterialList（素材列表）

- 查看全部素材
- 点击素材进入句子列表
- 底部菜单发起导入（文件夹 / 媒体 / Demo）
- 素材重命名、删除
- 可查看导入任务进度与失败状态

### 3.2 SentenceList（句子列表）

- 展示句子原文与可选中文注释（`textZh`）
- 展示每句最近一次识别文本与分数
- 点击进入训练页

### 3.3 Training（训练页）

- 展示目标句
- 播放音频/视频并支持变速与循环
- 支持开始/停止/取消录音
- 本地 ASR 转写
- 展示词级对比结果（分数、漏词、增词、错词、简评）
- 上一句/下一句导航

---

## 4. 数据模型（Room）

### `materials`

- `id`（PK）
- `title`
- `type`（`audio` / `video`）
- `sourcePath`
- `fallbackAudioPath`（视频回退音轨）
- `coverPath`（预留）
- `language`
- `createdAt`, `updatedAt`

### `sentences`

- `id`（PK）
- `materialId`（FK）
- `index`
- `textOriginal`
- `textZh`（可空）
- `startTimeMs`, `endTimeMs`（可空）
- `clipPath`（可空）
- `createdAt`

### `practice_records`

- `id`（PK）
- `materialId`, `sentenceId`（FK）
- `recordingPath`
- `recognizedText`
- `matchScore`
- `errorTags`（JSON）
- `createdAt`

### `sentence_latest_results`

- `sentenceId`（PK, FK）
- `latestPracticeRecordId`
- `latestRecognizedText`
- `latestScore`
- `updatedAt`

---

## 5. 导入格式

### 5.1 素材包目录

最小结构：

```text
my_material/
├── meta.json
└── sentences.json
```

可选内容：
- 根目录媒体文件（如 `source.mp3` / `source.mp4`）
- `clips/` 下句级片段（由 `sentences.json` 的 `clipFile` 引用）
- `fallback_audio.wav`（播放回退音轨）
- `disputed_sentences.json`（可选争议切句信息）

`meta.json` 示例：

```json
{
  "title": "Daily English - Episode 1",
  "type": "audio",
  "language": "en"
}
```

`sentences.json` 示例：

```json
[
  {
    "index": 0,
    "textOriginal": "Hello, how are you today?",
    "textZh": "问候",
    "startTimeMs": 0,
    "endTimeMs": 3200,
    "clipFile": "clips/s0.wav"
  }
]
```

### 5.2 单媒体导入

流程：
1. 校验媒体类型并复制到缓存目录
2. 初始化 Moonshine 模型
3. 本地转写并切句（含争议句信息）
4. 生成 `meta.json` + `sentences.json`
5. 复用目录导入逻辑落库

---

## 6. 播放策略

训练页按以下优先级选择播放源：

1. 若句子有 `clipPath`，优先播放 clip
2. 否则播放素材 `sourcePath`
3. 若主媒体不可播且存在 `fallbackAudioPath`，尝试回退音轨
4. 若有时间戳，进行片段播放（支持循环）
5. 无可用媒体则提示不可播放

---

## 7. 文本比对与评分

`TextCompareUseCase` 使用词级编辑距离对齐（Match / Replace / Delete / Insert）：

- `matchScore = matchedWords / targetWords`
- 输出字段：
  - `missedWords`
  - `extraWords`
  - `wrongWords`
  - `simpleFeedback`

当前策略目标是“先可用”，暂不包含音素级或声学级评分。

---

## 8. 本地存储约定

应用私有目录：

```text
files/shadowing_data/
└── materials/{materialId}/
    ├── <source media>
    ├── fallback_audio.wav (可选)
    ├── clips/... (可选)
    ├── disputed_sentences.json (可选)
    └── recordings/
        └── rec_{sentenceId}_{timestamp}.wav
```

数据库文件：`shadowing.db`。

---

## 9. Moonshine 与原生层

- JNI 库：`shadowing-moonshine`（`app/src/main/cpp`）
- Moonshine C++ core：`app/src/main/cpp/moonshine-core`
- 内置模型：`app/src/main/assets/moonshine/medium-streaming-en`
- 启动时会尝试将模型从 assets 提取到 `files/moonshine/...` 并加载

环境准备可参考：
- `docs/MOONSHINE_SETUP.md`
- `scripts/setup_moonshine.sh`

---

## 10. 开发与运行

### 10.1 环境要求

- Android Studio（建议新版本）
- Android SDK（compile/target 35）
- NDK `27.0.12077973`
- CMake `3.22.1`
- JDK 17

### 10.2 启动步骤

```bash
# 1) Android Studio 打开仓库根目录
# 2) Sync Gradle
# 3) 运行 app 模块到 arm64-v8a 设备/模拟器
```

> 当前 `abiFilters` 仅包含 `arm64-v8a`。

---

## 11. 权限与已知限制

### 11.1 权限

- `RECORD_AUDIO`
- `READ_MEDIA_AUDIO`
- `READ_EXTERNAL_STORAGE`（`maxSdkVersion=32`）

### 11.2 限制

- 文本比对仅词级，不含发音韵律评分
- 设置页（DataStore）尚未完整落地
- 单媒体导入默认语言为 `en`
- 未声明 `READ_MEDIA_VIDEO`，视频导入在部分设备上可能受限

---

## 12. 目录结构速览

```text
app/src/main/java/com/orien/shadowing/
├── data/
│   ├── local/          # 播放、录音、ASR、DAO、Repository
│   └── model/          # Room 实体
├── domain/usecase/     # 导入、队列、文本比对、Demo 生成
├── presentation/
│   ├── materiallist/
│   ├── sentencelist/
│   ├── training/
│   └── navigation/
├── di/                 # Hilt Module
└── MainActivity.kt

app/src/main/cpp/       # Moonshine JNI + C++ core
app/src/main/assets/    # Moonshine 模型资源
docs/                   # 补充文档
scripts/                # 环境脚本
```
