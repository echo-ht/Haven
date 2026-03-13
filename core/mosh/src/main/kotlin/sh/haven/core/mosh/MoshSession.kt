package sh.haven.core.mosh

import android.system.ErrnoException
import android.system.Os
import android.util.Log
import java.io.Closeable
import java.io.FileDescriptor
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val TAG = "MoshSession"

/**
 * Bridges a mosh-client PTY to a terminal emulator.
 *
 * Parallel to ReticulumSession but reads/writes a PTY file descriptor
 * instead of polling a Python bridge. Mosh-client handles its own
 * UDP roaming — this class just shuttles bytes between the PTY and
 * the terminal emulator.
 */
class MoshSession(
    val sessionId: String,
    val profileId: String,
    val label: String,
    private val masterFd: Int,
    private val childPid: Int,
    private val onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onDisconnected: ((cleanExit: Boolean) -> Unit)? = null,
) : Closeable {

    @Volatile
    private var closed = false

    private var readerThread: Thread? = null

    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mosh-writer-$sessionId").apply { isDaemon = true }
    }

    private val fd: FileDescriptor = FileDescriptor().also {
        // Set the internal int fd field via reflection (Android's FileDescriptor
        // doesn't have a public constructor that takes an fd int).
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        field.setInt(it, masterFd)
    }

    /**
     * Start the reader thread that reads mosh-client output from the PTY.
     */
    fun start() {
        readerThread = thread(
            name = "mosh-reader-$sessionId",
            isDaemon = true,
        ) {
            readLoop()
        }
    }

    private fun readLoop() {
        val buf = ByteArray(8192)
        try {
            while (!closed) {
                val n = try {
                    Os.read(fd, buf, 0, buf.size)
                } catch (e: ErrnoException) {
                    if (closed) break
                    Log.e(TAG, "read error for $sessionId: ${e.message}")
                    break
                }

                if (n <= 0) {
                    // EOF — mosh-client exited
                    break
                }

                onDataReceived(buf, 0, n)
            }
        } catch (e: Exception) {
            if (!closed) {
                Log.e(TAG, "readLoop exception for $sessionId", e)
            }
        }

        if (!closed) {
            Log.d(TAG, "readLoop ended for $sessionId (mosh-client exited)")
            onDisconnected?.invoke(true)
        }
    }

    /**
     * Send keyboard input to mosh-client via the PTY.
     * Safe to call from any thread.
     */
    fun sendInput(data: ByteArray) {
        if (closed) return
        val copy = data.copyOf()
        try {
            writeExecutor.execute {
                if (closed) return@execute
                try {
                    Os.write(fd, copy, 0, copy.size)
                } catch (e: ErrnoException) {
                    Log.e(TAG, "sendInput failed", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (closed) return
        try {
            writeExecutor.execute {
                if (closed) return@execute
                try {
                    PtyHelper.nativeResize(masterFd, rows, cols)
                } catch (e: Exception) {
                    Log.e(TAG, "resize failed", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down
        }
    }

    /**
     * Detach without killing mosh-client.
     * Stops the reader/writer but leaves the child process alive.
     */
    fun detach() {
        if (closed) return
        closed = true
        writeExecutor.shutdown()
        readerThread?.interrupt()
    }

    override fun close() {
        if (closed) return
        closed = true
        writeExecutor.shutdown()
        try {
            Os.kill(childPid, 1) // SIGHUP
        } catch (e: ErrnoException) {
            Log.e(TAG, "kill failed for pid $childPid", e)
        }
        try {
            Os.close(fd)
        } catch (e: ErrnoException) {
            Log.e(TAG, "close fd failed", e)
        }
        readerThread?.interrupt()
    }
}
