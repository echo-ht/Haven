package sh.haven.core.mosh

/**
 * JNI bridge for PTY operations used by mosh-client.
 * Native implementation in pty_jni.c.
 */
object PtyHelper {
    init {
        System.loadLibrary("mosh-pty")
    }

    /**
     * Fork a child process with a new PTY.
     * @return int[2] = {masterFd, childPid}, or null on failure
     */
    external fun nativeForkPty(
        path: String,
        argv: Array<String>,
        env: Array<String>,
        rows: Int,
        cols: Int,
    ): IntArray?

    /**
     * Resize the PTY window.
     */
    external fun nativeResize(masterFd: Int, rows: Int, cols: Int)
}
