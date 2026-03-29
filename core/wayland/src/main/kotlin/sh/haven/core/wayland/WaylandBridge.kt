package sh.haven.core.wayland

import android.util.Log

/**
 * JNI bridge to the native labwc Wayland compositor.
 * The compositor runs on a dedicated native thread.
 */
object WaylandBridge {
    private const val TAG = "WaylandBridge"

    init {
        try {
            System.loadLibrary("labwc_android")
            Log.i(TAG, "liblabwc_android.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load liblabwc_android.so", e)
        }
    }

    /**
     * Start the Wayland compositor on a background thread.
     * Creates a Wayland socket at [xdgRuntimeDir]/wayland-0.
     */
    external fun nativeStart(
        xdgRuntimeDir: String,
        xkbConfigRoot: String,
        fontconfigFile: String,
    )

    /** Stop the compositor and wait for the thread to exit. */
    external fun nativeStop()

    /** Returns the path to the Wayland socket (e.g. "/data/.../wayland-0"). */
    external fun nativeGetSocketPath(): String

    /** Returns true if the compositor event loop is running. */
    external fun nativeIsRunning(): Boolean
}
