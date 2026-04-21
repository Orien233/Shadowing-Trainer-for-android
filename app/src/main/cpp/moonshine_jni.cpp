#include <jni.h>

#include <cmath>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>

#include "transcriber.h"

namespace {

std::mutex gTranscribersMutex;
std::map<jint, std::shared_ptr<Transcriber>> gTranscribers;
jint gNextHandle = 1;

std::string jstringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return "";
    }

    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars == nullptr ? "" : chars;
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

void throwRuntimeException(JNIEnv* env, const std::string& message) {
    jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
        env->DeleteLocalRef(exceptionClass);
    }
}

std::string transcriptToText(const transcript_t* transcript) {
    if (transcript == nullptr || transcript->lines == nullptr || transcript->line_count == 0) {
        return "";
    }

    std::ostringstream builder;
    bool hasPreviousLine = false;
    for (uint64_t index = 0; index < transcript->line_count; ++index) {
        const transcript_line_t& line = transcript->lines[index];
        if (line.text == nullptr || line.text[0] == '\0') {
            continue;
        }

        if (hasPreviousLine) {
            builder << '\n';
        }
        builder << line.text;
        hasPreviousLine = true;
    }

    return builder.str();
}

jlong secondsToMilliseconds(float seconds) {
    return static_cast<jlong>(std::llround(seconds * 1000.0));
}

jobjectArray createTranscriptLines(JNIEnv* env, const transcript_t* transcript) {
    jclass lineClass = env->FindClass(
        "com/orien/shadowing/data/local/moonshine/MoonshineTranscriber$TranscriptLine"
    );
    if (lineClass == nullptr) {
        throwRuntimeException(env, "Moonshine transcript line class not found");
        return nullptr;
    }

    jmethodID lineConstructor = env->GetMethodID(lineClass, "<init>", "(Ljava/lang/String;JJ)V");
    if (lineConstructor == nullptr) {
        throwRuntimeException(env, "Moonshine transcript line constructor not found");
        env->DeleteLocalRef(lineClass);
        return nullptr;
    }

    const jsize lineCount = transcript == nullptr ? 0 : static_cast<jsize>(transcript->line_count);
    jobjectArray jLines = env->NewObjectArray(lineCount, lineClass, nullptr);
    if (jLines == nullptr) {
        env->DeleteLocalRef(lineClass);
        return nullptr;
    }

    if (transcript != nullptr) {
        for (jsize index = 0; index < lineCount; ++index) {
            const transcript_line_t& line = transcript->lines[index];
            const std::string lineText = line.text == nullptr ? "" : line.text;
            jstring jLineText = env->NewStringUTF(lineText.c_str());
            const jlong startTimeMs = secondsToMilliseconds(line.start_time);
            const jlong endTimeMs = secondsToMilliseconds(line.start_time + line.duration);
            jobject jLine = env->NewObject(
                lineClass,
                lineConstructor,
                jLineText,
                startTimeMs,
                endTimeMs
            );
            env->SetObjectArrayElement(jLines, index, jLine);
            env->DeleteLocalRef(jLine);
            env->DeleteLocalRef(jLineText);
        }
    }

    env->DeleteLocalRef(lineClass);
    return jLines;
}

jobject createTranscriptObject(JNIEnv* env, const transcript_t* transcript) {
    jclass transcriptClass =
        env->FindClass("com/orien/shadowing/data/local/moonshine/MoonshineTranscriber$Transcript");
    if (transcriptClass == nullptr) {
        throwRuntimeException(env, "Moonshine transcript class not found");
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(
        transcriptClass,
        "<init>",
        "(Ljava/lang/String;[Lcom/orien/shadowing/data/local/moonshine/MoonshineTranscriber$TranscriptLine;[I)V"
    );
    if (constructor == nullptr) {
        throwRuntimeException(env, "Moonshine transcript constructor not found");
        env->DeleteLocalRef(transcriptClass);
        return nullptr;
    }

    const std::string text = transcriptToText(transcript);
    jstring jText = env->NewStringUTF(text.c_str());
    jobjectArray jLines = createTranscriptLines(env, transcript);
    if (jLines == nullptr && env->ExceptionCheck()) {
        env->DeleteLocalRef(jText);
        env->DeleteLocalRef(transcriptClass);
        return nullptr;
    }
    jintArray jTokens = env->NewIntArray(0);
    jobject transcriptObject = env->NewObject(transcriptClass, constructor, jText, jLines, jTokens);

    env->DeleteLocalRef(jTokens);
    env->DeleteLocalRef(jLines);
    env->DeleteLocalRef(jText);
    env->DeleteLocalRef(transcriptClass);
    return transcriptObject;
}

TranscriberOptions createCommonOptions(jint modelArch) {
    TranscriberOptions options;
    options.model_arch = static_cast<uint32_t>(modelArch);
    options.identify_speakers = false;
    options.return_audio_data = false;
    options.word_timestamps = false;
    return options;
}

jint storeTranscriber(std::shared_ptr<Transcriber> transcriber) {
    std::lock_guard<std::mutex> lock(gTranscribersMutex);
    const jint handle = gNextHandle++;
    gTranscribers[handle] = std::move(transcriber);
    return handle;
}

std::shared_ptr<Transcriber> getTranscriber(jint handle) {
    std::lock_guard<std::mutex> lock(gTranscribersMutex);
    const auto iterator = gTranscribers.find(handle);
    if (iterator == gTranscribers.end()) {
        return nullptr;
    }
    return iterator->second;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_orien_shadowing_data_local_moonshine_MoonshineTranscriber_nativeLoadFromFiles(
    JNIEnv* env,
    jobject /* thiz */,
    jstring path,
    jint modelArch) {
    const std::string modelPath = jstringToString(env, path);
    if (modelPath.empty()) {
        throwRuntimeException(env, "Moonshine model path is empty");
        return -1;
    }

    try {
        TranscriberOptions options = createCommonOptions(modelArch);
        options.model_source = TranscriberOptions::ModelSource::FILES;
        options.model_path = modelPath.c_str();
        return storeTranscriber(std::make_shared<Transcriber>(options));
    } catch (const std::exception& error) {
        throwRuntimeException(env, error.what());
        return -1;
    }
}

JNIEXPORT jint JNICALL
Java_com_orien_shadowing_data_local_moonshine_MoonshineTranscriber_nativeLoadFromMemory(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray encoderData,
    jbyteArray decoderData,
    jbyteArray tokenizerData,
    jint modelArch) {
    if (encoderData == nullptr || decoderData == nullptr || tokenizerData == nullptr) {
        throwRuntimeException(env, "Moonshine model buffers must not be null");
        return -1;
    }

    jbyte* encoder = env->GetByteArrayElements(encoderData, nullptr);
    jbyte* decoder = env->GetByteArrayElements(decoderData, nullptr);
    jbyte* tokenizer = env->GetByteArrayElements(tokenizerData, nullptr);

    const jint encoderLen = env->GetArrayLength(encoderData);
    const jint decoderLen = env->GetArrayLength(decoderData);
    const jint tokenizerLen = env->GetArrayLength(tokenizerData);

    try {
        TranscriberOptions options = createCommonOptions(modelArch);
        options.model_source = TranscriberOptions::ModelSource::MEMORY;
        options.encoder_model_data = reinterpret_cast<const uint8_t*>(encoder);
        options.encoder_model_data_size = static_cast<size_t>(encoderLen);
        options.decoder_model_data = reinterpret_cast<const uint8_t*>(decoder);
        options.decoder_model_data_size = static_cast<size_t>(decoderLen);
        options.tokenizer_data = reinterpret_cast<const uint8_t*>(tokenizer);
        options.tokenizer_data_size = static_cast<size_t>(tokenizerLen);

        const jint handle = storeTranscriber(std::make_shared<Transcriber>(options));

        env->ReleaseByteArrayElements(encoderData, encoder, JNI_ABORT);
        env->ReleaseByteArrayElements(decoderData, decoder, JNI_ABORT);
        env->ReleaseByteArrayElements(tokenizerData, tokenizer, JNI_ABORT);
        return handle;
    } catch (const std::exception& error) {
        env->ReleaseByteArrayElements(encoderData, encoder, JNI_ABORT);
        env->ReleaseByteArrayElements(decoderData, decoder, JNI_ABORT);
        env->ReleaseByteArrayElements(tokenizerData, tokenizer, JNI_ABORT);
        throwRuntimeException(env, error.what());
        return -1;
    }
}

JNIEXPORT void JNICALL
Java_com_orien_shadowing_data_local_moonshine_MoonshineTranscriber_nativeFreeTranscriber(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint handle) {
    std::lock_guard<std::mutex> lock(gTranscribersMutex);
    gTranscribers.erase(handle);
}

JNIEXPORT jobject JNICALL
Java_com_orien_shadowing_data_local_moonshine_MoonshineTranscriber_nativeTranscribe(
    JNIEnv* env,
    jobject /* thiz */,
    jint handle,
    jfloatArray audioData,
    jint sampleRate) {
    if (audioData == nullptr) {
        throwRuntimeException(env, "Audio buffer must not be null");
        return nullptr;
    }

    const std::shared_ptr<Transcriber> transcriber = getTranscriber(handle);
    if (transcriber == nullptr) {
        throwRuntimeException(env, "Moonshine transcriber is not loaded");
        return nullptr;
    }

    jfloat* audio = env->GetFloatArrayElements(audioData, nullptr);
    const jsize sampleCount = env->GetArrayLength(audioData);

    try {
        transcript_t* transcript = nullptr;
        transcriber->transcribe_without_streaming(
            reinterpret_cast<const float*>(audio),
            static_cast<uint64_t>(sampleCount),
            sampleRate,
            0,
            &transcript
        );

        env->ReleaseFloatArrayElements(audioData, audio, JNI_ABORT);
        return createTranscriptObject(env, transcript);
    } catch (const std::exception& error) {
        env->ReleaseFloatArrayElements(audioData, audio, JNI_ABORT);
        throwRuntimeException(env, error.what());
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_orien_shadowing_data_local_moonshine_MoonshineTranscriber_nativeFreeStream(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint /* transcriberHandle */,
    jint /* streamHandle */) {}

}  // extern "C"
