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
          for i = 0, count - 1 do
            local h = (time + i / count) % 1 * 6
            local sector = math.floor(h)
            local up = math.floor((h - sector) * 255)
            local down = 255 - up
            local r, g, b = 0, 0, 0
            if sector == 0 then r, g = 255, up
            elseif sector == 1 then r, g = down, 255
            elseif sector == 2 then g, b = 255, up
            elseif sector == 3 then g, b = down, 255
            elseif sector == 4 then r, b = up, 255
            else r, b = 255, down end
            luxio_set_pixel_color(i, r, g, b)
          end
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
          for i = 0, count - 1 do
            local h = (time) % 1 * 6
            local sector = math.floor(h)
            local up = math.floor((h - sector) * 255)
            local down = 255 - up
            local r, g, b = 0, 0, 0
            if sector == 0 then r, g = 255, up
            elseif sector == 1 then r, g = down, 255
            elseif sector == 2 then g, b = 255, up
            elseif sector == 3 then g, b = down, 255
            elseif sector == 4 then r, b = up, 255
            else r, b = 255, down end
            luxio_set_pixel_color(i, r, g, b)
          end
        end
    """.trimIndent()),
    AnimationPreset("Aurora", """
        -- @preview 2d1859,8246ff
        -- @parameter Speed,Slider,50,0,100
        -- @parameter Spacing,Number,8,1,64
        -- @parameter Pulse,Checkbox,true
        function setup() end
        function loop()
          local phase = luxio_millis() / 1800 * luxio_get_parameter("Speed") / 50
          for i = 0, luxio_get_pixel_count() - 1 do
            local t = luxio_get_parameter("Pulse") and (0.35 + 0.65 * (math.sin(i / luxio_get_parameter("Spacing") + phase) + 1) / 2) or 1
            luxio_set_pixel_color(i, math.floor(130 * t), math.floor(70 * t), math.floor(255 * t))
          end
        end
    """.trimIndent()),
    AnimationPreset("Ember", """
        -- @preview 591804,ff460c
        -- @parameter Speed,Slider,50,0,100
        -- @parameter Spacing,Number,8,1,64
        -- @parameter Pulse,Checkbox,true
        function setup() end
        function loop()
          local phase = luxio_millis() / 1800 * luxio_get_parameter("Speed") / 50
          for i = 0, luxio_get_pixel_count() - 1 do
            local t = luxio_get_parameter("Pulse") and (0.35 + 0.65 * (math.sin(i / luxio_get_parameter("Spacing") + phase) + 1) / 2) or 1
            luxio_set_pixel_color(i, math.floor(255 * t), math.floor(70 * t), math.floor(12 * t))
          end
        end
    """.trimIndent()),
    AnimationPreset("Tidal", """
        -- @preview 053159,0f8cff
        -- @parameter Speed,Slider,50,0,100
        -- @parameter Spacing,Number,8,1,64
        -- @parameter Pulse,Checkbox,true
        function setup() end
        function loop()
          local phase = luxio_millis() / 1800 * luxio_get_parameter("Speed") / 50
          for i = 0, luxio_get_pixel_count() - 1 do
            local t = luxio_get_parameter("Pulse") and (0.35 + 0.65 * (math.sin(i / luxio_get_parameter("Spacing") + phase) + 1) / 2) or 1
            luxio_set_pixel_color(i, math.floor(15 * t), math.floor(140 * t), math.floor(255 * t))
          end
        end
    """.trimIndent()),
    AnimationPreset("Christmas", """
        -- @preview 55381b,ffbe69
        -- Soft, independent warm-white twinkles over a steady glow.
        function loop()
          local t = luxio_millis() / 1000
          local sin, floor, max = math.sin, math.floor, math.max
          for i = 0, luxio_get_pixel_count() - 1 do
            local phase = (sin(i * 127.1 + 311.7) * 43758.5453) % 6.28318
            local wave = max(0, sin(t * (0.8 + (i * 0.618034 % 1) * 1.2) + phase))
            local glow = 0.16 + 0.84 * wave ^ 8
            luxio_set_pixel_color(i, floor(255 * glow), floor(190 * glow), floor(105 * glow))
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
          for i = 0, count - 1 do
            local distance = (head - i) * direction
            local glow = max(0, 1 - abs(head - i) / 1.5)
            if distance >= 0 then glow = max(glow, 0.7 * exp(-distance / tail)) end
            if count == 1 then glow = 0.35 + 0.65 * (1 + math.cos(phase * math.pi)) / 2 end
            luxio_set_pixel_color(i, floor(255 * glow), 0, 0)
          end
        end
    """.trimIndent()),
    AnimationPreset("Fireplace", """
        -- @preview 730c00,ff5f0a,ffaf23
        -- Uneven pockets of glowing embers and dancing amber flames.
        function loop()
          local t = luxio_millis() / 1000
          local sin, floor = math.sin, math.floor
          for i = 0, luxio_get_pixel_count() - 1 do
            local heat = 0.5 + 0.22 * sin(t * 2.8 + i * 0.7)
              + 0.12 * sin(t * 7.1 - i * 1.3) + 0.06 * sin(t * 13.3 + i * 2.1)
            luxio_set_pixel_color(i, floor(120 + 135 * heat), floor(12 + 180 * heat ^ 3), floor(35 * heat ^ 8))
          end
        end
    """.trimIndent()),
    AnimationPreset("Candle", """
        -- @preview aa5016,ffa537
        -- A calm amber flame with gentle flicker and occasional soft dips.
        function loop()
          local t = luxio_millis() / 1000
          local sin, floor, max = math.sin, math.floor, math.max
          local flame = 0.82 + 0.07 * sin(t * 3.1) + 0.05 * sin(t * 7.7)
            + 0.03 * sin(t * 13.9) - 0.18 * max(0, sin(t * 0.73)) ^ 16
          for i = 0, luxio_get_pixel_count() - 1 do
            local glow = flame + 0.02 * sin(i * 2.4 + t * 5.3)
            luxio_set_pixel_color(i, floor(255 * glow), floor(155 * glow), floor(48 * glow))
          end
        end
    """.trimIndent()),
    AnimationPreset("Cyberpunk", """
        -- @preview 0f0032,ff00a0,00dcff
        -- Cyan and magenta neon bands drifting through deep violet.
        function loop()
          local t = luxio_millis() / 1000
          local count = luxio_get_pixel_count()
          local sin, floor, max = math.sin, math.floor, math.max
          for i = 0, count - 1 do
            local x = i / count
            local cyan = max(0, sin(x * 18.85 - t * 1.8)) ^ 6
            local pink = max(0, sin(x * 12.57 + t * 1.3 + 2)) ^ 6
            local shimmer = 0.85 + 0.15 * sin(t * 8 + i * 1.7)
            luxio_set_pixel_color(i, floor((12 + 243 * pink) * shimmer), floor(220 * cyan * shimmer), floor(math.min(255, 35 + 130 * pink + 220 * cyan) * shimmer))
          end
        end
    """.trimIndent())
)
