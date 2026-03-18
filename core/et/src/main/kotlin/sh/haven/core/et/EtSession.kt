package sh.haven.core.et

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "EtSession"

/**
 * Manages a TCP connection to an Eternal Terminal server using the
 * ET wire protocol (XSalsa20-Poly1305 encrypted, protobuf-framed).
 *
 * Lifecycle:
 * 1. SSH bootstrap runs externally (exec etterminal, get id/passkey)
 * 2. [start] connects TCP, sends ConnectRequest, receives ConnectResponse
 * 3. Exchanges encrypted InitialPayload/InitialResponse
 * 4. Enters steady-state: TERMINAL_BUFFER packets for stdin/stdout,
 *    TERMINAL_INFO for resize
 *
 * The protocol/crypto classes (EtProtocol, EtCrypto) are pure Kotlin/JVM
 * with no Android dependencies, suitable for extraction to a standalone library.
 */
class EtSession(
    val sessionId: String,
    val profileId: String,
    val label: String,
    private val serverHost: String,
    private val etPort: Int,
    private val clientId: String,
    private val passkey: String,
    private val onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onDisconnected: ((cleanExit: Boolean) -> Unit)? = null,
) : Closeable {

    @Volatile
    private var closed = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null

    // Crypto handlers — initialised after handshake
    private var writer: EtCrypto? = null
    private var reader: EtCrypto? = null

    // Synchronize writes to the socket
    private val writeLock = Any()

    /**
     * Start the ET transport: TCP connect, handshake, then read loop.
     */
    fun start() {
        if (closed) return
        Log.d(TAG, "Starting ET transport for $sessionId: $serverHost:$etPort")

        readJob = scope.launch {
            try {
                // Phase 1: TCP connect
                val sock = Socket()
                sock.connect(InetSocketAddress(serverHost, etPort), 10_000)
                sock.tcpNoDelay = true
                socket = sock
                outputStream = sock.getOutputStream()

                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                // Phase 2: Unencrypted handshake
                Log.d(TAG, "Sending ConnectRequest for $sessionId (clientId=${clientId.take(6)}...)")
                val request = EtProtocol.encodeConnectRequest(clientId, EtProtocol.PROTOCOL_VERSION)
                EtProtocol.writeHandshakeMessage(output, request)

                val responseBytes = EtProtocol.readHandshakeMessage(input)
                val (status, error) = EtProtocol.decodeConnectResponse(responseBytes)
                Log.d(TAG, "ConnectResponse: status=$status error=$error")

                when (status) {
                    EtProtocol.STATUS_NEW_CLIENT -> { /* expected */ }
                    EtProtocol.STATUS_RETURNING_CLIENT -> {
                        // TODO: implement recovery protocol
                        Log.w(TAG, "Returning client — recovery not yet implemented")
                    }
                    EtProtocol.STATUS_INVALID_KEY -> {
                        throw Exception("ET server rejected key (INVALID_KEY)")
                    }
                    EtProtocol.STATUS_MISMATCHED_PROTOCOL -> {
                        throw Exception("ET protocol version mismatch")
                    }
                    else -> throw Exception("Unknown ConnectResponse status: $status")
                }

                // Phase 3: Set up encryption
                val keyBytes = passkey.toByteArray(Charsets.US_ASCII)
                writer = EtCrypto(keyBytes, EtCrypto.CLIENT_SERVER_NONCE_MSB)
                reader = EtCrypto(keyBytes, EtCrypto.SERVER_CLIENT_NONCE_MSB)

                // Phase 4: Send encrypted InitialPayload
                val initialPayload = EtProtocol.encodeInitialPayload(jumphost = false)
                sendEncryptedPacket(output, EtProtocol.HEADER_INITIAL_PAYLOAD, initialPayload)

                // Read InitialResponse
                val (_, respHeader, respPayload) = EtProtocol.readDataPacket(input)
                if (respHeader == EtProtocol.HEADER_INITIAL_RESPONSE) {
                    val decrypted = reader!!.decrypt(respPayload)
                    val respError = EtProtocol.decodeInitialResponse(decrypted)
                    if (respError != null && respError.isNotEmpty()) {
                        throw Exception("ET InitialResponse error: $respError")
                    }
                    Log.d(TAG, "ET handshake complete for $sessionId")
                } else {
                    Log.w(TAG, "Unexpected header after InitialPayload: $respHeader")
                }

                // Phase 5: Steady-state read loop
                while (!closed) {
                    val (_, header, payload) = EtProtocol.readDataPacket(input)
                    val decrypted = reader!!.decrypt(payload)

                    when (header) {
                        EtProtocol.HEADER_TERMINAL_BUFFER -> {
                            val termData = EtProtocol.decodeTerminalBuffer(decrypted)
                            if (termData.isNotEmpty() && !closed) {
                                onDataReceived(termData, 0, termData.size)
                            }
                        }
                        EtProtocol.HEADER_KEEP_ALIVE -> {
                            // Respond with keepalive
                            sendEncryptedPacket(output, EtProtocol.HEADER_KEEP_ALIVE, ByteArray(0))
                        }
                        EtProtocol.HEADER_TERMINAL_INFO -> {
                            // Server-side resize info — ignore for now
                        }
                        else -> {
                            Log.d(TAG, "Ignoring ET packet header=${header.toInt() and 0xFF}")
                        }
                    }
                }

                if (!closed) {
                    Log.d(TAG, "ET transport EOF for $sessionId")
                    onDisconnected?.invoke(true)
                }
            } catch (e: Exception) {
                if (!closed) {
                    Log.e(TAG, "ET transport error for $sessionId: ${e.message}", e)
                    onDisconnected?.invoke(false)
                }
            }
        }
    }

    /**
     * Send keyboard input to the ET server as an encrypted TERMINAL_BUFFER packet.
     */
    fun sendInput(data: ByteArray) {
        if (closed) return
        val w = writer ?: return
        val out = outputStream ?: return
        scope.launch {
            try {
                val payload = EtProtocol.encodeTerminalBuffer(data)
                synchronized(writeLock) {
                    val encrypted = w.encrypt(payload)
                    EtProtocol.writeDataPacket(out, true, EtProtocol.HEADER_TERMINAL_BUFFER, encrypted)
                }
            } catch (e: Exception) {
                if (!closed) {
                    Log.e(TAG, "ET send error for $sessionId", e)
                }
            }
        }
    }

    /**
     * Notify the ET server of a terminal resize.
     */
    fun resize(cols: Int, rows: Int) {
        if (closed) return
        val w = writer ?: return
        val out = outputStream ?: return
        scope.launch {
            try {
                val payload = EtProtocol.encodeTerminalInfo(rows = rows, cols = cols)
                synchronized(writeLock) {
                    val encrypted = w.encrypt(payload)
                    EtProtocol.writeDataPacket(out, true, EtProtocol.HEADER_TERMINAL_INFO, encrypted)
                }
            } catch (e: Exception) {
                if (!closed) {
                    Log.e(TAG, "ET resize error for $sessionId", e)
                }
            }
        }
    }

    private fun sendEncryptedPacket(out: OutputStream, header: Byte, plaintext: ByteArray) {
        synchronized(writeLock) {
            val encrypted = writer!!.encrypt(plaintext)
            EtProtocol.writeDataPacket(out, true, header, encrypted)
        }
    }

    fun detach() {
        if (closed) return
        closed = true
        readJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outputStream = null
    }

    override fun close() {
        if (closed) return
        closed = true
        readJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outputStream = null
    }
}
