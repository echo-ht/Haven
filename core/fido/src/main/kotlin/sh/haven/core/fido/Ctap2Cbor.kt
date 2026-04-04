package sh.haven.core.fido

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Minimal CBOR encoder/decoder for CTAP2 authenticatorGetAssertion.
 * Only implements the subset needed: unsigned ints, byte strings,
 * text strings, maps, arrays, and booleans.
 */
object Ctap2Cbor {

    // CTAP2 command byte for authenticatorGetAssertion
    const val CMD_GET_ASSERTION: Byte = 0x02

    // CTAP2 status codes
    const val STATUS_OK: Byte = 0x00
    const val STATUS_NO_CREDENTIALS: Byte = 0x2E
    const val STATUS_ACTION_TIMEOUT: Byte = 0x27

    data class AssertionResponse(
        val authData: ByteArray,
        val signature: ByteArray,
    )

    /**
     * Encode a CTAP2 authenticatorGetAssertion command.
     * Returns the full command payload including the 0x02 command byte.
     *
     * CBOR map: {1: rpId, 2: clientDataHash, 3: [{type:"public-key", id:credentialId}], 5: {up:true}}
     */
    fun encodeGetAssertionCommand(
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CMD_GET_ASSERTION.toInt()) // command byte

        // Map with 4 entries
        encodeMapHeader(out, 4)

        // Key 1 -> rpId (text string)
        encodeUint(out, 1)
        encodeTextString(out, rpId)

        // Key 2 -> clientDataHash (byte string)
        encodeUint(out, 2)
        encodeByteString(out, clientDataHash)

        // Key 3 -> allowList: array of 1 credential descriptor
        encodeUint(out, 3)
        encodeArrayHeader(out, 1)
        // Credential descriptor: map with 2 entries {id: bytes, type: "public-key"}
        encodeMapHeader(out, 2)
        encodeTextString(out, "id")
        encodeByteString(out, credentialId)
        encodeTextString(out, "type")
        encodeTextString(out, "public-key")

        // Key 5 -> options: {up: true}
        encodeUint(out, 5)
        encodeMapHeader(out, 1)
        encodeTextString(out, "up")
        encodeBoolean(out, true)

        return out.toByteArray()
    }

    /**
     * Decode a CTAP2 authenticatorGetAssertion response.
     * Input should be the raw response WITHOUT the status byte (already checked).
     *
     * Response CBOR map keys: 1=credential, 2=authData, 3=signature
     */
    fun decodeGetAssertionResponse(data: ByteArray): AssertionResponse {
        val buf = ByteBuffer.wrap(data)
        val mapSize = readMapHeader(buf)

        var authData: ByteArray? = null
        var signature: ByteArray? = null

        for (i in 0 until mapSize) {
            val key = readUint(buf)
            when (key) {
                1 -> skipValue(buf) // credential descriptor, not needed
                2 -> authData = readByteString(buf)
                3 -> signature = readByteString(buf)
                else -> skipValue(buf) // unknown keys (4=numberOfCredentials, etc.)
            }
        }

        requireNotNull(authData) { "CTAP2 response missing authData (key 2)" }
        requireNotNull(signature) { "CTAP2 response missing signature (key 3)" }
        return AssertionResponse(authData, signature)
    }

    // --- CBOR encoding helpers ---

    private fun encodeUint(out: ByteArrayOutputStream, value: Int) {
        encodeMajor(out, 0, value)
    }

    private fun encodeByteString(out: ByteArrayOutputStream, data: ByteArray) {
        encodeMajor(out, 2, data.size)
        out.write(data)
    }

    private fun encodeTextString(out: ByteArrayOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        encodeMajor(out, 3, bytes.size)
        out.write(bytes)
    }

    private fun encodeMapHeader(out: ByteArrayOutputStream, size: Int) {
        encodeMajor(out, 5, size)
    }

    private fun encodeArrayHeader(out: ByteArrayOutputStream, size: Int) {
        encodeMajor(out, 4, size)
    }

    private fun encodeBoolean(out: ByteArrayOutputStream, value: Boolean) {
        // CBOR simple values: true = 0xF5, false = 0xF4
        out.write(if (value) 0xF5 else 0xF4)
    }

    private fun encodeMajor(out: ByteArrayOutputStream, majorType: Int, value: Int) {
        val mt = majorType shl 5
        when {
            value < 24 -> out.write(mt or value)
            value < 256 -> {
                out.write(mt or 24)
                out.write(value)
            }
            value < 65536 -> {
                out.write(mt or 25)
                out.write(value shr 8)
                out.write(value and 0xFF)
            }
            else -> {
                out.write(mt or 26)
                out.write(value shr 24)
                out.write((value shr 16) and 0xFF)
                out.write((value shr 8) and 0xFF)
                out.write(value and 0xFF)
            }
        }
    }

    // --- CBOR decoding helpers ---

    private fun readMapHeader(buf: ByteBuffer): Int {
        val initial = buf.get().toInt() and 0xFF
        val major = initial shr 5
        require(major == 5) { "Expected CBOR map, got major type $major" }
        return readAdditionalInfo(buf, initial and 0x1F)
    }

    private fun readUint(buf: ByteBuffer): Int {
        val initial = buf.get().toInt() and 0xFF
        val major = initial shr 5
        require(major == 0) { "Expected CBOR uint, got major type $major" }
        return readAdditionalInfo(buf, initial and 0x1F)
    }

    private fun readByteString(buf: ByteBuffer): ByteArray {
        val initial = buf.get().toInt() and 0xFF
        val major = initial shr 5
        require(major == 2) { "Expected CBOR byte string, got major type $major" }
        val len = readAdditionalInfo(buf, initial and 0x1F)
        val data = ByteArray(len)
        buf.get(data)
        return data
    }

    private fun readAdditionalInfo(buf: ByteBuffer, info: Int): Int = when {
        info < 24 -> info
        info == 24 -> buf.get().toInt() and 0xFF
        info == 25 -> ((buf.get().toInt() and 0xFF) shl 8) or (buf.get().toInt() and 0xFF)
        info == 26 -> ((buf.get().toInt() and 0xFF) shl 24) or
            ((buf.get().toInt() and 0xFF) shl 16) or
            ((buf.get().toInt() and 0xFF) shl 8) or
            (buf.get().toInt() and 0xFF)
        else -> throw IllegalArgumentException("Unsupported CBOR additional info: $info")
    }

    /** Skip a single CBOR value (any type). */
    private fun skipValue(buf: ByteBuffer) {
        val initial = buf.get().toInt() and 0xFF
        val major = initial shr 5
        val info = initial and 0x1F
        when (major) {
            0, 1 -> readAdditionalInfo(buf, info) // uint / negative int
            2, 3 -> { // byte string / text string
                val len = readAdditionalInfo(buf, info)
                buf.position(buf.position() + len)
            }
            4 -> { // array
                val count = readAdditionalInfo(buf, info)
                repeat(count) { skipValue(buf) }
            }
            5 -> { // map
                val count = readAdditionalInfo(buf, info)
                repeat(count) { skipValue(buf); skipValue(buf) }
            }
            7 -> {} // simple value (true, false, null) — no additional data for info < 24
        }
    }
}
