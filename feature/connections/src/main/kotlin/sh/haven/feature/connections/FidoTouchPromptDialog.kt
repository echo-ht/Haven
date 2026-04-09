package sh.haven.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sh.haven.core.fido.FidoTouchPrompt

/**
 * Modal prompt shown while a FIDO2 SSH assertion is in flight. The dialog is
 * not user-dismissible — the JSch auth path is awaiting the security key's
 * signature, and there is no clean cancel route from the UI thread back into
 * a blocking USB / NFC transfer. The dialog disappears automatically when
 * [FidoTouchPrompt] flips back to null in [FidoAuthenticator.touchPrompt],
 * which happens whether the assertion succeeds, fails, or the underlying
 * transfer times out.
 */
@Composable
fun FidoTouchPromptDialog(prompt: FidoTouchPrompt) {
    val (title, body) = when (prompt) {
        is FidoTouchPrompt.WaitingForKey -> "Security key required" to
            "Plug in your security key over USB, or tap it on the back of " +
            "the device for NFC. Haven will continue automatically once it is detected."
        is FidoTouchPrompt.TouchKey -> "Touch your security key" to
            "Press the button on your security key now to authorise the SSH " +
            "signature. The key may blink to indicate it is waiting."
    }

    AlertDialog(
        // Empty lambda — this prompt is not user-dismissible. The
        // FidoAuthenticator clears the state when the assertion finishes
        // (success, failure, or timeout) and the dialog goes away with it.
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Cancel by disconnecting from the connections list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        // No buttons — the dialog can only be dismissed by the assertion
        // completing or by the user disconnecting via the connections list.
        confirmButton = {},
    )
}
