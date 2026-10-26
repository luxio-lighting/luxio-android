package lighting.luxio

import org.junit.Assert.*
import org.junit.Test

class NativeAnimationTest {
    @Test fun allPresetsRunOnEveryStripType() {
        for(count in listOf(1,60,1024)) for(strip in listOf(2812,2814,2805,6812)) for(item in builtInAnimations) {
            val handle=AnimationRuntime.create(count,strip); assertNotEquals(0L,handle)
            try {
                item.parameters.forEach { assertTrue(AnimationRuntime.parameter(handle,it.name,it.boolean,it.value)) }
                assertTrue("${item.name}: ${AnimationRuntime.error(handle)}",AnimationRuntime.start(handle,item.source.toByteArray()))
                for(time in listOf(0,100,1500)) assertNotNull("${item.name}: ${AnimationRuntime.error(handle)}",AnimationRuntime.frame(handle,time,count))
            } finally { AnimationRuntime.destroy(handle) }
        }
    }
    @Test fun candleUsesDedicatedWhite() {
        val source=builtInAnimations.first { it.name=="Candle" }.source
        for(strip in listOf(6812,2814,2805)) {
            val handle=AnimationRuntime.create(60,strip)
            try { assertTrue(AnimationRuntime.start(handle,source.toByteArray())); val frame=AnimationRuntime.frame(handle,100,60)!!; assertTrue((frame[if(strip==2805) 4 else 3].toInt() and 255)>0); if(strip==2805) assertEquals(0,frame[0].toInt()) }
            finally { AnimationRuntime.destroy(handle) }
        }
    }
    @Test fun supports1024LedsAndParameterChanges() {
        val item=builtInAnimations.first(); val handle=AnimationRuntime.create(1024,2812)
        try {
            item.parameters.forEach { AnimationRuntime.parameter(handle,it.name,it.boolean,it.value) }
            assertTrue(AnimationRuntime.start(handle,item.source.toByteArray()))
            assertEquals(6144,AnimationRuntime.frame(handle,100,1024)!!.size)
            assertTrue(AnimationRuntime.parameter(handle,"Reverse",true,1.0)); assertNotNull(AnimationRuntime.frame(handle,200,1024))
        } finally { AnimationRuntime.destroy(handle) }
    }
    @Test fun hostileScriptsAreBoundedAndCannotAccessHost() {
        for(source in listOf("function loop() while true do end end", "function loop() luxio_set_pixel_color(60,255,0,0) end", "function loop() os.execute('touch /tmp/no') end", "function loop() local t={} for i=1,100000 do t[i]=string.rep('x',1000) end end")) {
            val handle=AnimationRuntime.create(60,2812)
            try { assertTrue(AnimationRuntime.start(handle,source.toByteArray())); assertNull(AnimationRuntime.frame(handle,0,60)); assertTrue(AnimationRuntime.error(handle).isNotEmpty()) }
            finally { AnimationRuntime.destroy(handle) }
        }
    }
}
