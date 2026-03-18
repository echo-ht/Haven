package sh.haven.core.et

import org.junit.Assert.*
import org.junit.Test

/**
 * Test that our XSalsa20-Poly1305 implementation matches
 * libsodium's crypto_secretbox_easy output.
 */
class EtCryptoCompatTest {

    @Test
    fun `matches libsodium crypto_secretbox output`() {
        val key = "abcdefghijklmnopqrstuvwxyz012345".toByteArray()
        val plaintext = "Hello, Eternal Terminal!".toByteArray()

        // Expected output from Python nacl.bindings.crypto_secretbox
        // with nonce = [1,0,0,...,0] (our first nonce after increment)
        val expectedHex = "e0a49906f02079386e4d8c885424bd035ca76d24bc0171cbccf2a6589da361ea2a0a91e48415ed88"

        val crypto = EtCrypto(key, EtCrypto.CLIENT_SERVER_NONCE_MSB)
        val result = crypto.encrypt(plaintext)

        val resultHex = result.joinToString("") { "%02x".format(it) }
        println("Expected: $expectedHex")
        println("Got:      $resultHex")
        assertEquals("Crypto output must match libsodium", expectedHex, resultHex)
    }
}
