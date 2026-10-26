#include "AnimationRuntime.h"
#include "lua/lua.h"
#include "lua/lauxlib.h"
#include "lua/lualib.h"
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <math.h>

struct LuxioAnimation {
    lua_State *lua;
    size_t memory;
    int count, strip, remaining, parameterCount;
    uint32_t elapsed;
    double deadline;
    uint8_t pixels[1024 * 6];
    struct { char name[64]; bool boolean; double value; } parameters[16];
    char error[256];
};
static double monotonic_seconds(void) {
    struct timespec time; clock_gettime(CLOCK_MONOTONIC, &time);
    return time.tv_sec + time.tv_nsec / 1e9;
}
static void *allocate(void *context, void *pointer, size_t oldSize, size_t size) {
    LuxioAnimation *a = context;
    if (!pointer) oldSize = 0;
    if (!size) { free(pointer); a->memory -= oldSize; return NULL; }
    if (size > 131072 || a->memory - oldSize > 131072 - size) return NULL;
    void *result = realloc(pointer, size);
    if (result) a->memory = a->memory - oldSize + size;
    return result;
}
static void budget(lua_State *L, lua_Debug *debug) {
    LuxioAnimation *a = *(LuxioAnimation **)lua_getextraspace(L);
    if (debug) a->remaining -= 1000;
    if (a->remaining <= 0) luaL_error(L, "Animation exceeded its instruction budget");
    if (monotonic_seconds() > a->deadline) luaL_error(L, "Animation exceeded its time budget");
}
static int pixel_count(lua_State *L) {
    LuxioAnimation *a = *(LuxioAnimation **)lua_getextraspace(L);
    lua_pushinteger(L, a->count); return 1;
}
static int color_type(lua_State *L) {
    LuxioAnimation *a = *(LuxioAnimation **)lua_getextraspace(L);
    lua_pushstring(L, a->strip == 2805 ? "RGBCCT" : (a->strip == 2814 || a->strip == 6812) ? "RGBW" : "RGB"); return 1;
}
static int milliseconds(lua_State *L) {
    LuxioAnimation *a = *(LuxioAnimation **)lua_getextraspace(L);
    lua_pushinteger(L, a->elapsed); return 1;
}
static int parameter(lua_State *L) {
    LuxioAnimation *a = *(LuxioAnimation **)lua_getextraspace(L);
    const char *name = luaL_checkstring(L, 1);
    for (int i = 0; i < a->parameterCount; i++) if (!strcmp(name, a->parameters[i].name)) {
        if (a->parameters[i].boolean) lua_pushboolean(L, a->parameters[i].value != 0);
        else lua_pushnumber(L, a->parameters[i].value);
        return 1;
    }
    return luaL_error(L, "Unknown animation parameter: %s", name);
}
static int set_pixel(lua_State *L) {
    LuxioAnimation *a = *(LuxioAnimation **)lua_getextraspace(L);
    lua_Integer index = luaL_checkinteger(L, 1);
    if (index < 0 || index >= a->count) return luaL_error(L, "Pixel index out of range");
    int values[6];
    for (int c = 0; c < 6; c++) {
        lua_Integer value = luaL_optinteger(L, c + 2, 0);
        if (value < 0 || value > 255) return luaL_error(L, "Channels must be 0-255");
        values[c] = (int)value;
    }
    if ((a->strip == 2805 && values[3]) || ((a->strip == 2814 || a->strip == 6812) && (values[4] || values[5])) ||
        (a->strip == 2812 && (values[3] || values[4] || values[5]))) return luaL_error(L, "Channel unavailable on this strip type");
    for (int c = 0; c < 6; c++) a->pixels[index * 6 + c] = (uint8_t)values[c];
    return 0;
}
static int fill(lua_State *L) {
    LuxioAnimation *a = *(LuxioAnimation **)lua_getextraspace(L);
    const int ranged = lua_toboolean(L, lua_upvalueindex(1));
    const lua_Integer start = ranged ? luaL_checkinteger(L, 1) : 0;
    const lua_Integer length = ranged ? luaL_checkinteger(L, 2) : a->count;
    if (start < 0 || start > a->count || length < 0 || length > a->count - start)
        return luaL_error(L, "Pixel range out of bounds (zero-based start, count)");
    uint8_t values[6];
    for (int c = 0; c < 6; c++) {
        lua_Integer value = luaL_optinteger(L, c + (ranged ? 3 : 1), 0);
        if (value < 0 || value > 255) return luaL_error(L, "Channels must be 0-255");
        values[c] = (uint8_t)value;
    }
    if ((a->strip == 2805 && values[3]) || ((a->strip == 2814 || a->strip == 6812) && (values[4] || values[5])) || (a->strip == 2812 && (values[3] || values[4] || values[5]))) return luaL_error(L, "Channel unavailable on this strip type");
    for (lua_Integer i = start; i < start + length; i++) memcpy(a->pixels + i * 6, values, 6);
    return 0;
}
static int fill_hsv(lua_State *L) {
    LuxioAnimation *a = *(LuxioAnimation **)lua_getextraspace(L);
    lua_Number hue = luaL_checknumber(L, 1), step = luaL_optnumber(L, 2, 0);
    lua_Number saturation = luaL_optnumber(L, 3, 1), value = luaL_optnumber(L, 4, 1);
    if (!isfinite(hue) || !isfinite(step) || !isfinite(saturation) || !isfinite(value) ||
        saturation < 0 || saturation > 1 || value < 0 || value > 1)
        return luaL_error(L, "HSV requires finite hue/step and saturation/value from 0 to 1");
    hue -= l_mathop(floor)(hue); step -= l_mathop(floor)(step);
    const int high = (int)l_mathop(floor)(255 * value);
    const int low = (int)l_mathop(floor)(255 * value * (1 - saturation));
    for (int i = 0; i < a->count; i++) {
        if (step == 0 && i > 0) { memcpy(a->pixels + i * 6, a->pixels, 6); continue; }
        lua_Number h = hue + i * step;
        h = (h - l_mathop(floor)(h)) * 6;
        const int sector = (int)l_mathop(floor)(h);
        const int rising = (int)l_mathop(floor)((high - low) * (h - sector));
        const int up = low + rising, down = high - rising;
        uint8_t rgb[6] = {0};
        switch (sector) {
            case 0: rgb[0] = high; rgb[1] = up; rgb[2] = low; break;
            case 1: rgb[0] = down; rgb[1] = high; rgb[2] = low; break;
            case 2: rgb[0] = low; rgb[1] = high; rgb[2] = up; break;
            case 3: rgb[0] = low; rgb[1] = down; rgb[2] = high; break;
            case 4: rgb[0] = up; rgb[1] = low; rgb[2] = high; break;
            default: rgb[0] = high; rgb[1] = low; rgb[2] = down; break;
        }
        memcpy(a->pixels + i * 6, rgb, 6);
    }
    return 0;
}
static int render_waves(lua_State *L) {
    LuxioAnimation *a = *(LuxioAnimation **)lua_getextraspace(L);
    // Small, bounded descriptors on the stack; pixel storage is never duplicated.
    struct WaveTerm { lua_Number phase, step, amplitude; };
    struct Wave { lua_Number offset; int count, positive; struct WaveTerm terms[4]; } waves[4] = {0};
    struct MixTerm { lua_Number gain; int wave, power; };
    struct Channel { lua_Number offset; int count, multiply; struct MixTerm terms[4]; } channels[6] = {0};
    luaL_checktype(L, 1, LUA_TTABLE); luaL_checktype(L, 2, LUA_TTABLE);
    size_t waveCount = lua_rawlen(L, 1);
    if (waveCount < 1 || waveCount > 4) return luaL_error(L, "Use 1-4 waves");
    if (lua_rawlen(L, 2) > 6) return luaL_error(L, "Use at most six color channels");
    for (size_t w = 0; w < waveCount; w++) {
        lua_rawgeti(L, 1, w + 1); luaL_checktype(L, -1, LUA_TTABLE);
        lua_getfield(L, -1, "offset"); waves[w].offset = luaL_optnumber(L, -1, 0); lua_pop(L, 1);
        if (!isfinite(waves[w].offset) || waves[w].offset < -4 || waves[w].offset > 4)
            return luaL_error(L, "Wave offset must be finite and between -4 and 4");
        lua_getfield(L, -1, "positive");
        if (!lua_isnil(L, -1) && !lua_isboolean(L, -1)) return luaL_error(L, "positive must be a boolean");
        waves[w].positive = lua_toboolean(L, -1); lua_pop(L, 1);
        size_t length = lua_rawlen(L, -1);
        if (length > 4) return luaL_error(L, "Use at most four sine terms per wave");
        waves[w].count = (int)length;
        for (size_t t = 0; t < length; t++) {
            lua_rawgeti(L, -1, t + 1); luaL_checktype(L, -1, LUA_TTABLE);
            const char *keys[] = {"phase", "step", "amplitude"};
            lua_Number values[3];
            for (int k = 0; k < 3; k++) {
                lua_getfield(L, -1, keys[k]); values[k] = luaL_optnumber(L, -1, k == 2 ? 1 : 0); lua_pop(L, 1);
                lua_Number limit = k == 2 ? 4 : (lua_Number)1e12;
                if (!isfinite(values[k]) || values[k] < -limit || values[k] > limit)
                    return luaL_error(L, "Invalid sine phase, step or amplitude");
            }
            waves[w].terms[t].phase = values[0]; waves[w].terms[t].step = values[1]; waves[w].terms[t].amplitude = values[2];
            lua_pop(L, 1);
        }
        lua_pop(L, 1);
    }
    for (int c = 0; c < 6; c++) {
        lua_rawgeti(L, 2, c + 1);
        if (lua_isnil(L, -1)) { lua_pop(L, 1); continue; }
        luaL_checktype(L, -1, LUA_TTABLE);
        lua_getfield(L, -1, "offset"); channels[c].offset = luaL_optnumber(L, -1, 0); lua_pop(L, 1);
        if (!isfinite(channels[c].offset) || channels[c].offset < -1024 || channels[c].offset > 1024)
            return luaL_error(L, "Channel offset must be finite and between -1024 and 1024");
        lua_getfield(L, -1, "multiply"); lua_Integer multiply = luaL_optinteger(L, -1, 0); lua_pop(L, 1);
        if (multiply < 0 || multiply > (lua_Integer)waveCount) return luaL_error(L, "Invalid modulation wave index");
        channels[c].multiply = (int)multiply;
        size_t length = lua_rawlen(L, -1);
        if (length > 4) return luaL_error(L, "Use at most four terms per color channel");
        channels[c].count = (int)length;
        if (((a->strip == 2805 && c == 3) || ((a->strip == 2814 || a->strip == 6812) && c >= 4) || (a->strip == 2812 && c >= 3)) && (length || channels[c].offset != 0))
            return luaL_error(L, "Channel unavailable on this strip type");
        for (size_t t = 0; t < length; t++) {
            lua_rawgeti(L, -1, t + 1); luaL_checktype(L, -1, LUA_TTABLE);
            lua_getfield(L, -1, "wave"); lua_Integer wave = luaL_checkinteger(L, -1); lua_pop(L, 1);
            lua_getfield(L, -1, "power"); lua_Integer power = luaL_optinteger(L, -1, 1); lua_pop(L, 1);
            if (wave < 1 || wave > (lua_Integer)waveCount || power < 1 || power > 16)
                return luaL_error(L, "Wave index must exist; power must be an integer from 1 to 16");
            lua_getfield(L, -1, "gain"); lua_Number gain = luaL_optnumber(L, -1, 255); lua_pop(L, 1);
            if (!isfinite(gain) || gain < -1024 || gain > 1024) return luaL_error(L, "Gain must be finite and between -1024 and 1024");
            channels[c].terms[t].wave = (int)wave - 1; channels[c].terms[t].power = (int)power; channels[c].terms[t].gain = gain;
            lua_pop(L, 1);
        }
        lua_pop(L, 1);
    }
    // Finish validation before touching any pixels. Quantize only after mixing.
    for (int i = 0; i < a->count; i++) {
        lua_Number values[4];
        for (size_t w = 0; w < waveCount; w++) {
            lua_Number value = waves[w].offset;
            for (int t = 0; t < waves[w].count; t++) {
                const struct WaveTerm *term = &waves[w].terms[t];
                value += term->amplitude * l_mathop(sin)(term->phase + i * term->step);
            }
            lua_Number minimum = waves[w].positive ? 0 : -1;
            values[w] = value < minimum ? minimum : value > 1 ? 1 : value;
        }
        for (int c = 0; c < 6; c++) {
            lua_Number value = channels[c].offset;
            for (int t = 0; t < channels[c].count; t++) {
                const struct MixTerm *term = &channels[c].terms[t];
                lua_Number base = values[term->wave], powered = 1;
                // Integer powers use multiplies instead of a general pow call.
                for (int p = term->power; p; p >>= 1) { if (p & 1) powered *= base; if (p > 1) base *= base; }
                value += term->gain * powered;
            }
            value = value < 0 ? 0 : value > 255 ? 255 : value;
            if (channels[c].multiply) value *= values[channels[c].multiply - 1];
            a->pixels[i * 6 + c] = (uint8_t)(value < 0 ? 0 : value); // Clamp above, then floor by truncation.
        }
    }
    budget(L, NULL); // Native loops must also obey the frame CPU deadline.
    return 0;
}
static int initialize(lua_State *L) {
    luaL_requiref(L, "_G", luaopen_base, 1); lua_pop(L, 1);
    luaL_requiref(L, LUA_MATHLIBNAME, luaopen_math, 1); lua_pop(L, 1);
    luaL_requiref(L, LUA_TABLIBNAME, luaopen_table, 1); lua_pop(L, 1);
    luaL_requiref(L, LUA_STRLIBNAME, luaopen_string, 1); lua_pop(L, 1);
    luaL_requiref(L, LUA_UTF8LIBNAME, luaopen_utf8, 1); lua_pop(L, 1);
    const char *blocked[] = { "dofile", "loadfile", "load", "collectgarbage", "print" };
    for (int i = 0; i < 5; i++) { lua_pushnil(L); lua_setglobal(L, blocked[i]); }
    lua_pushcfunction(L, pixel_count); lua_setglobal(L, "luxio_get_pixel_count");
    lua_pushcfunction(L, color_type); lua_setglobal(L, "luxio_get_color_type");
    lua_pushcfunction(L, milliseconds); lua_setglobal(L, "luxio_millis");
    lua_pushcfunction(L, parameter); lua_setglobal(L, "luxio_get_parameter");
    lua_pushcfunction(L, set_pixel); lua_setglobal(L, "luxio_set_pixel_color");
    lua_pushboolean(L, 0); lua_pushcclosure(L, fill, 1); lua_setglobal(L, "luxio_fill");
    lua_pushboolean(L, 1); lua_pushcclosure(L, fill, 1); lua_setglobal(L, "luxio_fill_range");
    lua_pushcfunction(L, fill_hsv); lua_setglobal(L, "luxio_fill_hsv");
    lua_pushcfunction(L, render_waves); lua_setglobal(L, "luxio_render_waves");
    return 0;
}
LuxioAnimation *luxio_animation_create(int count, int strip) {
    if (count < 1 || count > 1024 || (strip != 2805 && strip != 2814 && strip != 2812 && strip != 6812)) return NULL;
    LuxioAnimation *a = calloc(1, sizeof(*a)); if (!a) return NULL;
    a->count = count; a->strip = strip;
    a->lua = lua_newstate(allocate, a);
    if (!a->lua) { free(a); return NULL; }
    *(LuxioAnimation **)lua_getextraspace(a->lua) = a;
    lua_pushcfunction(a->lua, initialize);
    if (lua_pcall(a->lua, 0, 0, 0) != LUA_OK) { luxio_animation_destroy(a); return NULL; }
    lua_sethook(a->lua, budget, LUA_MASKCOUNT, 1000);
    return a;
}
void luxio_animation_destroy(LuxioAnimation *a) {
    if (!a) return;
    if (a->lua) lua_close(a->lua);
    free(a);
}
bool luxio_animation_parameter(LuxioAnimation *a, const char *name, bool boolean, double value) {
    if (!a || !name || !name[0] || strlen(name) > 63 || !isfinite(value)) return false;
    int i = 0; for (; i < a->parameterCount; i++) if (!strcmp(name, a->parameters[i].name)) break;
    if (i == 16) return false;
    if (i == a->parameterCount) a->parameterCount++;
    strcpy(a->parameters[i].name, name); a->parameters[i].boolean = boolean; a->parameters[i].value = value;
    return true;
}
static int setup(lua_State *L) {
    lua_getglobal(L, "setup");
    if (lua_isnil(L, -1)) lua_pop(L, 1); else lua_call(L, 0, 0);
    lua_getglobal(L, "loop");
    if (!lua_isfunction(L, -1)) return luaL_error(L, "Define a loop() function");
    lua_pop(L, 1); return 0;
}
static int frame(lua_State *L) {
    lua_getglobal(L, "loop"); lua_call(L, 0, 0); return 0;
}
bool luxio_animation_start(LuxioAnimation *a, const char *source) {
    if (!a || !source || strlen(source) > 16384) return false;
    lua_State *L = a->lua;
    a->remaining = 100000; a->deadline = monotonic_seconds() + 0.03;
    if (luaL_loadbufferx(L, source, strlen(source), "animation", "t") != LUA_OK || lua_pcall(L, 0, 0, 0) != LUA_OK) goto failed;
    a->remaining = 100000; a->deadline = monotonic_seconds() + 0.03;
    lua_pushcfunction(L, setup);
    if (lua_pcall(L, 0, 0, 0) != LUA_OK) goto failed;
    return true;
failed:
    snprintf(a->error, sizeof(a->error), "%s", lua_tostring(L, -1) ? lua_tostring(L, -1) : "Animation failed"); lua_pop(L, 1); return false;
}
const uint8_t *luxio_animation_frame(LuxioAnimation *a, uint32_t milliseconds) {
    if (!a || a->error[0]) return NULL;
    a->elapsed = milliseconds; a->remaining = 100000; a->deadline = monotonic_seconds() + 0.02;
    lua_pushcfunction(a->lua, frame);
    if (lua_pcall(a->lua, 0, 0, 0) != LUA_OK) {
        snprintf(a->error, sizeof(a->error), "%s", lua_tostring(a->lua, -1) ? lua_tostring(a->lua, -1) : "Animation failed"); lua_pop(a->lua, 1); return NULL;
    }
    return a->pixels;
}
const char *luxio_animation_error(LuxioAnimation *a) { return a ? a->error : "Unable to allocate animation"; }
