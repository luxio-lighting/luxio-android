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
    (void)debug;
    LuxioAnimation *a = *(LuxioAnimation **)lua_getextraspace(L);
    a->remaining -= 1000;
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
