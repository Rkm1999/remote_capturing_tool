#include "RawRefineryRuntime.h"
#include <onnxruntime/onnxruntime_c_api.h>
#include <onnxruntime/coreml_provider_factory.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <zlib.h>

struct RRSession { const OrtApi *api; OrtEnv *env; OrtSession *session; OrtMemoryInfo *memory; };

static int check(RRSession *rr, OrtStatus *status, char *error, size_t size) {
    if (!status) return 1;
    if (error && size) snprintf(error, size, "%s", rr->api->GetErrorMessage(status));
    rr->api->ReleaseStatus(status);
    return 0;
}

RRSession *rr_session_create(const char *path, int use_coreml, char *error, size_t size) {
    RRSession *rr = calloc(1, sizeof(RRSession));
    if (!rr) return NULL;
    rr->api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    OrtSessionOptions *options = NULL;
    if (!check(rr, rr->api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "RawRefinery", &rr->env), error, size) ||
        !check(rr, rr->api->CreateSessionOptions(&options), error, size)) goto fail;
    if (!check(rr, rr->api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL), error, size)) goto fail;
    if (use_coreml) {
        OrtStatus *provider = OrtSessionOptionsAppendExecutionProvider_CoreML(options, COREML_FLAG_ENABLE_ON_SUBGRAPH);
        if (provider) rr->api->ReleaseStatus(provider);
    }
    OrtStatus *session_status = rr->api->CreateSession(rr->env, path, options, &rr->session);
    if (session_status && use_coreml) {
        rr->api->ReleaseStatus(session_status);
        rr->api->ReleaseSessionOptions(options);
        options = NULL;
        if (!check(rr, rr->api->CreateSessionOptions(&options), error, size)) goto fail;
        if (!check(rr, rr->api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL), error, size)) goto fail;
        session_status = rr->api->CreateSession(rr->env, path, options, &rr->session);
    }
    if (!check(rr, session_status, error, size) ||
        !check(rr, rr->api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &rr->memory), error, size)) goto fail;
    rr->api->ReleaseSessionOptions(options);
    return rr;
fail:
    if (options) rr->api->ReleaseSessionOptions(options);
    rr_session_destroy(rr);
    return NULL;
}

int rr_session_run(RRSession *rr, float *image, size_t count, float condition,
                   long width, long height, float *output, char *error, size_t size) {
    if (!rr || !image || !output) return 0;
    int64_t image_shape[] = {1, 3, height, width};
    int64_t condition_shape[] = {1, 1};
    OrtValue *inputs[2] = {NULL, NULL};
    OrtValue *result = NULL;
    if (!check(rr, rr->api->CreateTensorWithDataAsOrtValue(rr->memory, image, count * sizeof(float), image_shape, 4,
                                                           ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &inputs[0]), error, size) ||
        !check(rr, rr->api->CreateTensorWithDataAsOrtValue(rr->memory, &condition, sizeof(float), condition_shape, 2,
                                                           ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &inputs[1]), error, size)) goto fail;
    const char *input_names[] = {"image", "condition"};
    const char *output_names[] = {"output"};
    if (!check(rr, rr->api->Run(rr->session, NULL, input_names, (const OrtValue *const *)inputs, 2,
                                output_names, 1, &result), error, size)) goto fail;
    float *values = NULL;
    if (!check(rr, rr->api->GetTensorMutableData(result, (void **)&values), error, size)) goto fail;
    memcpy(output, values, count * sizeof(float));
    rr->api->ReleaseValue(result); rr->api->ReleaseValue(inputs[0]); rr->api->ReleaseValue(inputs[1]);
    return 1;
fail:
    if (result) rr->api->ReleaseValue(result);
    if (inputs[0]) rr->api->ReleaseValue(inputs[0]);
    if (inputs[1]) rr->api->ReleaseValue(inputs[1]);
    return 0;
}

void rr_session_destroy(RRSession *rr) {
    if (!rr) return;
    if (rr->memory) rr->api->ReleaseMemoryInfo(rr->memory);
    if (rr->session) rr->api->ReleaseSession(rr->session);
    if (rr->env) rr->api->ReleaseEnv(rr->env);
    free(rr);
}

int rr_inflate_raw(const unsigned char *input, size_t input_size, unsigned char *output, size_t *output_size) {
    z_stream stream = {0};
    stream.next_in = (Bytef *)input; stream.avail_in = (uInt)input_size;
    stream.next_out = output; stream.avail_out = (uInt)*output_size;
    if (inflateInit2(&stream, -MAX_WBITS) != Z_OK) return 0;
    int status = inflate(&stream, Z_FINISH);
    inflateEnd(&stream);
    if (status != Z_STREAM_END) return 0;
    *output_size = stream.total_out;
    return 1;
}
