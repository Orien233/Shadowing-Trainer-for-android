#include "speaker-embedding-model-data.h"

// The app does not use speaker diarization, and the real embedded speaker
// model blob is absent from this checkout. Keep a tiny stub symbol so Android
// ASR can link successfully while speaker identification remains disabled.
const uint8_t speaker_embedding_model_ort_bytes[] = {0};
const size_t speaker_embedding_model_ort_byte_count = 0;
