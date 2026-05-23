# Shadowing Trainer for Android

一个本地优先的英语 Shadowing 训练应用。素材导入、播放、录音、转写、比对和结果保存都在设备端完成。

## 项目现状

# 当前项目暂时停止更新，目前优先修改完善原型设计（详见项目简介about部分的连接仓库）

当前代码已经实现的主流程：

- 素材列表
  - 展示所有素材
  - 重命名、删除素材
  - 显示导入队列和失败任务
- 三种导入方式
  - 导入素材包目录：读取 `meta.json` 和 `sentences.json`
  - 导入单个音频/视频文件：先用 Moonshine 在本地转写，再自动生成素材包并导入
  - 一键导入 Demo 素材：生成 3 组示例课程
- 句子列表
  - 展示原文和可选注释 `textZh`
  - 展示每句最近一次识别文本和分数
- 训练页
  - 逐句播放音频或视频
  - 支持变速播放：`0.5x / 0.75x / 1.0x / 1.25x / 1.5x`
  - 支持单句循环
  - 支持上一句 / 下一句跳转
  - 麦克风录音
  - 对当前录音做本地 ASR 转写
  - 基于文本对齐输出漏词、增词、替换词和简短反馈
  - 基于内置 G2P 字典输出单词级 IPA 和发音提示
  - 支持回放本次练习录音
- 本地持久化
  - Room 保存素材、句子、练习记录和句子最新结果
  - 素材文件持久化到应用私有目录

## 目前使用的技术栈

- UI：Jetpack Compose + Material 3
- 状态管理：ViewModel + StateFlow
- 依赖注入：Hilt
- 数据库：Room
- 媒体播放：`MediaPlayer`
- 录音：`AudioRecord`，16kHz / mono / PCM16 WAV
- ASR：Moonshine（JNI + C++ + ONNX Runtime，本地推理）
- 发音提示：内置英文 G2P 字典 `app/src/main/assets/dictionary/base_g2p_dict.json`
- 构建：AGP `8.7.3`，Kotlin `2.1.0`，Java `17`

## 与旧 README 不一致的地方

这几个点已经按当前代码修正：

- 播放器不是 ExoPlayer，当前实现是 `MediaPlayer`
- 默认模型不是 `tiny-en`，仓库实际内置的是 `app/src/main/assets/moonshine/medium-streaming-en`
- 训练页不只是文本比对，还包含基于 G2P 的单词级发音提示
- 仓库当前没有提交 `gradlew` / `gradlew.bat`

## 快速开始

### 环境要求

- Android Studio 新版本
- Android SDK 35
- JDK 17
- NDK `27.0.12077973`
- CMake `3.22.1`
- 真机或模拟器建议使用 `arm64-v8a`

### 构建与运行

推荐直接用 Android Studio：

1. 打开仓库根目录
2. Sync Gradle
3. 运行 `app` 模块

注意：

- 当前 `minSdk = 26`，`targetSdk = 35`
- `abiFilters` 只包含 `arm64-v8a`
- 仓库未提交 `gradlew` / `gradlew.bat`，如果要命令行构建，需要本机自行安装 Gradle
- 当前仓库已经带有 Moonshine C++ 核心和 `medium-streaming-en` 模型资源，通常不需要额外下载模型

## 导入方式

### 1. 导入素材包目录

素材包最少需要：

```text
my_material/
|- meta.json
|- sentences.json
```

可选内容：

- 根目录媒体文件，例如 `source.mp3`、`source.mp4`
- 句子级 clip 文件，例如 `clips/s0.wav`
- 兼容回退音频 `fallback_audio.wav`
- 原始媒体自动切句时生成的 `disputed_sentences.json`

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

字段说明：

- `textOriginal`：训练目标文本
- `textZh`：可选备注或中文释义
- `startTimeMs` / `endTimeMs`：在整段媒体中的时间片段
- `clipFile`：可选，指向句子级媒体片段

### 2. 导入单个音频/视频文件

应用会通过系统文件选择器读取媒体，然后执行：

1. 复制到应用缓存目录
2. 初始化 Moonshine 模型
3. 本地转写并按时间切分句子
4. 自动生成 `meta.json` / `sentences.json`
5. 复用素材包导入流程完成入库

说明：

- 导入原始媒体时，语言当前固定写入为 `en`
- 导入队列最多并发处理 2 个任务
- 导入通过 SAF 完成，应用会保存读取授权

### 3. 导入 Demo 素材

会自动生成 3 组演示课：

- `Lesson 1 - Greetings`
- `Lesson 2 - Daily Routine`
- `Lesson 3 - Meeting Phrases`

## 播放与兼容策略

训练页会按下面的优先级选择播放源：

1. 如果素材主媒体是视频，优先播放视频源
2. 否则如果句子有 `clipPath`，优先播放句子级 clip
3. 否则播放素材主媒体，并按 `startTimeMs` / `endTimeMs` 做片段播放

为了兼容不同设备的媒体解码能力，导入视频时还会按需生成：

- `compat_video.mp4`：更兼容的 MP4 版本
- `fallback_audio.wav`：播放失败时的音频回退文件

如果设备无法直接播放某些视频格式，训练页会自动退回到兼容音频播放。

## 评分与反馈

当前评分不是声学级口语测评，而是两层轻量反馈：

1. 文本层
   - 词级编辑距离对齐
   - 生成 `matchScore`
   - 输出 `missedWords`、`extraWords`、`wrongWords`
2. 发音提示层
   - 基于英文 G2P 字典把目标词和识别词映射到音素
   - 生成 IPA 展示
   - 给出单词级“需要改进 / 错误或漏读”等提示

这意味着它更适合做 Shadowing 练习反馈，不等同于标准化口语评分引擎。

## 本地存储

持久化素材目录：

```text
files/shadowing_data/
|- materials/{materialId}/
   |- <source media>
   |- compat_video.mp4
   |- fallback_audio.wav
   |- clips/...
```

数据库文件：

- `shadowing.db`

训练录音文件：

- 临时保存在应用缓存目录 `cache/training_attempt_recordings/`
- 当前评分后不会长期保存原始录音文件，只保存识别文本、分数和错误标签

## 数据模型

### `materials`

- `id`
- `title`
- `type`
- `sourcePath`
- `fallbackAudioPath`
- `coverPath`
- `language`
- `createdAt`
- `updatedAt`

### `sentences`

- `id`
- `materialId`
- `index`
- `textOriginal`
- `textZh`
- `startTimeMs`
- `endTimeMs`
- `clipPath`
- `createdAt`

### `practice_records`

- `id`
- `materialId`
- `sentenceId`
- `recordingPath`
- `recognizedText`
- `matchScore`
- `errorTags`
- `createdAt`

### `sentence_latest_results`

- `sentenceId`
- `latestPracticeRecordId`
- `latestRecognizedText`
- `latestScore`
- `updatedAt`

## 目录结构

```text
app/src/main/java/com/orien/shadowing/
|- data/
|  |- local/
|  |- model/
|- domain/usecase/
|- presentation/
|  |- materiallist/
|  |- sentencelist/
|  |- training/
|  |- navigation/
|- di/
|- MainActivity.kt

app/src/main/assets/
|- dictionary/
|- moonshine/

app/src/main/cpp/
|- Moonshine JNI 和 C++ 核心

docs/
|- 补充文档

scripts/
|- 环境初始化脚本
```

## 当前限制

- 目前主流程围绕英文素材设计，原始媒体导入默认语言也是 `en`
- 评分核心仍然是文本对齐加启发式音素提示，不是完整的声学评分
- 当前没有练习历史页面，只保存句子级最近结果
- `practice_records.recordingPath` 字段当前没有实际持久化录音文件
- 仓库未提交 Gradle Wrapper 脚本，命令行构建体验不完整

## 相关文件

- `docs/MOONSHINE_SETUP.md`
- `scripts/setup_moonshine.sh`

如果你现在要继续补功能，最值得优先做的通常是：

- 补练习历史页和结果统计
- 把设置页落地（默认语速、循环策略、模型选择）
- 明确是否要长期保存用户录音
- 如果要提高评分可信度，再引入更细粒度的声学特征评估
