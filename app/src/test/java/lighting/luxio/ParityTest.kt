package lighting.luxio

import org.junit.Assert.*
import org.junit.Test

class ParityTest {
    @Test fun bluetoothFramesSurviveEverySplitBoundaryAndRejectTrailingData() {
        val payload=ByteArray(900) { (it*31).toByte() }; val packet=BluetoothFrame.encode(payload)
        for(split in 1 until packet.size) { val frame=BluetoothFrame(); assertNull(frame.append(packet.copyOfRange(0,split))); assertArrayEquals(payload,frame.append(packet.copyOfRange(split,packet.size))) }
        val frame=BluetoothFrame(); packet.dropLast(1).forEach { assertNull(frame.append(byteArrayOf(it))) }; assertArrayEquals(payload,frame.append(byteArrayOf(packet.last())))
        assertThrows(IllegalArgumentException::class.java) { BluetoothFrame().append(packet+byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { BluetoothFrame().append("LXP1".toByteArray()+byteArrayOf(127,0,0,0)) }
        assertThrows(IllegalArgumentException::class.java) { BluetoothFrame().append("BAD!".toByteArray()+byteArrayOf(0,0,0,1)) }
    }
    @Test fun bluetoothMaximumFrameIsBoundedAndReusable() {
        val frame=BluetoothFrame(); val bytes=ByteArray(32768) { 4 }
        assertArrayEquals(bytes,frame.append(BluetoothFrame.encode(bytes)))
        assertArrayEquals(byteArrayOf(1),frame.append(BluetoothFrame.encode(byteArrayOf(1))))
        assertThrows(IllegalArgumentException::class.java) { BluetoothFrame.encode(ByteArray(32769)) }
        assertThrows(IllegalArgumentException::class.java) { BluetoothFrame.encode(byteArrayOf()) }
    }
    @Test fun allCatalogAnimationsHaveCanonicalModeAndValidParameters() {
        assertEquals(10,builtInAnimations.size); assertEquals(24,gradientPresets.size); assertEquals(24,colorPresets.size)
        builtInAnimations.forEach { item -> assertEquals(2,Proto.decode(item.proto().bytes.toByteArray()).number(4)); assertTrue(item.colors.size in 2..8) }
        val rainbow=builtInAnimations.first { it.name=="Rainbow" }
        assertEquals(listOf("Speed","Period","Reverse"),rainbow.parameters.map { it.name })
        assertTrue(rainbow.parameters[1].numberInput); assertTrue(rainbow.parameters[2].boolean)
    }
    @Test fun simulatorRemembersSettingsAndAllLibraries() {
        val device=SimulatorDevice()
        val config=device.state.copy(name="Desk",strip="WS2805",count=1024,relayEnabled=true,relayGpio=18,wifiEnabled=false,matterEnabled=false)
        device.request(Proto().uint(1,1).uint(2,3).data(5,config.config().bytes.toByteArray()).bytes.toByteArray())
        for(kind in 0..2) {
            val item=when(kind) { 0 -> colorPresets.first(); 1 -> gradientPresets.first(); else -> builtInAnimations.first() }
            device.request(Proto().uint(1,2).uint(2,listOf(27L,20L,7L)[kind]).data(listOf(14,11,7)[kind],item.proto().bytes.toByteArray()).bytes.toByteArray())
        }
        val restored=SimulatorDevice(device.snapshot)
        assertEquals("Desk",restored.state.name); assertEquals(1024,restored.state.count); assertTrue(restored.state.relayEnabled); assertFalse(restored.state.wifiEnabled); assertFalse(restored.state.matterEnabled)
        for(kind in 0..2) { val response=restored.request(Proto().uint(1,3).uint(2,listOf(25L,18L,5L)[kind]).bytes.toByteArray()); assertEquals(1,response.messages(listOf(18,15,4)[kind]).size) }
    }
    @Test fun unsavedAnimationsKeepAnimationModeAndDoNotWriteLibrary() {
        val device=SimulatorDevice()
        for(name in listOf("Tidal","Christmas","Fireplace","Cyberpunk")) {
            val item=builtInAnimations.first { it.name==name }
            device.request(Proto().uint(1,1).uint(2,31).data(7,item.proto().bytes.toByteArray()).bytes.toByteArray())
            val state=LightState.from(device.request(Proto().uint(1,2).uint(2,0).bytes.toByteArray()).message(3))
            assertEquals(2,state.mode); assertEquals(name,state.animationName); assertEquals("",state.animation)
        }
        assertTrue(device.request(Proto().uint(1,3).uint(2,5).bytes.toByteArray()).messages(4).isEmpty())
    }
    @Test fun parameterPatchesPreserveRevisionAndRejectStaleAnimation() {
        val device=SimulatorDevice(); val item=builtInAnimations.first()
        device.request(Proto().uint(1,1).uint(2,31).data(7,item.proto().bytes.toByteArray()).bytes.toByteArray())
        val revision=device.state.revision
        val patch=Proto().uint(1,2).uint(2,32).data(7,Proto().text(2,item.name).bytes.toByteArray()).data(17,item.parameters.first().copy(value=70.0).proto().bytes.toByteArray())
        device.request(patch.bytes.toByteArray()); assertEquals(revision,device.state.revision); assertEquals(70.0,device.state.parameters.first().value,0.0)
        assertThrows(IllegalArgumentException::class.java) { device.request(Proto().uint(1,3).uint(2,32).data(7,Proto().text(2,"Stale").bytes.toByteArray()).bytes.toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { device.request(Proto().uint(1,3).uint(2,32).data(17,item.parameters.first().copy(value=Double.NaN).proto().bytes.toByteArray()).bytes.toByteArray()) }
    }
    @Test fun savedRenamePreservesSixChannelsAndGradientMetadata() {
        val device=SimulatorDevice(); val original=gradientPresets.first().copy(name="Mine",metadata="{\"editor\":1}")
        val created=device.request(Proto().uint(1,1).uint(2,20).data(11,original.proto().bytes.toByteArray()).bytes.toByteArray()).message(16)
        val renamed=LibraryItem.from(created,1).copy(name="Evening")
        device.request(Proto().uint(1,2).uint(2,21).data(11,renamed.proto().bytes.toByteArray()).bytes.toByteArray())
        device.request(Proto().uint(1,3).uint(2,23).text(12,renamed.id).bytes.toByteArray())
        assertEquals(original.colors,device.state.gradient.colors); assertEquals(original.metadata,device.state.gradient.metadata); assertEquals("Evening",device.state.gradient.name)
        device.request(Proto().uint(1,4).uint(2,22).text(12,renamed.id).bytes.toByteArray())
        assertEquals("",device.state.gradientId); assertEquals(1,device.state.mode)
    }
    @Test fun whiteChannelsRespectStripCapabilities() {
        val device=SimulatorDevice()
        assertEquals("WS2805",device.state.strip)
        for(channels in listOf(listOf(0,0,0,0,255,0), listOf(0,0,0,0,0,255))) {
            device.request(Proto().uint(1,1).uint(2,1).data(3,LedColor(channels).proto().bytes.toByteArray()).bytes.toByteArray())
            assertEquals(channels,device.state.color.channels)
            assertEquals(channels,SimulatorDevice(device.snapshot).state.color.channels)
        }
        device.request(Proto().uint(1,1).uint(2,3).data(5,device.state.copy(strip="SK6812").config().bytes.toByteArray()).bytes.toByteArray())
        device.request(Proto().uint(1,1).uint(2,1).data(3,LedColor(listOf(0,0,0,255,0,0)).proto().bytes.toByteArray()).bytes.toByteArray())
        assertEquals(255,device.state.color.channels[3])
        assertThrows(IllegalArgumentException::class.java) { device.request(Proto().uint(1,2).uint(2,1).data(3,LedColor(listOf(0,0,0,0,255,0)).proto().bytes.toByteArray()).bytes.toByteArray()) }
    }
    @Test fun invalidAnimationHeadersAreRejected() {
        for(source in listOf("-- @parameter Speed,Slider,5,10,1\nfunction loop() end", "-- @parameter X,Checkbox,maybe\nfunction loop() end", "-- @parameter X,Number,1,0,2\n-- @parameter X,Number,1,0,2\nfunction loop() end", "function loop() end\n-- @parameter X,Number,1,0,2")) assertThrows(IllegalArgumentException::class.java) { animationItem("Bad",source) }
    }
}
