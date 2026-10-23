package lighting.luxio

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun decodesTypeScriptFixtureAndEncodesMatchingRequest() {
        // Generated with the canonical TS codec: UTF-8 name, power off with nonzero brightness,
        // a two-byte varint channel and the independent RGBCCT white channels.
        val fixture = "CHsahQEKQGFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEQCxoZCglEZXNrIPCfkqESBldTMjgwNRg8IBAoZCCAAioOCP8BEAAYACAAKBEwgQEyADoNMTkyLjE2OC4xLjIzMUgA"
        val response = Proto.decode(Base64.getDecoder().decode(fixture))
        assertEquals(123, response.number(1))
        val state = LightState.from(response.message(3))
        assertEquals("Desk 💡", state.name); assertEquals(60, state.count); assertEquals(256, state.brightness); assertFalse(state.on)
        val color = response.message(3).message(5)
        assertEquals(listOf(255,0,0,0,17,129), (1..6).map { color.number(it) })
        val channels = Proto(); listOf(255,0,0,0,17,129).forEachIndexed { i, v -> channels.uint(i+1, v.toLong()) }
        val request = Proto().uint(1,123).uint(2,1).data(3,channels.bytes.toByteArray())
        assertEquals("CHsQARoOCP8BEAAYACAAKBEwgQE=", Base64.getEncoder().encodeToString(request.bytes.toByteArray()))
    }
    @Test fun controlTypesSurviveStateAndPatchEncoding() {
        val slider = Proto().text(1, "Speed").real(3, 50.0).real(5, 0.0).real(6, 100.0)
        val number = Proto().text(1, "Spacing").real(3, 8.0).real(5, 1.0).real(6, 64.0).uint(7, 1)
        val checkbox = Proto().text(1, "Pulse").uint(2, 1).real(3, 1.0)
        val state = Proto().uint(2, 21).data(14, slider.bytes.toByteArray()).data(14, number.bytes.toByteArray()).data(14, checkbox.bytes.toByteArray())
        val parameters = LightState.from(Proto.decode(state.bytes.toByteArray())).parameters
        assertEquals(3, parameters.size)
        assertFalse(parameters[0].numberInput); assertTrue(parameters[1].numberInput); assertTrue(parameters[2].boolean)
        val patch = Proto.decode(parameters[1].proto().bytes.toByteArray())
        assertEquals(1, patch.number(7)); assertEquals(8.0, patch.real(3), 0.0)
    }
    @Test fun powerRequestKeepsBooleanSeparate() {
        val request = Proto.decode(Proto().uint(2,15).uint(10,1).bytes.toByteArray())
        assertEquals(15, request.number(2)); assertEquals(1, request.number(10))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedLength() {
        Proto.decode(byteArrayOf(0x1a, 0xff.toByte()))
    }
}
