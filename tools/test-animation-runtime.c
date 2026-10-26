#include <math.h>
#include "../app/src/main/cpp/AnimationRuntime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

static const char *bulkCases[] = {"local n = luxio_get_pixel_count()\nfunction loop()\n  luxio_fill(11, 22, 33)\n  assert(not pcall(luxio_fill_range, -1, 1, 9))\n  assert(not pcall(luxio_fill_range, n, 1, 9))\n  assert(not pcall(luxio_fill_range, 0, n + 1, 9))\n  assert(not pcall(luxio_fill_range, 0.5, 1, 9))\n  assert(not pcall(luxio_fill_range, 0, -1, 9))\n  assert(not pcall(luxio_fill, 256))\n  assert(not pcall(luxio_fill, -1))\n  assert(not pcall(luxio_fill, 1.5))\n  assert(not pcall(luxio_fill, 1/0))\n  assert(not pcall(luxio_fill_hsv, 0/0))\n  assert(not pcall(luxio_fill_hsv, 0, 1/0))\n  assert(not pcall(luxio_fill_hsv, 0, 0, -0.1))\n  assert(not pcall(luxio_fill_hsv, 0, 0, 1, 1.1))\n  local kind = luxio_get_color_type()\n  if kind == \"RGBCCT\" then assert(not pcall(luxio_fill, 0, 0, 0, 1))\n  else assert(not pcall(luxio_fill, 0, 0, 0, 0, 1)) end\n  luxio_fill_range(n, 0, 255)\n  luxio_fill_range(n - 1, 1, 44, 55, 66)\nend","function loop() luxio_fill_hsv(0, 1 / 6) end","function loop() luxio_fill_hsv(-9.5, 0, 0, 0.5) end","function loop()\n local kind = luxio_get_color_type()\n if kind == \"RGBCCT\" then luxio_fill(0, 0, 0, 0, 180, 75)\n elseif kind == \"RGBW\" then luxio_fill(0, 0, 0, 255)\n else luxio_fill(255, 190, 105) end\nend","function loop() luxio_fill_hsv(-1/3, -1/6) end","function loop()\n local kind = luxio_get_color_type()\n if kind == \"RGBCCT\" then luxio_fill(0,0,0,0,180,75)\n elseif kind == \"RGBW\" then luxio_fill(0,0,0,255)\n else luxio_fill(255,190,105) end\n luxio_fill_hsv(1)\nend","local waves = {{offset = 0.5}, {offset = 0.5}, {positive = true, offset = -1}}\nfunction loop()\n luxio_render_waves(waves, {\n  {offset = 200, multiply = 2, {wave = 1, gain = 200}},\n  {{wave = 1, gain = 255, power = 3}},\n  {{wave = 3, gain = 255, power = 2}}})\nend","function loop()\n luxio_render_waves({{offset = 0.5, {amplitude = 0.25, phase = 0.3, step = -0.2}, {amplitude = 0.1, phase = -0.7, step = 0.4}}}, {{{wave = 1}}, {{wave = 1, gain = 100, power = 8}}, {offset = -10}})\nend","function loop()\n local channels = {}\n local kind = luxio_get_color_type()\n if kind == \"RGBCCT\" then channels[5] = {{wave = 1, gain = 180}}; channels[6] = {{wave = 1, gain = 75}}\n elseif kind == \"RGBW\" then channels[4] = {{wave = 1}}\n else channels = {{{wave = 1}}, {{wave = 1, gain = 190}}, {{wave = 1, gain = 105}}} end\n luxio_render_waves({{offset = 1}}, channels)\nend","function loop()\n luxio_fill(11,22,33)\n local invalid = {\n  {{}, {}}, {{{},{},{},{},{}}, {}}, {{{offset=1/0}}, {}},\n  {{{positive=1}}, {}}, {{{{phase=0/0}}}, {}}, {{{{step=1e20}}}, {}},\n  {{{{amplitude=5}}}, {}}, {{{{},{},{},{},{}}}, {}},\n  {{{}}, {{{wave=2}}}}, {{{}}, {{{wave=1,power=1.5}}}},\n  {{{}}, {{{wave=1,power=0}}}}, {{{}}, {{{wave=1,power=17}}}},\n  {{{}}, {{{wave=1,gain=1/0}}}}, {{{}}, {{offset=0/0}}},\n  {{{}}, {{multiply=2}}}, {{{}}, {{multiply=0.5}}},\n  {{{}}, {{{wave=1},{wave=1},{wave=1},{wave=1},{wave=1}}}},\n  {{{}}, {{},{},{},{},{},{},{}}}, {{{}}, {42}}, {{false}, {}},\n }\n for _, args in ipairs(invalid) do assert(not pcall(luxio_render_waves,args[1],args[2])) end\n local kind = luxio_get_color_type()\n local bad = kind == \"RGBCCT\" and 4 or 5\n assert(not pcall(luxio_render_waves, {{offset=1}}, {[bad]={{wave=1}}}))\nend"};

int main(void) {
    LuxioAnimation *a = luxio_animation_create(60, 2805); assert(a);
    assert(luxio_animation_parameter(a, "Speed", false, 100));
    assert(luxio_animation_parameter(a, "Flicker", true, 1));
    assert(luxio_animation_start(a, "function setup() assert(luxio_get_pixel_count()==60) end\nfunction loop() for i=0,59 do luxio_set_pixel_color(i, luxio_get_parameter('Speed'), luxio_get_parameter('Flicker') and 50 or 0, luxio_millis()%256) end end"));
    const uint8_t *pixels = luxio_animation_frame(a, 33); assert(pixels && pixels[0] == 100 && pixels[1] == 50 && pixels[2] == 33 && pixels[59*6] == 100);
    assert(luxio_animation_parameter(a, "Speed", false, 200));
    assert(luxio_animation_parameter(a, "Flicker", true, 0));
    pixels = luxio_animation_frame(a, 66); assert(pixels && pixels[0] == 200 && pixels[1] == 0 && pixels[2] == 66);
    luxio_animation_destroy(a);
    const char *bad[] = {
        "while true do end",
        "function setup() while true do end end function loop() end",
        "local t={} for i=1,1000000 do t[i]=i end function loop() end",
        "assert(io) function loop() end", "assert(os) function loop() end", "assert(package) function loop() end",
        "assert(debug) function loop() end", "assert(load) function loop() end",
        "function loop() luxio_set_pixel_color(60,255) end",
        "function loop() luxio_set_pixel_color(0,0,0,0,255) end",
        "function loop() while true do end end",
        "function loop() error({}) end",
        "function loop() end setmetatable(_G, {__index=function() while true do end end})"
    };
    for (unsigned i = 0; i < sizeof(bad)/sizeof(*bad); i++) {
        a = luxio_animation_create(60, 2805); assert(a);
        bool started = luxio_animation_start(a, bad[i]);
        assert(!started || !luxio_animation_frame(a, 33));
        assert(strlen(luxio_animation_error(a)) > 0);
        luxio_animation_destroy(a);
    }
    a = luxio_animation_create(1, 2814); assert(a);
    assert(luxio_animation_start(a, "function loop() luxio_set_pixel_color(0,0,0,0,255) end"));
    assert(luxio_animation_frame(a, 33)[3] == 255); luxio_animation_destroy(a);
    int strips[] = {2812, 2814, 2805, 6812};
    int counts[] = {1, 60, 1024};
    const uint8_t expected[][6] = {{255,190,105,0,0,0}, {0,0,0,255,0,0}, {0,0,0,0,180,75}, {0,0,0,255,0,0}};
    for (int strip = 0; strip < 4; strip++) for (int count = 0; count < 3; count++) {
        a = luxio_animation_create(counts[count], strips[strip]); assert(a);
        assert(luxio_animation_start(a,
            "local kind = luxio_get_color_type()\n"
            "function setup() assert(luxio_get_color_type()==kind) end\n"
            "function loop() assert(luxio_get_color_type()==kind) for i=0,luxio_get_pixel_count()-1 do "
            "if kind=='RGBCCT' then luxio_set_pixel_color(i,0,0,0,0,180,75) "
            "elseif kind=='RGBW' then luxio_set_pixel_color(i,0,0,0,255) "
            "elseif kind=='RGB' then luxio_set_pixel_color(i,255,190,105) else error('Unknown color type') end end end"));
        for (int frame = 0; frame < 3; frame++) {
            pixels = luxio_animation_frame(a, frame * 33); assert(pixels);
            for (int pixel = 0; pixel < counts[count]; pixel++) assert(!memcmp(pixels + pixel * 6, expected[strip], 6));
        }
        luxio_animation_destroy(a);
    }
    for (int strip = 0; strip < 4; strip++) for (int count = 0; count < 3; count++) {
        int n = counts[count];
        for (unsigned test = 0; test < sizeof(bulkCases)/sizeof(*bulkCases); test++) {
            a = luxio_animation_create(n, strips[strip]); assert(a);
            assert(luxio_animation_start(a, bulkCases[test]));
            pixels = luxio_animation_frame(a, 33); assert(pixels);
            for (int i = 0; i < n; i++) {
                uint8_t expectedBulk[6] = {0};
                if (test == 0) { expectedBulk[0] = i==n-1?44:11; expectedBulk[1] = i==n-1?55:22; expectedBulk[2] = i==n-1?66:33; }
                if (test == 1) { const uint8_t colors[6][3] = {{255,0,0},{255,255,0},{0,255,0},{0,255,255},{0,0,255},{255,0,255}}; memcpy(expectedBulk, colors[i%6], 3); }
                if (test == 2) expectedBulk[0] = expectedBulk[1] = expectedBulk[2] = 127;
                if (test == 3 || test == 8) memcpy(expectedBulk, expected[strip], 6);
                if (test == 4) { const uint8_t colors[6][3] = {{0,0,255},{0,255,255},{0,255,0},{255,255,0},{255,0,0},{255,0,255}}; memcpy(expectedBulk, colors[i%6], 3); }
                if (test == 5) expectedBulk[0] = 255;
                if (test == 6) { expectedBulk[0]=127; expectedBulk[1]=31; }
                if (test == 7) { double v=0.5+0.25*sin(0.3-i*0.2)+0.1*sin(-0.7+i*0.4); expectedBulk[0]=(uint8_t)(255*v); expectedBulk[1]=(uint8_t)(100*pow(v,8)); }
                if (test == 9) { expectedBulk[0]=11; expectedBulk[1]=22; expectedBulk[2]=33; }
                for (int c = 0; c < 6; c++) assert(abs(pixels[i*6+c] - expectedBulk[c]) <= (test==1||test==4||test==7?1:0));
            }
            luxio_animation_destroy(a);
        }
    }
    puts("PASS: native bulk fill/range/HSV/waves, bounds, rejected writes and white channels");
    assert(!luxio_animation_create(0, 2805)); assert(!luxio_animation_create(1025, 2805));
    puts("PASS: pixel output, timing, live parameters, color capabilities, sandbox, memory and instruction limits");
}
