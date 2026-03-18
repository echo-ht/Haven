package sh.haven.core.et

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Integration test against local etserver.
 * Skipped if etserver is not running on localhost:2022.
 */
class EtIntegrationTest {

    private fun isEtServerRunning(): Boolean {
        return try {
            val sock = Socket()
            sock.connect(InetSocketAddress("127.0.0.1", 2022), 1000)
            sock.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Bootstrap etterminal locally to get valid credentials.
     * Returns (clientId, passkey) or null if etterminal is not available.
     */
    private fun bootstrapEtterminal(): Pair<String, String>? {
        try {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            fun randomAlphaNum(len: Int) = String(CharArray(len) { chars.random() })
            val proposedId = "XXX" + randomAlphaNum(13)
            val proposedKey = randomAlphaNum(32)
            val input = "${proposedId}/${proposedKey}_xterm-256color"

            val process = ProcessBuilder("etterminal")
                .redirectErrorStream(true)
                .start()

            process.outputStream.write(input.toByteArray())
            process.outputStream.close()

            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            process.waitFor(5, TimeUnit.SECONDS)

            val marker = "IDPASSKEY:"
            val pos = output.indexOf(marker)
            if (pos < 0) return null

            val idPasskey = output.substring(pos + marker.length).trim().take(49)
            val parts = idPasskey.split("/", limit = 2)
            if (parts.size != 2 || parts[0].length != 16 || parts[1].length != 32) return null
            return Pair(parts[0], parts[1])
        } catch (_: Exception) {
            return null
        }
    }

    @Test
    fun `handshake with real etserver`() {
        assumeTrue("etserver not running", isEtServerRunning())
        val creds = bootstrapEtterminal()
        assumeTrue("etterminal not available", creds != null)

        val (clientId, passkey) = creds!!
        println("Got credentials: clientId=${clientId.take(6)}...")

        // TCP connect to etserver
        val sock = Socket()
        sock.connect(InetSocketAddress("127.0.0.1", 2022), 5000)
        sock.tcpNoDelay = true
        val input = sock.getInputStream()
        val output = sock.getOutputStream()

        // Send ConnectRequest
        val request = EtProtocol.encodeConnectRequest(clientId, EtProtocol.PROTOCOL_VERSION)
        EtProtocol.writeHandshakeMessage(output, request)

        // Read ConnectResponse
        val responseBytes = EtProtocol.readHandshakeMessage(input)
        val (status, error) = EtProtocol.decodeConnectResponse(responseBytes)
        println("ConnectResponse: status=$status error=$error")

        assertEquals("Expected NEW_CLIENT status", EtProtocol.STATUS_NEW_CLIENT, status)
        assertTrue("Error should be null or empty", error.isNullOrEmpty())

        // Set up encryption
        val writer = EtCrypto(passkey.toByteArray(), EtCrypto.CLIENT_SERVER_NONCE_MSB)
        val reader = EtCrypto(passkey.toByteArray(), EtCrypto.SERVER_CLIENT_NONCE_MSB)

        // Send encrypted InitialPayload
        val initialPayload = EtProtocol.encodeInitialPayload(jumphost = false)
        val encPayload = writer.encrypt(initialPayload)
        EtProtocol.writeDataPacket(output, true, EtProtocol.HEADER_INITIAL_PAYLOAD, encPayload)

        // Read InitialResponse
        val (isEnc, respHeader, respCiphertext) = EtProtocol.readDataPacket(input)
        println("InitialResponse: encrypted=$isEnc header=${respHeader.toInt() and 0xFF}")
        assertTrue("Response should be encrypted", isEnc)
        assertEquals("Expected INITIAL_RESPONSE header",
            EtProtocol.HEADER_INITIAL_RESPONSE.toInt() and 0xFF,
            respHeader.toInt() and 0xFF)

        val decResp = reader.decrypt(respCiphertext)
        val respError = EtProtocol.decodeInitialResponse(decResp)
        println("InitialResponse error: $respError")
        assertTrue("InitialResponse should have no error", respError.isNullOrEmpty())

        println("Handshake complete!")

        // Send a terminal resize
        val resizePayload = EtProtocol.encodeTerminalInfo(rows = 24, cols = 80)
        val encResize = writer.encrypt(resizePayload)
        EtProtocol.writeDataPacket(output, true, EtProtocol.HEADER_TERMINAL_INFO, encResize)

        // Try to read some terminal output (should get shell prompt or similar)
        val received = AtomicReference<ByteArray>(null)
        val latch = CountDownLatch(1)

        val readThread = Thread {
            try {
                val (_, header, ciphertext) = EtProtocol.readDataPacket(input)
                val decrypted = reader.decrypt(ciphertext)
                when (header) {
                    EtProtocol.HEADER_TERMINAL_BUFFER -> {
                        val data = EtProtocol.decodeTerminalBuffer(decrypted)
                        received.set(data)
                        println("Received terminal data: ${String(data).take(100)}")
                    }
                    else -> {
                        println("Received packet header=${header.toInt() and 0xFF}")
                    }
                }
            } catch (e: Exception) {
                println("Read error: ${e.message}")
            }
            latch.countDown()
        }
        readThread.isDaemon = true
        readThread.start()

        // Wait for some output
        val gotOutput = latch.await(5, TimeUnit.SECONDS)
        println("Got terminal output: $gotOutput")

        sock.close()
    }
}
