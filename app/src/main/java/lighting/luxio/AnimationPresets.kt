package lighting.luxio

// Matches the iOS and Studio animation catalog.
data class AnimationPreset(val name: String, val source: String)

val animationPresets = listOf(
    AnimationPreset("Rainbow", """
        -- @preview ff0000,ffff00,00ff00,00ffff,0000ff,ff00ff
        -- @parameter Speed,Slider,50,0,100
        -- @parameter Period,Number,20,1,120
        -- @parameter Reverse,Checkbox,false
        function setup() end
        function loop()
          local count = luxio_get_pixel_count()
          local time = luxio_millis() / (luxio_get_parameter("Period") * 1000) * luxio_get_parameter("Speed") / 50
          if luxio_get_parameter("Reverse") then time = -time end
          luxio_fill_hsv(time, 1 / count)
        end
    """.trimIndent()),
    AnimationPreset("Colorloop", """
        -- @preview ff0000,ffff00,00ff00,00ffff,0000ff,ff00ff
        -- @parameter Speed,Slider,50,0,100
        -- @parameter Period,Number,20,1,120
        -- @parameter Reverse,Checkbox,false
        function setup() end
        function loop()
          local count = luxio_get_pixel_count()
          local time = luxio_millis() / (luxio_get_parameter("Period") * 1000) * luxio_get_parameter("Speed") / 50
          if luxio_get_parameter("Reverse") then time = -time end
          luxio_fill_hsv(time, 0)
        end
    """.trimIndent()),
    AnimationPreset("Aurora", """
        -- @preview 2d1859,8246ff
        -- @parameter Speed,Slider,50,0,100
        -- @parameter Spacing,Number,8,1,64
        -- @parameter Pulse,Checkbox,true
        local waves = {{offset = 0.675, {amplitude = 0.325}}}
        local channels = {{{wave = 1, gain = 130}}, {{wave = 1, gain = 70}}, {{wave = 1, gain = 255}}}
        function setup() end
        function loop()
          local phase = luxio_millis() / 1800 * luxio_get_parameter("Speed") / 50
          local pulse = luxio_get_parameter("Pulse")
          local spacing = luxio_get_parameter("Spacing")
          if pulse then
            waves[1][1].phase, waves[1][1].step = phase, 1 / spacing
            luxio_render_waves(waves, channels)
          else
            luxio_fill(130, 70, 255)
          end
        end
    """.trimIndent()),
    AnimationPreset("Ember", """
        -- @preview 591804,ff460c
        -- @parameter Speed,Slider,50,0,100
        -- @parameter Spacing,Number,8,1,64
        -- @parameter Pulse,Checkbox,true
        local waves = {{offset = 0.675, {amplitude = 0.325}}}
        local channels = {{{wave = 1, gain = 255}}, {{wave = 1, gain = 70}}, {{wave = 1, gain = 12}}}
        function setup() end
        function loop()
          local phase = luxio_millis() / 1800 * luxio_get_parameter("Speed") / 50
          local pulse = luxio_get_parameter("Pulse")
          local spacing = luxio_get_parameter("Spacing")
          if pulse then
            waves[1][1].phase, waves[1][1].step = phase, 1 / spacing
            luxio_render_waves(waves, channels)
          else
            luxio_fill(255, 70, 12)
          end
        end
    """.trimIndent()),
    AnimationPreset("Tidal", """
        -- @preview 053159,0f8cff
        -- @parameter Speed,Slider,50,0,100
        -- @parameter Spacing,Number,8,1,64
        -- @parameter Pulse,Checkbox,true
        local waves = {{offset = 0.675, {amplitude = 0.325}}}
        local channels = {{{wave = 1, gain = 15}}, {{wave = 1, gain = 140}}, {{wave = 1, gain = 255}}}
        function setup() end
        function loop()
          local phase = luxio_millis() / 1800 * luxio_get_parameter("Speed") / 50
          local pulse = luxio_get_parameter("Pulse")
          local spacing = luxio_get_parameter("Spacing")
          if pulse then
            waves[1][1].phase, waves[1][1].step = phase, 1 / spacing
            luxio_render_waves(waves, channels)
          else
            luxio_fill(15, 140, 255)
          end
        end
    """.trimIndent()),
    AnimationPreset("Christmas", """
        -- @preview 55381b,ffbe69
        -- Soft, independent warm-white twinkles over a steady glow.
        function loop()
          local t = luxio_millis() / 1000
          local sin, floor, max = math.sin, math.floor, math.max
          -- Negative twinkles are just the background; fill those pixels in bulk.
          luxio_fill(40, 30, 16)
          for i = 0, luxio_get_pixel_count() - 1 do
            local phase = (sin(i * 127.1 + 311.7) * 43758.5453) % 6.28318
            local wave = sin(t * (0.8 + (i * 0.618034 % 1) * 1.2) + phase)
            if wave > 0 then
              local squared = wave * wave
              local fourth = squared * squared
              local glow = 0.16 + 0.84 * fourth * fourth
              luxio_set_pixel_color(i, floor(255 * glow), floor(190 * glow), floor(105 * glow))
            end
          end
        end
    """.trimIndent()),
    AnimationPreset("Knight Rider", """
        -- @preview 230000,ff0000
        -- A red scanner with a fading tail, sweeping out and back.
        function loop()
          local count = luxio_get_pixel_count()
          local phase = (luxio_millis() / 1300) % 2
          local direction = phase < 1 and 1 or -1
          local head = (phase < 1 and phase or 2 - phase) * (count - 1)
          local tail = math.max(1, count * 0.1)
          local floor, abs, max, exp = math.floor, math.abs, math.max, math.exp
          luxio_fill()
          if count == 1 then
            luxio_fill(floor(255 * (0.35 + 0.65 * (1 + math.cos(phase * math.pi)) / 2)), 0, 0)
            return
          end
          -- Draw the head, then walk down the visible trail with one decay multiply.
          for i = max(0, math.ceil(head - 1.5)), math.min(count - 1, floor(head + 1.5)) do
            luxio_set_pixel_color(i, floor(255 * max(0, 1 - abs(head - i) / 1.5)), 0, 0)
          end
          local i = direction == 1 and floor(head) or math.ceil(head)
          local glow = 0.7 * exp(-abs(head - i) / tail)
          local decay = exp(-1 / tail)
          while i >= 0 and i < count and glow >= 1 / 255 do
            luxio_set_pixel_color(i, floor(255 * max(glow, 1 - abs(head - i) / 1.5)), 0, 0)
            glow = glow * decay
            i = i - direction
          end
        end
    """.trimIndent()),
    AnimationPreset("Fireplace", """
        -- @preview 730c00,ff5f0a,ffaf23
        -- Uneven pockets of glowing embers and dancing amber flames.
        local waves = {{offset = 0.5,
          {amplitude = 0.22, step = 0.7},
          {amplitude = 0.12, step = -1.3},
          {amplitude = 0.06, step = 2.1}}}
        local channels = {
          {offset = 120, {wave = 1, gain = 135}},
          {offset = 12, {wave = 1, gain = 180, power = 3}},
          {{wave = 1, gain = 35, power = 8}}}
        function loop()
          local t = luxio_millis() / 1000
          waves[1][1].phase, waves[1][2].phase, waves[1][3].phase = t * 2.8, t * 7.1, t * 13.3
          luxio_render_waves(waves, channels)
        end
    """.trimIndent()),
    AnimationPreset("Candle", """
        -- @preview aa5016,ffa537
        -- A gentle candle flame, using dedicated white LEDs when available.
        local color_type = luxio_get_color_type()
        local waves = {{{amplitude = 0.02, step = 2.4}}}
        local channels
        if color_type == "RGBCCT" then channels = {[5] = {{wave = 1, gain = 255}}}
        elseif color_type == "RGBW" then channels = {{{wave = 1, gain = 207}}, {{wave = 1, gain = 107}}, {}, {{wave = 1, gain = 48}}}
        else channels = {{{wave = 1, gain = 255}}, {{wave = 1, gain = 155}}, {{wave = 1, gain = 48}}} end
        function loop()
          local t = luxio_millis() / 1000
          local sin, floor, max = math.sin, math.floor, math.max
          local flame = 0.82 + 0.07 * sin(t * 3.1) + 0.05 * sin(t * 7.7)
            + 0.03 * sin(t * 13.9) - 0.18 * max(0, sin(t * 0.73)) ^ 16
          waves[1].offset, waves[1][1].phase = flame, t * 5.3
          luxio_render_waves(waves, channels)
        end
    """.trimIndent()),
    AnimationPreset("Cyberpunk", """
        -- @preview 0f0032,ff00a0,00dcff
        -- Cyan and magenta neon bands drifting through deep violet.
        local waves = {
          {positive = true, {}},
          {positive = true, {}},
          {offset = 0.85, {amplitude = 0.15, step = 1.7}}}
        local channels = {
          {offset = 12, multiply = 3, {wave = 2, gain = 243, power = 6}},
          {multiply = 3, {wave = 1, gain = 220, power = 6}},
          {offset = 35, multiply = 3, {wave = 2, gain = 130, power = 6}, {wave = 1, gain = 220, power = 6}}}
        function loop()
          local t = luxio_millis() / 1000
          local count = luxio_get_pixel_count()
          waves[1][1].phase, waves[1][1].step = -t * 1.8, 18.85 / count
          waves[2][1].phase, waves[2][1].step = t * 1.3 + 2, 12.57 / count
          waves[3][1].phase = t * 8
          luxio_render_waves(waves, channels)
        end
    """.trimIndent())
)
