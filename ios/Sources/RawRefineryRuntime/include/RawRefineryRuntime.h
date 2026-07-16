#pragma once
#include <stddef.h>
typedef struct RRSession RRSession;
RRSession *rr_session_create(const char *model_path, int use_coreml, char *error, size_t error_size);
int rr_session_run(RRSession *session, float *image, size_t image_count, float condition,
                   long width, long height, float *output, char *error, size_t error_size);
void rr_session_destroy(RRSession *session);
int rr_inflate_raw(const unsigned char *input, size_t input_size, unsigned char *output, size_t *output_size);
