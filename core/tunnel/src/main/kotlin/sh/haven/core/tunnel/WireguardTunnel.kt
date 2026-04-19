package sh.haven.core.tunnel

import sh.haven.rclone.binding.wgbridge.Conn as NativeConn
import sh.haven.rclone.binding.wgbridge.TunnelHandle as NativeHandle
import sh.haven.rclone.binding.wgbridge.Wgbridge
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [Tunnel] implementation backed by wireguard-go + gVisor netstack via
 * the gomobile-bound `wgbridge` package (co-located in rclone-android so
 * both gomobile bindings ship in a single `libgojni.so` — see
 * rclone-android/go/wgbridge/).
 *
 * Holds one live native tunnel handle — created up-front from the user's
 * wg-quick config text, torn down on [close]. Dials are safe to call
 * concurrently; the native layer serialises as needed.
 */
class WireguardTunnel internal constructor(configText: String) : Tunnel {

    private val native: NativeHandle = try {
        Wgbridge.startTunnel(configText)
    } catch (e: Exception) {
        throw IOException("Failed to start WireGuard tunnel: ${e.message}", e)
    }

    override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection {
        val conn = try {
            native.dial(host, port.toLong(), timeoutMs.toLong())
        } catch (e: Exception) {
            throw IOException("WireGuard dial $host:$port failed: ${e.message}", e)
        }
        return NativeTunneledConnection(conn)
    }

    override fun close() {
        try {
            native.close()
        } catch (_: Throwable) {
            // Best-effort teardown.
        }
    }
}

/**
 * Wraps a native [NativeConn] as [TunneledConnection]. The Go side
 * returns fresh byte slices from `read(size)` because gomobile copies
 * `[]byte` arguments across the JNI boundary — the caller's buffer
 * wouldn't be updated otherwise. The extra copy is immaterial for SSH
 * traffic (small frames, network round-trip dominates).
 */
private class NativeTunneledConnection(
    private val conn: NativeConn,
) : TunneledConnection {

    override val inputStream: InputStream = object : InputStream() {
        private var eof = false

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n == -1) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (len == 0) return 0
            val bytes = try {
                conn.read(len.toLong())
            } catch (_: Exception) {
                eof = true
                return -1
            }
            if (bytes == null || bytes.isEmpty()) {
                eof = true
                return -1
            }
            val n = minOf(bytes.size, len)
            System.arraycopy(bytes, 0, b, off, n)
            return n
        }
    }

    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf((b and 0xFF).toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val slice = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            try {
                conn.write(slice)
            } catch (e: Exception) {
                throw IOException("WireGuard write failed: ${e.message}", e)
            }
        }
    }

    override fun close() {
        try {
            conn.close()
        } catch (_: Throwable) {
            // Already-closed connections raise here; ignore.
        }
    }
}
