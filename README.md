# Shadowing Trainer for Android

一个**本地优先**的英语 Shadowing（跟读）训练应用，使用 **Jetpack Compose + Room + Moonshine ASR** 构建，完整闭环在 Android 端完成：

> 导入素材 → 自动/手动生成句子 → 逐句播放 → 录音 → 本地转写 → 文本比对评分 → 保存历史结果

---

## 1. 项目现状（与代码一致）

当前仓库已经是 Android 单端工程，不再依赖原来的 Web + Python 后端架构。

### 已实现核心能力

- 素材列表：展示、重命名、删除素材。
- 三种导入方式：
  - 导入素材包目录（`meta.json` + `sentences.json` + 可选媒体/clip）
  - 导入单个音/视频文件（调用 Moonshine 自动转写并切句）
  - 一键导入 Demo 素材
- 句子列表：展示每句文本与最近一次成绩。
- 训练页：逐句播放、变速、循环、上下句切换。
- 录音评估：麦克风录音（WAV）→ Moonshine 本地转写 → 词级比对评分。
- 结果存储：保存练习记录并回填句级 latest result。
- 视频播放：当素材为视频时，训练页使用 ExoPlayer 视频面板播放。

---

## 2. 技术栈

- **UI**: Jetpack Compose + Material 3
- **架构**: ViewModel + StateFlow + Repository + UseCase
- **依赖注入**: Hilt
- **本地数据库**: Room
- **播放**: Media3 ExoPlayer
- **录音**: AudioRecord（16kHz, mono, PCM16 WAV）
- **ASR**: Moonshine（JNI + C++ + ONNX Runtime，本地推理）
- **最低系统**: Android 8.0 (API 26)
- **构建**: AGP 8.7.3, Kotlin 2.1.0, Java 17, NDK + CMake

---

## 3. 页面与流程

### 3.1 MaterialList（素材列表）

入口页支持：
- 查看所有素材
- 点击进入句子列表
- 底部弹窗执行导入（目录 / 媒体文件 / Demo）
- 素材重命名、删除

### 3.2 SentenceList（句子列表）

- 展示句子原文、可选注释（`textZh`）
- 展示每句最近一次识别文本与分数
- 点击进入训练页

### 3.3 Training（训练页）

- 目标句展示
- 音频/视频播放，支持 `0.5x / 0.75x / 1.0x / 1.25x / 1.5x`
- 单句循环播放
- 录音开始/停止/取消
- 本地 ASR 转写
- 对比结果展示：得分、漏词、增词、错词、简评
- 上一句/下一句导航

---

## 4. 数据模型（Room）

### `materials`

- `id` 主键
- `title`
- `type`（`audio` / `video`）
- `sourcePath`（整段媒体）
- `coverPath`（暂未实际使用）
- `language`
- `createdAt`, `updatedAt`

### `sentences`

- `id` 主键
- `materialId`（FK）
- `index`
- `textOriginal`
- `textZh`（可空）
- `startTimeMs`, `endTimeMs`（可空）
- `clipPath`（可空）
- `createdAt`

### `practice_records`

- `id` 主键
- `materialId`, `sentenceId`（FK）
- `recordingPath`
- `recognizedText`
- `matchScore`
- `errorTags`（JSON）
- `createdAt`

### `sentence_latest_results`

- `sentenceId` 主键（FK）
- `latestPracticeRecordId`
- `latestRecognizedText`
- `latestScore`
- `updatedAt`

---

## 5. 导入格式说明

### 5.1 素材包目录导入

目录至少包含：

```text
my_material/
├── meta.json
└── sentences.json
```

可选包含：
- 根目录媒体文件（如 `source.mp3` / `source.mp4`）
- 句级 clip 文件（在 `sentences.json` 中通过 `clipFile` 相对路径引用）

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

### 5.2 单媒体文件导入

支持选择音频或视频文件。

流程：
1. 复制到缓存目录
2. 调用 Moonshine 转写
3. 按转写行生成 `sentences.json`
4. 复用素材包导入逻辑落库

---

## 6. 播放策略

训练页按以下优先级选择播放源：

1. 如果素材是视频：优先使用视频源（clip 为视频则优先 clip）
2. 否则如果句子有 `clipPath`：直接播 clip
3. 否则播放素材 `sourcePath`，若有时间戳则按片段播放
4. 无可用媒体则提示不可播放

---

## 7. 文本比对策略（当前评分）

`TextCompareUseCase` 使用轻量词级编辑距离对齐（Match/Replace/Delete/Insert）：

- `matchScore = matchedWords / targetWords`
- 输出：
  - `missedWords`
  - `extraWords`
  - `wrongWords`
  - `simpleFeedback`

这是第一阶段“可用优先”的实现，不做音素级/声学级评分。

---

## 8. 本地存储约定

应用私有目录：

```text
files/shadowing_data/
└── materials/{materialId}/
    ├── <source media>
    ├── clips/... (如果素材包提供)
    └── recordings/
        └── rec_{sentenceId}_{timestamp}.wav
```

数据库文件：`shadowing.db`。

---

## 9. Moonshine 模型与原生层

- JNI 库：`shadowing-moonshine`（`app/src/main/cpp`）
- Moonshine C++ core 已放在 `app/src/main/cpp/moonshine-core`
- 项目自带模型资源：`app/src/main/assets/moonshine/medium-streaming-en`
- `MoonshineAsr` 启动时会优先尝试从 assets 提取并加载模型到 `files/moonshine/...`

如果你需要重新准备环境，可参考：
- `docs/MOONSHINE_SETUP.md`
- `scripts/setup_moonshine.sh`

---

## 10. 开发与运行

### 环境要求

- Android Studio（建议新版本）
- Android SDK（compile/target 35）
- NDK `27.0.12077973`
- CMake `3.22.1`
- JDK 17

### 启动步骤

```bash
# 1) 打开工程
# Android Studio 打开本仓库根目录

# 2) 同步依赖
# Sync Project with Gradle Files

# 3) 构建并运行
# 运行 app 模块到 arm64-v8a 设备/模拟器
```

> 注意：`abiFilters` 当前只包含 `arm64-v8a`，请使用 arm64 设备或对应模拟器。

---

## 11. 当前限制与后续方向

### 当前限制

- 文本比对仅词级，不含发音韵律评估
- 暂无设置页（DataStore 依赖已引入，但功能未落地）
- 导入媒体时默认语言 `en`
- `READ_MEDIA_VIDEO` 未声明，视频导入能力可能受系统版本/厂商行为影响（当前主要声明了音频读取权限）

### 后续建议

- 增加设置中心（播放速度默认值、循环策略、模型切换）
- 引入更细粒度评分（发音、停顿、语速）
- 补齐历史练习记录页与可视化趋势
- 完善视频权限与导入兼容性

---

## 12. 目录结构速览

```text
app/src/main/java/com/orien/shadowing/
├── data/
│   ├── local/          # 播放、录音、ASR、DAO、Repository
│   └── model/          # Room 实体
├── domain/usecase/     # 导入、Demo 生成、文本比对
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
