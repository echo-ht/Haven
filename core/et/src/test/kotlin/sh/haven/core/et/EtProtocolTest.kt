package sh.haven.core.et

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class EtProtocolTest {

    // --- Handshake framing ---

    @Test
    fun `handshake message roundtrip`() {
        val payload = "hello".toByteArray()
        val buf = ByteArrayOutputStream()
        EtProtocol.writeHandshakeMessage(buf, payload)

        val input = ByteArrayInputStream(buf.toByteArray())
        val result = EtProtocol.readHandshakeMessage(input)
        assertArrayEquals(payload, result)
    }

    @Test
    fun `handshake empty message`() {
        val buf = ByteArrayOutputStream()
        EtProtocol.writeHandshakeMessage(buf, ByteArray(0))

        val input = ByteArrayInputStream(buf.toByteArray())
        val result = EtProtocol.readHandshakeMessage(input)
        assertEquals(0, result.size)
    }

    @Test
    fun `handshake framing is 8-byte LE length prefix`() {
        val payload = byteArrayOf(0x41, 0x42, 0x43) // "ABC"
        val buf = ByteArrayOutputStream()
        EtProtocol.writeHandshakeMessage(buf, payload)

        val bytes = buf.toByteArray()
        // First 8 bytes = length 3 in LE
        assertEquals(11, bytes.size)
        assertEquals(3, bytes[0].toInt())
        for (i in 1..7) assertEquals(0, bytes[i].toInt())
        // Then payload
        assertEquals(0x41, bytes[8].toInt())
        assertEquals(0x42, bytes[9].toInt())
        assertEquals(0x43, bytes[10].toInt())
    }

    // --- Data packet framing ---

    @Test
    fun `data packet roundtrip`() {
        val payload = "terminal data".toByteArray()
        val buf = ByteArrayOutputStream()
        EtProtocol.writeDataPacket(buf, true, EtProtocol.HEADER_TERMINAL_BUFFER, payload)

        val input = ByteArrayInputStream(buf.toByteArray())
        val (encrypted, header, result) = EtProtocol.readDataPacket(input)
        assertTrue(encrypted)
        assertEquals(EtProtocol.HEADER_TERMINAL_BUFFER, header)
        assertArrayEquals(payload, result)
    }

    @Test
    fun `data packet framing is 4-byte BE length prefix`() {
        val payload = byteArrayOf(0x01, 0x02)
        val buf = ByteArrayOutputStream()
        EtProtocol.writeDataPacket(buf, true, 0x42, payload)

        val bytes = buf.toByteArray()
        // 4 bytes length (BE) + 1 enc_flag + 1 header + 2 payload = 8 total
        assertEquals(8, bytes.size)
        // Length = 4 (1+1+2) in big-endian
        assertEquals(0, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        assertEquals(0, bytes[2].toInt())
        assertEquals(4, bytes[3].toInt())
        // enc_flag = 1
        assertEquals(1, bytes[4].toInt())
        // header = 0x42
        assertEquals(0x42, bytes[5].toInt())
    }

    // --- ConnectRequest encoding ---

    @Test
    fun `ConnectRequest encodes correctly`() {
        val encoded = EtProtocol.encodeConnectRequest("XXXtestclient123", 6)
        // Should be valid protobuf with field 1 = string, field 2 = varint
        assertTrue(encoded.isNotEmpty())
        // Field 1 tag
        assertEquals(0x0a, encoded[0].toInt())
    }

    // --- ConnectResponse decoding ---

    @Test
    fun `ConnectResponse decodes NEW_CLIENT`() {
        // Hand-craft: field 1 (varint) = 1
        val data = byteArrayOf(0x08, 0x01)
        val (status, error) = EtProtocol.decodeConnectResponse(data)
        assertEquals(EtProtocol.STATUS_NEW_CLIENT, status)
        assertNull(error)
    }

    @Test
    fun `ConnectResponse decodes INVALID_KEY with error`() {
        // field 1 = 3, field 2 = "bad key"
        val errorBytes = "bad key".toByteArray()
        val data = byteArrayOf(0x08, 0x03, 0x12, errorBytes.size.toByte()) + errorBytes
        val (status, error) = EtProtocol.decodeConnectResponse(data)
        assertEquals(EtProtocol.STATUS_INVALID_KEY, status)
        assertEquals("bad key", error)
    }

    // --- TerminalBuffer encoding/decoding ---

    @Test
    fun `TerminalBuffer roundtrip`() {
        val original = "ls -la\n".toByteArray()
        val encoded = EtProtocol.encodeTerminalBuffer(original)
        val decoded = EtProtocol.decodeTerminalBuffer(encoded)
        assertArrayEquals(original, decoded)
    }

    @Test
    fun `TerminalBuffer empty roundtrip`() {
        val encoded = EtProtocol.encodeTerminalBuffer(ByteArray(0))
        val decoded = EtProtocol.decodeTerminalBuffer(encoded)
        assertEquals(0, decoded.size)
    }

    @Test
    fun `TerminalBuffer binary data roundtrip`() {
        val original = ByteArray(256) { it.toByte() }
        val encoded = EtProtocol.encodeTerminalBuffer(original)
        val decoded = EtProtocol.decodeTerminalBuffer(encoded)
        assertArrayEquals(original, decoded)
    }

    // --- TerminalInfo encoding ---

    @Test
    fun `TerminalInfo encodes row and column`() {
        val encoded = EtProtocol.encodeTerminalInfo(rows = 24, cols = 80)
        assertTrue(encoded.isNotEmpty())
        // Should contain field 2 tag (0x10) and field 3 tag (0x18)
        assertTrue(encoded.contains(0x10))
        assertTrue(encoded.contains(0x18))
    }

    // --- InitialPayload encoding ---

    @Test
    fun `InitialPayload encodes jumphost false`() {
        val encoded = EtProtocol.encodeInitialPayload(jumphost = false)
        // field 1 = varint 0
        assertArrayEquals(byteArrayOf(0x08, 0x00), encoded)
    }

    // --- InitialResponse decoding ---

    @Test
    fun `InitialResponse decodes empty (success)`() {
        val error = EtProtocol.decodeInitialResponse(ByteArray(0))
        assertNull(error)
    }

    @Test
    fun `InitialResponse decodes error string`() {
        val errMsg = "something failed"
        val errBytes = errMsg.toByteArray()
        val data = byteArrayOf(0x0a, errBytes.size.toByte()) + errBytes
        val error = EtProtocol.decodeInitialResponse(data)
        assertEquals(errMsg, error)
    }

    // --- Multiple data packets in sequence ---

    @Test
    fun `multiple data packets in one stream`() {
        val buf = ByteArrayOutputStream()
        EtProtocol.writeDataPacket(buf, true, EtProtocol.HEADER_TERMINAL_BUFFER, "first".toByteArray())
        EtProtocol.writeDataPacket(buf, true, EtProtocol.HEADER_KEEP_ALIVE, ByteArray(0))
        EtProtocol.writeDataPacket(buf, true, EtProtocol.HEADER_TERMINAL_BUFFER, "second".toByteArray())

        val input = ByteArrayInputStream(buf.toByteArray())
        val (_, h1, p1) = EtProtocol.readDataPacket(input)
        val (_, h2, p2) = EtProtocol.readDataPacket(input)
        val (_, h3, p3) = EtProtocol.readDataPacket(input)

        assertEquals(EtProtocol.HEADER_TERMINAL_BUFFER, h1)
        assertArrayEquals("first".toByteArray(), p1)
        assertEquals(EtProtocol.HEADER_KEEP_ALIVE, h2)
        assertEquals(0, p2.size)
        assertEquals(EtProtocol.HEADER_TERMINAL_BUFFER, h3)
        assertArrayEquals("second".toByteArray(), p3)
    }

    // --- Full encrypted packet roundtrip (crypto + protocol together) ---

    @Test
    fun `encrypted packet roundtrip with crypto`() {
        val key = "abcdefghijklmnopqrstuvwxyz012345".toByteArray()
        val writer = EtCrypto(key, EtCrypto.CLIENT_SERVER_NONCE_MSB)
        val reader = EtCrypto(key, EtCrypto.CLIENT_SERVER_NONCE_MSB)

        val plainPayload = EtProtocol.encodeTerminalBuffer("hello world".toByteArray())
        val encrypted = writer.encrypt(plainPayload)

        val buf = ByteArrayOutputStream()
        EtProtocol.writeDataPacket(buf, true, EtProtocol.HEADER_TERMINAL_BUFFER, encrypted)

        val input = ByteArrayInputStream(buf.toByteArray())
        val (isEnc, header, ciphertext) = EtProtocol.readDataPacket(input)
        assertTrue(isEnc)
        assertEquals(EtProtocol.HEADER_TERMINAL_BUFFER, header)

        val decrypted = reader.decrypt(ciphertext)
        val termData = EtProtocol.decodeTerminalBuffer(decrypted)
        assertArrayEquals("hello world".toByteArray(), termData)
    }

    private fun ByteArray.contains(b: Byte): Boolean = any { it == b }
}
