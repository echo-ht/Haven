package sh.haven.core.local

import android.util.Log

/**
 * Creates a symlink to the Wayland socket in /data/local/tmp/ via Shizuku
 * so external clients (Termux, chroot) can connect.
 *
 * Requires Shizuku to be installed and permission granted.
 * Gracefully no-ops if Shizuku is unavailable.
 */
object WaylandSocketHelper {
    private const val TAG = "WaylandSocket"
    private const val LINK_DIR = "/data/local/tmp/haven-wayland"

    /**
     * Try to create a symlink at /data/local/tmp/haven-wayland/wayland-0
     * pointing to the app's Wayland socket. Returns true if successful.
     */
    fun tryCreateSymlink(socketPath: String): Boolean {
        if (!isShizukuAvailable()) {
            Log.d(TAG, "Shizuku not available — external socket access disabled")
            return false
        }
        if (!hasShizukuPermission()) {
            Log.d(TAG, "Shizuku permission not granted")
            return false
        }
        return try {
            val cmd = "mkdir -p $LINK_DIR && chmod 0755 $LINK_DIR && " +
                "rm -f $LINK_DIR/wayland-0 && " +
                "ln -s $socketPath $LINK_DIR/wayland-0"
            val result = runShizukuCommand(cmd)
            if (result == 0) {
                Log.i(TAG, "Symlink created: $LINK_DIR/wayland-0 → $socketPath")
                true
            } else {
                Log.w(TAG, "Symlink command failed with exit code $result")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku symlink failed: ${e.message}")
            false
        }
    }

    /** Clean up the symlink when the compositor stops. */
    fun tryRemoveSymlink() {
        if (!isShizukuAvailable() || !hasShizukuPermission()) return
        try {
            runShizukuCommand("rm -f $LINK_DIR/wayland-0")
        } catch (_: Exception) {}
    }

    /** Shizuku is installed AND its binder service is running. */
    fun isShizukuAvailable(): Boolean {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getMethod("pingBinder")
            method.invoke(null) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    /** Shizuku APK is installed on the device (may not be running). */
    fun isShizukuInstalled(context: android.content.Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getMethod("checkSelfPermission")
            (method.invoke(null) as Int) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun requestPermission() {
        try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getMethod("requestPermission", Int::class.java)
            method.invoke(null, 42)
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku permission request failed: ${e.message}")
        }
    }

    /**
     * Forward a local TCP port to the Android Terminal VM's guest port via Shizuku.
     * Equivalent to: adb forward tcp:<localPort> vsock:<cid>:<guestPort>
     *
     * The Terminal VM uses vsock for communication. CID 2 is the standard guest CID
     * in Android's pKVM implementation.
     *
     * @return true if the forward was created
     */
    fun tryVsockForward(localPort: Int, guestPort: Int, cid: Int = 2): Boolean {
        if (!isShizukuAvailable() || !hasShizukuPermission()) return false
        return try {
            // The Terminal app's port forwarding uses iptables + socat internally.
            // With shell access we can create a socat relay directly.
            val result = runShizukuCommand(
                "nohup socat TCP-LISTEN:$localPort,fork,reuseaddr VSOCK-CONNECT:$cid:$guestPort " +
                    "</dev/null >/dev/null 2>&1 &"
            )
            if (result == 0) {
                Log.i(TAG, "Vsock forward created: localhost:$localPort → vsock:$cid:$guestPort")
                true
            } else {
                Log.w(TAG, "Vsock forward failed with exit code $result")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vsock forward via Shizuku failed: ${e.message}")
            false
        }
    }

    /**
     * Stop a vsock port forward previously created by [tryVsockForward].
     */
    fun tryStopVsockForward(localPort: Int) {
        if (!isShizukuAvailable() || !hasShizukuPermission()) return
        try {
            runShizukuCommand("pkill -f 'socat TCP-LISTEN:$localPort'")
        } catch (_: Exception) {}
    }

    /**
     * Disable battery optimization for the given package via Shizuku.
     * Equivalent to: adb shell cmd deviceidle whitelist +<package>
     * Returns true if successful.
     */
    fun tryDisableBatteryOptimization(packageName: String): Boolean {
        if (!isShizukuAvailable() || !hasShizukuPermission()) return false
        return try {
            val result = runShizukuCommand("cmd deviceidle whitelist +$packageName")
            if (result == 0) {
                Log.i(TAG, "Battery optimization disabled for $packageName via Shizuku")
                true
            } else {
                Log.w(TAG, "Battery optimization command failed with exit code $result")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Battery optimization via Shizuku failed: ${e.message}")
            false
        }
    }

    private fun runShizukuCommand(cmd: String): Int {
        // Use Shizuku's remote process to execute shell commands
        val clazz = Class.forName("rikka.shizuku.Shizuku")
        val method = clazz.getMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        return process.waitFor()
    }
}
