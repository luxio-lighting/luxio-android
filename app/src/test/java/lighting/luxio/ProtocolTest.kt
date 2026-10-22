package lighting.luxio

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun decodesTypeScriptFixtureAndEncodesMatchingRequest() {
        // Generated with the canonical TS codec: UTF-8 name, explicit zero,
        // a two-byte varint channel and the independent RGBCCT white channels.
        val fixture = "CHsaggEKQGFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEQDBoZCglEZXNrIPCfkqESBldTMjgwNRg8IBAoZCAAKg4I/wEQABgAIAAoETCBATIAOg0xOTIuMTY4LjEuMjMx"
        val response = Proto.decode(Base64.getDecoder().decode(fixture))
        assertEquals(123, response.number(1))
        val state = LightState.from(response.message(3))
        assertEquals("Desk 💡", state.name); assertEquals(60, state.count); assertEquals(0, state.brightness)
        val color = response.message(3).message(5)
        assertEquals(listOf(255,0,0,0,17,129), (1..6).map { color.number(it) })
        val channels = Proto(); listOf(255,0,0,0,17,129).forEachIndexed { i, v -> channels.uint(i+1, v.toLong()) }
        val request = Proto().uint(1,123).uint(2,1).data(3,channels.bytes.toByteArray())
        assertEquals("CHsQARoOCP8BEAAYACAAKBEwgQE=", Base64.getEncoder().encodeToString(request.bytes.toByteArray()))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedLength() {
        Proto.decode(byteArrayOf(0x1a, 0xff.toByte()))
    }
}
