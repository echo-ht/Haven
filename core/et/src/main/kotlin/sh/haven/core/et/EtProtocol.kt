package sh.haven.core.et

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ET wire protocol encoding/decoding.
 *
 * Two framing formats:
 * - Handshake: 8-byte little-endian length + protobuf bytes (unencrypted)
 * - Data: 4-byte big-endian length + [enc_flag][header][payload] (encrypted)
 */
object EtProtocol {

    const val PROTOCOL_VERSION = 6

    // Packet header types
    const val HEADER_HEARTBEAT: Byte = 254.toByte()
    const val HEADER_INITIAL_PAYLOAD: Byte = 253.toByte()
    const val HEADER_INITIAL_RESPONSE: Byte = 252.toByte()
    const val HEADER_KEEP_ALIVE: Byte = 0
    const val HEADER_TERMINAL_BUFFER: Byte = 1
    const val HEADER_TERMINAL_INFO: Byte = 2

    // ConnectResponse status values
    const val STATUS_NEW_CLIENT = 1
    const val STATUS_RETURNING_CLIENT = 2
    const val STATUS_INVALID_KEY = 3
    const val STATUS_MISMATCHED_PROTOCOL = 4

    // --- Handshake framing (8-byte LE length prefix, unencrypted) ---

    /**
     * Write a handshake message: 8-byte LE length + payload bytes.
     */
    fun writeHandshakeMessage(out: OutputStream, payload: ByteArray) {
        val lenBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        lenBuf.putLong(payload.size.toLong())
        out.write(lenBuf.array())
        out.write(payload)
        out.flush()
    }

    /**
     * Read a handshake message: 8-byte LE length + payload bytes.
     */
    fun readHandshakeMessage(input: InputStream): ByteArray {
        val lenBuf = readExact(input, 8)
        val length = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).long
        require(length in 0..128 * 1024 * 1024) { "Invalid handshake length: $length" }
        if (length == 0L) return ByteArray(0)
        return readExact(input, length.toInt())
    }

    // --- Data framing (4-byte BE length prefix) ---

    /**
     * Write a data packet: 4-byte BE length + [enc_flag][header][payload].
     */
    fun writeDataPacket(out: OutputStream, encrypted: Boolean, header: Byte, payload: ByteArray) {
        val totalLen = 1 + 1 + payload.size // enc_flag + header + payload
        val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        lenBuf.putInt(totalLen)
        out.write(lenBuf.array())
        out.write(if (encrypted) 1 else 0)
        out.write(header.toInt())
        out.write(payload)
        out.flush()
    }

    /**
     * Read a data packet. Returns (encrypted, header, payload).
     */
    fun readDataPacket(input: InputStream): Triple<Boolean, Byte, ByteArray> {
        val lenBuf = readExact(input, 4)
        val length = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).int
        require(length in 2..128 * 1024 * 1024) { "Invalid data packet length: $length" }
        val data = readExact(input, length)
        val encrypted = data[0].toInt() != 0
        val header = data[1]
        val payload = data.copyOfRange(2, data.size)
        return Triple(encrypted, header, payload)
    }

    // --- Minimal protobuf encoding (hand-coded, no protobuf dependency) ---
    // ET uses proto2 with optional fields. We encode only what's needed.

    /**
     * Encode ConnectRequest { clientId = id, version = ver }
     * Field 1 (clientId): tag = 0x0a (field 1, wire type 2 = length-delimited)
     * Field 2 (version): tag = 0x10 (field 2, wire type 0 = varint)
     */
    fun encodeConnectRequest(clientId: String, version: Int): ByteArray {
        val idBytes = clientId.toByteArray()
        val buf = mutableListOf<Byte>()
        // field 1: string
        buf.add(0x0a)
        buf.addAll(encodeVarint(idBytes.size.toLong()))
        buf.addAll(idBytes.toList())
        // field 2: int32
        buf.add(0x10)
        buf.addAll(encodeVarint(version.toLong()))
        return buf.toByteArray()
    }

    /**
     * Decode ConnectResponse { status = int, error = string? }
     * Field 1 (status): tag = 0x08 (field 1, wire type 0 = varint)
     * Field 2 (error): tag = 0x12 (field 2, wire type 2 = length-delimited)
     */
    fun decodeConnectResponse(data: ByteArray): Pair<Int, String?> {
        var status = 0
        var error: String? = null
        var pos = 0
        while (pos < data.size) {
            val tag = data[pos++].toInt() and 0xFF
            when (tag) {
                0x08 -> { // field 1, varint
                    val (value, newPos) = decodeVarint(data, pos)
                    status = value.toInt()
                    pos = newPos
                }
                0x12 -> { // field 2, length-delimited
                    val (len, newPos) = decodeVarint(data, pos)
                    error = String(data, newPos, len.toInt())
                    pos = newPos + len.toInt()
                }
                else -> {
                    // Skip unknown field
                    pos = skipField(data, pos, tag)
                }
            }
        }
        return Pair(status, error)
    }

    /**
     * Encode InitialPayload { jumphost = false }
     * Field 1 (jumphost): tag = 0x08 (field 1, wire type 0 = varint)
     */
    fun encodeInitialPayload(jumphost: Boolean = false): ByteArray {
        val buf = mutableListOf<Byte>()
        buf.add(0x08)
        buf.add(if (jumphost) 1 else 0)
        return buf.toByteArray()
    }

    /**
     * Decode InitialResponse { error = string? }
     * Field 1 (error): tag = 0x0a (field 1, wire type 2 = length-delimited)
     */
    fun decodeInitialResponse(data: ByteArray): String? {
        var error: String? = null
        var pos = 0
        while (pos < data.size) {
            val tag = data[pos++].toInt() and 0xFF
            when (tag) {
                0x0a -> {
                    val (len, newPos) = decodeVarint(data, pos)
                    error = String(data, newPos, len.toInt())
                    pos = newPos + len.toInt()
                }
                else -> pos = skipField(data, pos, tag)
            }
        }
        return error
    }

    /**
     * Encode TerminalBuffer { buffer = bytes }
     * Field 1 (buffer): tag = 0x0a (field 1, wire type 2 = length-delimited)
     */
    fun encodeTerminalBuffer(buffer: ByteArray): ByteArray {
        val buf = mutableListOf<Byte>()
        buf.add(0x0a)
        buf.addAll(encodeVarint(buffer.size.toLong()))
        buf.addAll(buffer.toList())
        return buf.toByteArray()
    }

    /**
     * Decode TerminalBuffer { buffer = bytes }
     */
    fun decodeTerminalBuffer(data: ByteArray): ByteArray {
        var buffer = ByteArray(0)
        var pos = 0
        while (pos < data.size) {
            val tag = data[pos++].toInt() and 0xFF
            when (tag) {
                0x0a -> {
                    val (len, newPos) = decodeVarint(data, pos)
                    buffer = data.copyOfRange(newPos, newPos + len.toInt())
                    pos = newPos + len.toInt()
                }
                else -> pos = skipField(data, pos, tag)
            }
        }
        return buffer
    }

    /**
     * Encode TerminalInfo { row = r, column = c, width = w, height = h }
     * Field 2 (row): tag = 0x10 (field 2, varint)
     * Field 3 (column): tag = 0x18 (field 3, varint)
     * Field 4 (width): tag = 0x20 (field 4, varint)
     * Field 5 (height): tag = 0x28 (field 5, varint)
     */
    fun encodeTerminalInfo(rows: Int, cols: Int, width: Int = 0, height: Int = 0): ByteArray {
        val buf = mutableListOf<Byte>()
        // row
        buf.add(0x10)
        buf.addAll(encodeVarint(rows.toLong()))
        // column
        buf.add(0x18)
        buf.addAll(encodeVarint(cols.toLong()))
        // width (pixels)
        buf.add(0x20)
        buf.addAll(encodeVarint(width.toLong()))
        // height (pixels)
        buf.add(0x28)
        buf.addAll(encodeVarint(height.toLong()))
        return buf.toByteArray()
    }

    // --- Protobuf varint helpers ---

    private fun encodeVarint(value: Long): List<Byte> {
        var v = value
        val result = mutableListOf<Byte>()
        while (v > 0x7F) {
            result.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        result.add((v and 0x7F).toByte())
        return result
    }

    private fun decodeVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < data.size) {
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            require(shift < 64) { "Varint too long" }
        }
        return Pair(result, pos)
    }

    private fun skipField(data: ByteArray, pos: Int, tag: Int): Int {
        return when (tag and 0x07) {
            0 -> { // varint
                val (_, newPos) = decodeVarint(data, pos)
                newPos
            }
            1 -> pos + 8 // 64-bit
            2 -> { // length-delimited
                val (len, newPos) = decodeVarint(data, pos)
                newPos + len.toInt()
            }
            5 -> pos + 4 // 32-bit
            else -> throw IllegalArgumentException("Unknown wire type in tag $tag")
        }
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(buf, offset, length - offset)
            if (n < 0) throw java.io.EOFException("Unexpected EOF (expected $length bytes, got $offset)")
            offset += n
        }
        return buf
    }
}
