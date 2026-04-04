package sh.haven.app.desktop

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import sh.haven.core.vnc.VncClient
import sh.haven.core.rdp.RdpSession

/**
 * A single tab on the Desktop screen, representing an active VNC, RDP,
 * or native Wayland session. Mirrors TerminalTab for terminal sessions.
 */
sealed class DesktopTab {
    abstract val id: String
    abstract val label: String
    abstract val colorTag: Int
    abstract val connected: StateFlow<Boolean>
    abstract val frame: StateFlow<Bitmap?>
    abstract val error: StateFlow<String?>

    /** Protocol indicator for the tab bar icon/label. */
    val protocol: String get() = when (this) {
        is Vnc -> "VNC"
        is Rdp -> "RDP"
        is Wayland -> "Wayland"
    }

    data class Vnc(
        override val id: String,
        override val label: String,
        override val colorTag: Int = 0,
        val client: VncClient,
        val _connected: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val _frame: MutableStateFlow<Bitmap?> = MutableStateFlow(null),
        val _error: MutableStateFlow<String?> = MutableStateFlow(null),
        val tunnelPort: Int? = null,
        val tunnelSessionId: String? = null,
        val profileId: String? = null,
    ) : DesktopTab() {
        override val connected: StateFlow<Boolean> get() = _connected
        override val frame: StateFlow<Bitmap?> get() = _frame
        override val error: StateFlow<String?> get() = _error
    }

    data class Rdp(
        override val id: String,
        override val label: String,
        override val colorTag: Int = 0,
        val session: RdpSession,
        val _connected: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val _frame: MutableStateFlow<Bitmap?> = MutableStateFlow(null),
        val _error: MutableStateFlow<String?> = MutableStateFlow(null),
        val tunnelPort: Int? = null,
        val tunnelSessionId: String? = null,
        val profileId: String? = null,
    ) : DesktopTab() {
        override val connected: StateFlow<Boolean> get() = _connected
        override val frame: StateFlow<Bitmap?> get() = _frame
        override val error: StateFlow<String?> get() = _error
    }

    data class Wayland(
        override val id: String = "wayland-native",
        override val label: String = "Wayland",
        override val colorTag: Int = 0,
        val _connected: MutableStateFlow<Boolean> = MutableStateFlow(true),
        val _error: MutableStateFlow<String?> = MutableStateFlow(null),
    ) : DesktopTab() {
        override val connected: StateFlow<Boolean> get() = _connected
        override val frame: StateFlow<Bitmap?> = MutableStateFlow(null) // N/A — uses TextureView
        override val error: StateFlow<String?> get() = _error
    }
}
