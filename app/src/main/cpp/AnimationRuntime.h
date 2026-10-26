#pragma once
#include <stdint.h>
#include <stdbool.h>
typedef struct LuxioAnimation LuxioAnimation;
LuxioAnimation *luxio_animation_create(int count, int strip);
void luxio_animation_destroy(LuxioAnimation *animation);
bool luxio_animation_parameter(LuxioAnimation *animation, const char *name, bool boolean, double value);
bool luxio_animation_start(LuxioAnimation *animation, const char *source);
const uint8_t *luxio_animation_frame(LuxioAnimation *animation, uint32_t milliseconds);
const char *luxio_animation_error(LuxioAnimation *animation);
