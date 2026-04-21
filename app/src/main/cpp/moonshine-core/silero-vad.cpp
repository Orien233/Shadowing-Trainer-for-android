#include "silero-vad.h"

#include <algorithm>
#include <stdexcept>

#include "ort-utils.h"
#include "silero-vad-model-data.h"

namespace {

void throw_on_ort_error(const OrtApi *ort_api, OrtStatus *status,
                        const char *context) {
  if (status == nullptr) {
    return;
  }
  std::string message = "Unknown ORT error";
  if (ort_api != nullptr) {
    const char *ort_message = ort_api->GetErrorMessage(status);
    if (ort_message != nullptr) {
      message = ort_message;
    }
    ort_api->ReleaseStatus(status);
  }
  throw std::runtime_error(std::string(context) + ": " + message);
}

const OrtApi *get_compatible_ort_api_or_throw(const char *component) {
  const OrtApiBase *ort_api_base = OrtGetApiBase();
  if (ort_api_base == nullptr) {
    throw std::runtime_error(std::string(component) +
                             ": Failed to acquire ONNX Runtime API base");
  }

  const char *runtime_version = ort_api_base->GetVersionString();
  constexpr int kMaxProbeApiVersion = 256;
  int max_supported_api_version = 0;
  for (int api_version = 1; api_version <= kMaxProbeApiVersion; ++api_version) {
    const OrtApi *candidate =
        ort_api_base->GetApi(static_cast<uint32_t>(api_version));
    if (candidate != nullptr) {
      max_supported_api_version = api_version;
    }
  }

  if (max_supported_api_version == 0) {
    throw std::runtime_error(
        std::string(component) +
        ": Failed to acquire ONNX Runtime API. Runtime did not report any "
        "supported API version. Runtime=" +
        (runtime_version == nullptr ? std::string("unknown")
                                    : std::string(runtime_version)));
  }

  const int selected_api_version =
      std::min(static_cast<int>(ORT_API_VERSION), max_supported_api_version);
  const OrtApi *selected_api =
      ort_api_base->GetApi(static_cast<uint32_t>(selected_api_version));
  if (selected_api == nullptr) {
    throw std::runtime_error(
        std::string(component) +
        ": Failed to acquire ONNX Runtime API. Header API=" +
        std::to_string(ORT_API_VERSION) +
        ", runtime max API=" + std::to_string(max_supported_api_version) +
        ", runtime=" +
        (runtime_version == nullptr ? std::string("unknown")
                                    : std::string(runtime_version)));
  }

  if (selected_api_version != static_cast<int>(ORT_API_VERSION)) {
    LOGF(
        "%s: ORT API fallback activated. Header API=%d, selected API=%d, runtime max API=%d, runtime=%s",
        component, ORT_API_VERSION, selected_api_version,
        max_supported_api_version,
        runtime_version == nullptr ? "unknown" : runtime_version);
  }

  return selected_api;
}

void validate_required_ort_api_or_throw(const OrtApi *ort_api,
                                        const char *component) {
  if (ort_api == nullptr) {
    throw std::runtime_error(std::string(component) +
                             ": ORT API pointer is null");
  }

#define CHECK_ORT_FN(name)                                                   \
  if (ort_api->name == nullptr) {                                            \
    throw std::runtime_error(std::string(component) +                        \
                             ": ORT API missing required function " #name);  \
  }

  CHECK_ORT_FN(GetErrorMessage)
  CHECK_ORT_FN(ReleaseStatus)
  CHECK_ORT_FN(CreateEnv)
  CHECK_ORT_FN(CreateSessionOptions)
  CHECK_ORT_FN(SetIntraOpNumThreads)
  CHECK_ORT_FN(SetInterOpNumThreads)
  CHECK_ORT_FN(SetSessionGraphOptimizationLevel)
  CHECK_ORT_FN(CreateCpuMemoryInfo)
  CHECK_ORT_FN(GetAllocatorWithDefaultOptions)
  CHECK_ORT_FN(CreateTensorWithDataAsOrtValue)
  CHECK_ORT_FN(Run)
  CHECK_ORT_FN(GetTensorMutableData)
  CHECK_ORT_FN(ReleaseValue)
  CHECK_ORT_FN(ReleaseSession)
  CHECK_ORT_FN(ReleaseSessionOptions)
  CHECK_ORT_FN(ReleaseMemoryInfo)
  CHECK_ORT_FN(ReleaseEnv)

#undef CHECK_ORT_FN
}

}  // namespace

void SileroVad::init_onnx_env() {
  ort_api = get_compatible_ort_api_or_throw("SileroVad");
  validate_required_ort_api_or_throw(ort_api, "SileroVad");
  throw_on_ort_error(ort_api,
                     ort_api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "SileroVAD",
                                        &env),
                     "CreateEnv failed");
  throw_on_ort_error(ort_api, ort_api->CreateSessionOptions(&session_options),
                     "CreateSessionOptions failed");
  throw_on_ort_error(ort_api, ort_api->SetIntraOpNumThreads(session_options, 1),
                     "SetIntraOpNumThreads failed");
  throw_on_ort_error(ort_api, ort_api->SetInterOpNumThreads(session_options, 1),
                     "SetInterOpNumThreads failed");
  throw_on_ort_error(ort_api,
                     ort_api->SetSessionGraphOptimizationLevel(session_options,
                                                               ORT_ENABLE_ALL),
                     "SetSessionGraphOptimizationLevel failed");
  throw_on_ort_error(ort_api, ort_api->CreateCpuMemoryInfo(
                                  OrtArenaAllocator, OrtMemTypeCPU, &memory_info),
                     "CreateCpuMemoryInfo failed");
  throw_on_ort_error(ort_api, ort_api->GetAllocatorWithDefaultOptions(&allocator),
                     "GetAllocatorWithDefaultOptions failed");
}

// Initializes threading settings.
void SileroVad::init_engine_threads(int inter_threads, int intra_threads) {
  LOG_ORT_ERROR(ort_api,
                ort_api->SetIntraOpNumThreads(session_options, intra_threads));
  LOG_ORT_ERROR(ort_api,
                ort_api->SetInterOpNumThreads(session_options, inter_threads));
  LOG_ORT_ERROR(ort_api, ort_api->SetSessionGraphOptimizationLevel(
                             session_options, ORT_ENABLE_ALL));
}

SileroVad::SileroVad(int sample_rate, int windows_frame_size, float threshold,
                     int min_silence_duration_ms, int speech_pad_ms,
                     int min_speech_duration_ms, float max_speech_duration_s)
    : ort_api(nullptr),
      env(nullptr),
      session_options(nullptr),
      session(nullptr),
      allocator(nullptr),
      memory_info(nullptr),
      threshold(threshold),
      speech_pad_samples(speech_pad_ms) {
  sr_per_ms = sample_rate / 1000;  // e.g., 16000 / 1000 = 16
  window_size_samples =
      windows_frame_size * sr_per_ms;  // e.g., 32ms * 16 = 512 samples
  effective_window_size =
      window_size_samples + context_samples;  // 512 + 64 = 576 samples
  input_node_dims[0] = 1;
  input_node_dims[1] = effective_window_size;
  _state.resize(size_state);
  _context.resize(context_samples, 0.0f);  // Initialize context to zeros
  sr = sample_rate;                        // scalar
  min_speech_samples = sr_per_ms * min_speech_duration_ms;
  max_speech_samples = (sample_rate * max_speech_duration_s -
                        window_size_samples - 2 * speech_pad_samples);
  min_silence_samples = sr_per_ms * min_silence_duration_ms;
  min_silence_samples_at_max_speech = sr_per_ms * 98;
  // Load model from embedded data
  const int load_error = load_from_memory(silero_vad_onnx, silero_vad_onnx_len);
  if (load_error != 0 || session == nullptr) {
    throw std::runtime_error(
        "SileroVad: failed to load embedded VAD model from memory");
  }
}

int SileroVad::load_from_memory(const uint8_t *model_data,
                                size_t model_data_size) {
  init_onnx_env();
  return ort_session_from_memory(ort_api, env, session_options, model_data,
                                 model_data_size, &session);
}

SileroVad::~SileroVad() {
  if (session) ort_api->ReleaseSession(session);
  if (session_options) ort_api->ReleaseSessionOptions(session_options);
  if (env) ort_api->ReleaseEnv(env);
  if (memory_info) ort_api->ReleaseMemoryInfo(memory_info);
  // allocator is owned by ORT, do not release
}

// Inference: runs inference on one chunk of input data.
// data_chunk is expected to have window_size_samples samples (e.g., 512 for
// 16kHz).
void SileroVad::predict(const std::vector<float> &data_chunk,
                        float *out_probability, int *out_flag) {
  if (out_probability != nullptr) {
    *out_probability = 0.0f;
  }
  if (out_flag != nullptr) {
    *out_flag = 0;
  }
  if (ort_api == nullptr || session == nullptr || memory_info == nullptr) {
    LOG("SileroVad: ORT session is not initialized");
    return;
  }

  auto release_value = [this](OrtValue *&value) {
    if (value != nullptr) {
      ort_api->ReleaseValue(value);
      value = nullptr;
    }
  };
  auto log_and_release_status = [this](OrtStatus *status,
                                       const char *context) -> bool {
    if (status == nullptr) {
      return false;
    }
    const char *msg = ort_api->GetErrorMessage(status);
    LOGF("%s: %s", context, msg == nullptr ? "Unknown ORT error" : msg);
    ort_api->ReleaseStatus(status);
    return true;
  };

  // Build input by prepending context (64 samples) to data_chunk (512 samples)
  // = 576 total
  input.assign(effective_window_size, 0.0f);
  std::copy(_context.begin(), _context.end(), input.begin());
  const size_t expected_chunk_size = static_cast<size_t>(window_size_samples);
  if (data_chunk.size() >= expected_chunk_size) {
    std::copy(data_chunk.end() - expected_chunk_size, data_chunk.end(),
              input.begin() + context_samples);
  } else if (!data_chunk.empty()) {
    std::copy(data_chunk.begin(), data_chunk.end(),
              input.begin() + context_samples);
  }

  // Create input tensor
  OrtValue *input_ort = nullptr;
  OrtValue *state_ort = nullptr;
  OrtValue *sr_ort = nullptr;

  OrtStatus *status = nullptr;

  status = ort_api->CreateTensorWithDataAsOrtValue(
      memory_info, input.data(), input.size() * sizeof(float), input_node_dims,
      2, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &input_ort);
  if (log_and_release_status(status,
                             "SileroVad: CreateTensorWithDataAsOrtValue(input) failed")) {
    return;
  }

  status = ort_api->CreateTensorWithDataAsOrtValue(
      memory_info, _state.data(), _state.size() * sizeof(float),
      state_node_dims, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &state_ort);
  if (log_and_release_status(status,
                             "SileroVad: CreateTensorWithDataAsOrtValue(state) failed")) {
    release_value(input_ort);
    return;
  }

  // Create scalar tensor for sample rate (empty shape = scalar)
  status = ort_api->CreateTensorWithDataAsOrtValue(
      memory_info, &sr, sizeof(int64_t), nullptr, 0,
      ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &sr_ort);
  if (log_and_release_status(status,
                             "SileroVad: CreateTensorWithDataAsOrtValue(sr) failed")) {
    release_value(input_ort);
    release_value(state_ort);
    return;
  }

  ort_inputs.clear();
  ort_inputs.push_back(input_ort);
  ort_inputs.push_back(state_ort);
  ort_inputs.push_back(sr_ort);

  // Prepare output OrtValue* array
  OrtValue *output_ort[2] = {nullptr, nullptr};
  status =
      ort_api->Run(session, nullptr, input_node_names.data(), ort_inputs.data(),
                   ort_inputs.size(), output_node_names.data(),
                   output_node_names.size(), output_ort);

  if (log_and_release_status(status, "SileroVad: Run failed")) {
    release_value(input_ort);
    release_value(state_ort);
    release_value(sr_ort);
    return;
  }
  if (output_ort[0] == nullptr || output_ort[1] == nullptr) {
    LOG("SileroVad: Run returned null output tensor");
    release_value(input_ort);
    release_value(state_ort);
    release_value(sr_ort);
    release_value(output_ort[0]);
    release_value(output_ort[1]);
    return;
  }

  float *speech_prob_ptr = nullptr;
  status = ort_api->GetTensorMutableData(output_ort[0], (void **)&speech_prob_ptr);
  if (log_and_release_status(status,
                             "SileroVad: GetTensorMutableData(output) failed") ||
      speech_prob_ptr == nullptr) {
    release_value(input_ort);
    release_value(state_ort);
    release_value(sr_ort);
    release_value(output_ort[0]);
    release_value(output_ort[1]);
    return;
  }
  float speech_prob = speech_prob_ptr[0];

  float *stateN = nullptr;
  status = ort_api->GetTensorMutableData(output_ort[1], (void **)&stateN);
  if (log_and_release_status(status,
                             "SileroVad: GetTensorMutableData(stateN) failed") ||
      stateN == nullptr) {
    release_value(input_ort);
    release_value(state_ort);
    release_value(sr_ort);
    release_value(output_ort[0]);
    release_value(output_ort[1]);
    return;
  }
  std::memcpy(_state.data(), stateN, size_state * sizeof(float));

  // Update context with last context_samples samples of the full input
  std::copy(input.end() - context_samples, input.end(), _context.begin());

  // Set output values
  if (out_probability) *out_probability = speech_prob;
  if (out_flag) *out_flag = (speech_prob >= threshold) ? 1 : 0;

  // Release OrtValues
  release_value(input_ort);
  release_value(state_ort);
  release_value(sr_ort);
  release_value(output_ort[0]);
  release_value(output_ort[1]);
}
