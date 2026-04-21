cmake_minimum_required(VERSION 3.22.1)

project("shadowing-moonshine")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(MOONSHINE_CORE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/moonshine-core")
include("${MOONSHINE_CORE_DIR}/third-party/onnxruntime/find-ort-library-path.cmake")

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION "${ONNXRUNTIME_LIB_PATH}"
)

add_library(shadowing-moonshine SHARED
    moonshine_jni.cpp
    ${MOONSHINE_CORE_DIR}/moonshine-utils/debug-utils.cpp
    ${MOONSHINE_CORE_DIR}/moonshine-utils/string-utils.cpp
    ${MOONSHINE_CORE_DIR}/ort-utils/ort-utils.cpp
    ${MOONSHINE_CORE_DIR}/ort-utils/moonshine-ort-allocator.cpp
    ${MOONSHINE_CORE_DIR}/ort-utils/moonshine-tensor-view.cpp
    ${MOONSHINE_CORE_DIR}/ort-utils/moonshine-tensor.cpp
    ${MOONSHINE_CORE_DIR}/bin-tokenizer/bin-tokenizer.cpp
    ${MOONSHINE_CORE_DIR}/cosine-distance.cpp
    ${MOONSHINE_CORE_DIR}/moonshine-model.cpp
    ${MOONSHINE_CORE_DIR}/moonshine-streaming-model.cpp
    ${MOONSHINE_CORE_DIR}/voice-activity-detector.cpp
    ${MOONSHINE_CORE_DIR}/silero-vad.cpp
    ${MOONSHINE_CORE_DIR}/resampler.cpp
    ${MOONSHINE_CORE_DIR}/speaker-embedding-model.cpp
    ${MOONSHINE_CORE_DIR}/speaker-embedding-model-data.cpp
    ${MOONSHINE_CORE_DIR}/online-clusterer.cpp
    ${MOONSHINE_CORE_DIR}/word-alignment.cpp
    ${MOONSHINE_CORE_DIR}/transcriber.cpp
)

target_include_directories(shadowing-moonshine PRIVATE
    ${MOONSHINE_CORE_DIR}
    ${MOONSHINE_CORE_DIR}/moonshine-utils
    ${MOONSHINE_CORE_DIR}/ort-utils
    ${MOONSHINE_CORE_DIR}/bin-tokenizer
    ${MOONSHINE_CORE_DIR}/third-party
    ${MOONSHINE_CORE_DIR}/third-party/onnxruntime/include
    ${MOONSHINE_CORE_DIR}/third-party/utf-8
)

target_link_libraries(shadowing-moonshine
    onnxruntime
    android
    log
)
