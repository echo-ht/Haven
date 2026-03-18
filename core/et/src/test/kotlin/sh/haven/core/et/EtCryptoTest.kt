package sh.haven.core.et

import org.junit.Assert.*
import org.junit.Test

class EtCryptoTest {

    private val testKey = "abcdefghijklmnopqrstuvwxyz012345".toByteArray() // 32 bytes

    @Test
    fun `encrypt then decrypt roundtrip`() {
        val encryptor = EtCrypto(testKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)
        val decryptor = EtCrypto(testKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)

        val plaintext = "Hello, Eternal Terminal!".toByteArray()
        val ciphertext = encryptor.encrypt(plaintext)

        // Ciphertext should be MAC (16) + encrypted data
        assertEquals(plaintext.size + EtCrypto.MAC_BYTES, ciphertext.size)

        // Decrypt should recover original
        val decrypted = decryptor.decrypt(ciphertext)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `multiple messages stay in sync`() {
        val encryptor = EtCrypto(testKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)
        val decryptor = EtCrypto(testKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)

        for (i in 0 until 10) {
            val msg = "Message $i".toByteArray()
            val ct = encryptor.encrypt(msg)
            val pt = decryptor.decrypt(ct)
            assertArrayEquals("Message $i failed", msg, pt)
        }
    }

    @Test
    fun `client-server nonce streams are independent`() {
        // Client writer (MSB=0) and server reader (MSB=0) should match
        val clientWriter = EtCrypto(testKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)
        val serverReader = EtCrypto(testKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)

        // Server writer (MSB=1) and client reader (MSB=1) should match
        val serverWriter = EtCrypto(testKey, EtCrypto.SERVER_CLIENT_NONCE_MSB)
        val clientReader = EtCrypto(testKey, EtCrypto.SERVER_CLIENT_NONCE_MSB)

        // Client sends to server
        val msg1 = "client to server".toByteArray()
        val ct1 = clientWriter.encrypt(msg1)
        assertArrayEquals(msg1, serverReader.decrypt(ct1))

        // Server sends to client
        val msg2 = "server to client".toByteArray()
        val ct2 = serverWriter.encrypt(msg2)
        assertArrayEquals(msg2, clientReader.decrypt(ct2))
    }

    @Test(expected = SecurityException::class)
    fun `wrong key fails MAC verification`() {
        val encryptor = EtCrypto(testKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)
        val wrongKey = "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345".toByteArray()
        val decryptor = EtCrypto(wrongKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)

        val ct = encryptor.encrypt("secret".toByteArray())
        decryptor.decrypt(ct) // should throw
    }

    @Test(expected = SecurityException::class)
    fun `tampered ciphertext fails MAC verification`() {
        val encryptor = EtCrypto(testKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)
        val decryptor = EtCrypto(testKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)

        val ct = encryptor.encrypt("secret".toByteArray())
        // Flip a bit in the ciphertext (after the MAC)
        ct[EtCrypto.MAC_BYTES] = (ct[EtCrypto.MAC_BYTES].toInt() xor 0x01).toByte()
        decryptor.decrypt(ct) // should throw
    }

    @Test(expected = SecurityException::class)
    fun `out of sync nonce fails`() {
        val encryptor = EtCrypto(testKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)
        val decryptor = EtCrypto(testKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)

        // Encrypt two messages but only decrypt the second
        encryptor.encrypt("first".toByteArray())
        val ct2 = encryptor.encrypt("second".toByteArray())
        // Decryptor is at nonce 0, ct2 was encrypted with nonce 2 — mismatch
        decryptor.decrypt(ct2) // should throw
    }

    @Test
    fun `empty plaintext roundtrip`() {
        val encryptor = EtCrypto(testKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)
        val decryptor = EtCrypto(testKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)

        val ct = encryptor.encrypt(ByteArray(0))
        assertEquals(EtCrypto.MAC_BYTES, ct.size)
        val pt = decryptor.decrypt(ct)
        assertEquals(0, pt.size)
    }

    @Test
    fun `large payload roundtrip`() {
        val encryptor = EtCrypto(testKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)
        val decryptor = EtCrypto(testKey, EtCrypto.CLIENT_SERVER_NONCE_MSB)

        val plaintext = ByteArray(65536) { (it % 256).toByte() }
        val ct = encryptor.encrypt(plaintext)
        val pt = decryptor.decrypt(ct)
        assertArrayEquals(plaintext, pt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wrong key size throws`() {
        EtCrypto("short".toByteArray(), EtCrypto.CLIENT_SERVER_NONCE_MSB)
    }
}
