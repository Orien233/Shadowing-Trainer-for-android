# Moonshine Android 集成指南

## 前置条件

- Android Studio（Hedgehog+）
- NDK（通过 SDK Manager 安装）
- CMake 3.22+（通过 SDK Manager 安装）

## 步骤 1：克隆 Moonshine 核心库

```bash
cd app/src/main/cpp
git clone --depth 1 https://github.com/usefulsensors/moonshine.git moonshine-repo
cp -r moonshine-repo/core moonshine-core
rm -rf moonshine-repo
```

这会把 Moonshine 的 C/C++ 核心库放到 `cpp/moonshine-core/`，CMakeLists.txt 会自动找到它。

## 步骤 2：下载模型文件

Moonshine 模型是 3 个 ONNX Runtime 文件：
- `encoder_model.ort` — 编码器
- `decoder_model_merged.ort` — 解码器
- `tokenizer.bin` — 分词器

### 方式 A：用 Python 工具下载（推荐）

```bash
pip install moonshine-voice
python -m moonshine_voice.download --language en
```

下载完成后，把模型文件复制到：
```
app/src/main/assets/moonshine/tiny-en/
├── encoder_model.ort
├── decoder_model_merged.ort
└── tokenizer.bin
```

### 方式 B：从 release 下载

从 [moonshine releases](https://github.com/usefulsensors/moonshine/releases) 下载模型包。

### 模型大小参考

| 模型 | 大小 | WER | 速度（移动端） |
|------|------|-----|---------------|
| tiny | ~26MB | 12.00% | 最快 |
| base | ~100MB | ~9% | 快 |
| small | ~250MB | 7.84% | 中等 |
| medium | ~500MB | 6.65% | 较慢 |

**建议 Android 第一阶段用 tiny-en**，速度优先，后续可升级。

## 步骤 3：Sync & Build

1. 用 Android Studio 打开项目
2. File → Sync Project with Gradle Files
3. Build → Make Project

首次 build 会编译 Moonshine C++ 核心库，可能需要几分钟。

## 文件结构

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt           # CMake 构建配置
│   ├── moonshine_jni.cpp        # JNI 桥接
│   └── moonshine-core/          # Moonshine C++ 核心（步骤 1 获取）
│       ├── CMakeLists.txt
│       ├── src/
│       └── third-party/
├── assets/
│   └── moonshine/
│       └── tiny-en/             # 模型文件（步骤 2 获取）
│           ├── encoder_model.ort
│           ├── decoder_model_merged.ort
│           └── tokenizer.bin
└── java/com/orien/shadowing/data/local/moonshine/
    ├── MoonshineTranscriber.kt  # JNI 封装
    └── AudioUtils.kt            # 音频预处理
```

## 故障排查

### `UnsatisfiedLinkError: moonshine-jni`
- 检查 NDK 和 CMake 是否安装
- 检查 `cpp/moonshine-core/` 目录是否存在
- Clean Project → Rebuild

### `Failed to load Moonshine model`
- 检查模型文件是否在正确路径
- 确认 3 个 .ort 文件和 tokenizer.bin 都存在
- 检查文件大小是否正确（tiny-en 约 26MB）

### 编译 C++ 报错
- 确认 NDK 版本 ≥ 25
- 确认 CMake 版本 ≥ 3.22
- 检查 moonshine-core 的子模块是否完整
