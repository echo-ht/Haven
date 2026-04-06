package sh.haven.feature.connections

import android.Manifest
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.unit.dp
import sh.haven.core.data.db.entities.ConnectionProfile

/** Check whether the currently active keyboard has INTERNET permission. */
@Composable
private fun rememberKeyboardHasInternet(): Boolean {
    val context = LocalContext.current
    return remember {
        try {
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val currentIme = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
            )
            val pkgName = currentIme?.substringBefore('/') ?: return@remember false
            val pkgInfo = context.packageManager.getPackageInfo(pkgName, PackageManager.GET_PERMISSIONS)
            pkgInfo.requestedPermissions?.any { it == Manifest.permission.INTERNET } == true
        } catch (_: Exception) {
            false
        }
    }
}

@Composable
fun PasswordDialog(
    profile: ConnectionProfile,
    hasKeys: Boolean,
    onDismiss: () -> Unit,
    onConnect: (password: String, rememberPassword: Boolean) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(!profile.sshPassword.isNullOrBlank()) }
    val keyboardHasInternet = rememberKeyboardHasInternet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to ${profile.label}") },
        text = {
            Column {
                if (keyboardHasInternet) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                "Your keyboard has internet access and may transmit what you type. Consider using a privacy keyboard (e.g. HeliBoard, Simple Keyboard).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                when {
                    profile.isRdp -> {
                        Text("${profile.rdpUsername ?: profile.username}@${profile.host}:${profile.rdpPort}")
                        if (!profile.rdpDomain.isNullOrBlank()) {
                            Text(
                                "Domain: ${profile.rdpDomain}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> Text("${profile.username}@${profile.host}:${profile.port}")
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                        platformImeOptions = PlatformImeOptions("flagNoPersonalizedLearning"),
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { onConnect(password, rememberPassword) },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (profile.isSsh || profile.isSmb) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = rememberPassword,
                            onCheckedChange = { rememberPassword = it },
                        )
                        Text(
                            "Remember password",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (hasKeys && profile.isSsh) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Leave empty to connect with SSH key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(password, rememberPassword) }) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
