# Shadowing Trainer — Android 原生重构

## 源仓库

- **GitHub**: https://github.com/Orien233/Shadowing-Trainer
- **当前分支**: v0_3
- **原架构**: React 18 + TypeScript + Vite (前端) / Python 3.10+ + FastAPI + SQLModel + SQLite (后端)
- **原依赖**: faster-whisper, FFmpeg/ffprobe, librosa, soundfile, httpx, DeepSeek API

## 原产品概述

本地优先的英语口语跟读训练 Web 应用。核心流程：上传音频/视频素材 → 标准化为 16kHz WAV → faster-whisper ASR 转写 → 切句 → DeepSeek 翻译 → 句级播放训练 → 用户录音 → 后端评分（完整度/流利度/同步度/发音）→ 评分快照保存。

## 目标架构

**单端本地 Android 原生应用**，不再保留 Web 前后端。

| 层 | 技术选型 |
|----|----------|
| UI | Jetpack Compose |
| 状态管理 | ViewModel + StateFlow |
| 数据库 | Room |
| 文件存储 | Android 私有目录 |
| 播放 | Android 原生 MediaPlayer/ExoPlayer |
| 录音 | Android 原生 MediaRecorder |
| ASR | Moonshine（本地） |
| 业务组织 | Repository / UseCase |
| 设置存储 | DataStore |

## 数据实体设计

### Material
| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long (PK) | |
| title | String | |
| type | String | audio/video |
| sourcePath | String? | 原始文件路径 |
| coverPath | String? | 封面 |
| language | String | |
| createdAt | Long | |
| updatedAt | Long | |

### Sentence
| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long (PK) | |
| materialId | Long (FK) | |
| index | Int | 句子序号 |
| textOriginal | String | 英文原文 |
| textZh | String? | 中文释义（第一阶段可为空） |
| startTimeMs | Long? | 开始时间 |
| endTimeMs | Long? | 结束时间 |
| clipPath | String? | 句子音频片段路径 |
| createdAt | Long | |

### PracticeRecord
| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long (PK) | |
| materialId | Long (FK) | |
| sentenceId | Long (FK) | |
| recordingPath | String | 录音文件路径 |
| recognizedText | String | Moonshine 转写结果 |
| matchScore | Float | 比对得分 |
| errorTags | String? | 错误标记 JSON |
| createdAt | Long | |

### SentenceLatestResult
| 字段 | 类型 | 说明 |
|------|------|------|
| sentenceId | Long (PK) | |
| latestPracticeRecordId | Long | |
| latestRecognizedText | String | |
| latestScore | Float | |
| updatedAt | Long | |

### Settings
| 字段 | 类型 | 说明 |
|------|------|------|
| key | String (PK) | |
| value | String | |

Settings 内容：playbackSpeed, loopCurrentSentence, keepRecordings, moonshineModelPath, appLanguage

## 改动清单（12 项）

### 1. 项目形态
删除 Web 前端 + Python 后端双端结构，改为单一 Android Studio 工程。废弃 frontend/src、backend/app、uvicorn、npm run dev。

### 2. 数据层
SQLModel + SQLite → Room。表：materials, sentences, practice_records, latest_scores, settings。级联删除逻辑改为 Room + 本地文件联动。

### 3. 文件系统
backend/data/materials、audio、audio/sentences、recordings → Android 私有存储目录。按 material/sentence/recording 分层。

### 4. ASR
faster-whisper → Moonshine（安卓端本地集成）。重新设计录音输入、音频预处理、转写调用、结果回填。

### 5. 媒体处理
FFmpeg/ffprobe Python 工具链 → 安卓本地媒体方案。第一阶段采用"预处理材料包"或"手动导入句子与时间戳"，不做自动切句。

### 6. 翻译
DeepSeek API → 移除在线翻译。中文释义通过预置数据导入，或允许为空。

### 7. 评分
后端四维评分（完整度/流利度/同步度/发音）+ VAD → 第一阶段只做"本地转写文本 vs 目标文本"轻量比对。输出简单得分、漏词、错词。

### 8. API
HTTP API（/api/materials 等）→ Android 本地 Repository/UseCase 调用。去 API 化。

### 9. 页面与交互
Web 页面 → Compose 原生 UI。
保留：素材列表、句子列表、逐句播放、上下句切换、循环播放、录音、识别文本、比对结果、历史成绩。
放弃：Web 菜单细节、网页时间轴浮层、后端状态轮询、系统关闭按钮。

### 10. 材料处理策略
"上传后自动触发 processing" → "导入即使用"。材料格式：material 元数据 + sentences 列表 + 可选整段媒体 + 可选每句 clip。

### 11. 视频能力
第一阶段优先音频材料。视频同步作为第二阶段功能。

### 12. 运行方式
Python + Node.js + FFmpeg + PyTorch + uvicorn + npm → 单一 Android APK。

## 第一阶段必须保留的核心能力

1. 素材列表
2. 句子列表
3. 逐句播放
4. 上一句/下一句切换
5. 当前句循环播放
6. 用户录音
7. 本地 Moonshine 转写
8. 目标句与识别句文本比对
9. 保存历史练习记录
10. 查看每句最近一次练习结果

## 第一阶段明确不做

- React 前端迁移
- FastAPI 后端迁移
- Python requirements 迁移
- faster-whisper 迁移
- DeepSeek 翻译
- FFmpeg/ffprobe 自动处理
- 自动 ASR 切句
- 复杂发音/韵律/sync 评分
- latest-evaluations HTTP API
- system/shutdown API
- 后端 processing lock / VAD 裁剪
- Web 风格时间轴和素材菜单 UI

## 材料导入策略

第一阶段支持"预处理材料包导入"，包含：
- material 元数据文件
- sentences 列表文件
- 可选整段音频/视频
- 可选每句 clip 文件

有整段媒体 + 时间戳 → 按时间区间播放
有 clip 文件 → 优先直接播放 clip

## 文本比对策略

轻量文本级比对，不要求音素/声学级分析。
输出：recognizedText, matchScore, missedWords, extraWords, wrongWords, simpleFeedback
方法：字符串归一化 + token 级对齐。

## 迁移优先级

### P0（最小闭环）
本地数据库 → 材料导入 → 句子训练页 → 播放 → 录音 → Moonshine 转写 → 文本比对 → 保存记录

### P1
最近一次成绩回填 → 历史练习记录页 → 设置页 → 材料删除与联动文件清理

### P2
视频材料支持 → 更丰富的句子导航 → 更好的比对可视化 → 更完善的导入格式

## 实现原则

1. 优先跑通完整闭环，不追求复杂自动化
2. 优先保证本地可用，不依赖网络
3. 优先保证英语 shadowing 场景
4. 优先复用原仓库产品逻辑，不复用技术栈
5. 第一阶段只做最小可交付版本
6. 以 Android 原生单端架构为准

## 代码改造任务清单

- [ ] 新建 Android Studio 项目结构
- [ ] 设计本地数据库 schema
- [ ] 实现 Room 实体、DAO、Repository
- [ ] 实现材料导入模块
- [ ] 实现本地文件落盘与删除策略
- [ ] 实现素材列表页
- [ ] 实现句子列表页
- [ ] 实现训练页
- [ ] 实现句子播放控制
- [ ] 实现录音流程
- [ ] 接入 Moonshine 本地转写
- [ ] 实现转写结果回填
- [ ] 实现目标句与识别句文本比对
- [ ] 实现基础得分计算
- [ ] 实现最近一次成绩回填
- [ ] 实现历史练习记录页
- [ ] 实现基础设置页
- [ ] 移除所有 Web API 依赖
- [ ] 移除所有 Python/FastAPI/faster-whisper/DeepSeek 依赖
- [ ] 将产品逻辑重写为本地安卓业务流
