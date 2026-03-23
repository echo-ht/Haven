package sh.haven.feature.settings

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.db.entities.ConnectionLog

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AuditLogScreen(
    onBack: () -> Unit,
    viewModel: AuditLogViewModel = hiltViewModel(),
) {
    val logs by viewModel.logs.collectAsState()
    val filterProfileId by viewModel.filterProfileId.collectAsState()
    val availableProfiles by viewModel.availableProfiles.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Connection log") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (logs.isNotEmpty()) {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear logs")
                    }
                }
            },
        )

        // Filter chips
        if (availableProfiles.size > 1) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filterProfileId == null,
                    onClick = { viewModel.setFilter(null) },
                    label = { Text("All") },
                )
                availableProfiles.forEach { (id, label) ->
                    FilterChip(
                        selected = filterProfileId == id,
                        onClick = { viewModel.setFilter(if (filterProfileId == id) null else id) },
                        label = { Text(label) },
                    )
                }
            }
        }

        if (logs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No connection events logged",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(logs, key = { it.id }) { item ->
                    LogItem(item)
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear all logs?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearLogs()
                    showClearDialog = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LogItem(item: LogDisplayItem) {
    val (icon, tint) = when (item.status) {
        ConnectionLog.Status.CONNECTED -> Icons.Filled.CheckCircle to Color(0xFF4CAF50)
        ConnectionLog.Status.DISCONNECTED -> Icons.Filled.RemoveCircle to Color(0xFF9E9E9E)
        ConnectionLog.Status.FAILED -> Icons.Filled.Error to Color(0xFFF44336)
        ConnectionLog.Status.TIMEOUT -> Icons.Filled.Error to Color(0xFFFF9800)
    }

    val timeText = DateUtils.getRelativeTimeSpanString(
        item.timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

    ListItem(
        leadingContent = {
            Icon(icon, contentDescription = item.status.name, tint = tint, modifier = Modifier.size(24.dp))
        },
        headlineContent = {
            Text("${item.profileLabel}${if (item.host.isNotEmpty()) " (${item.host})" else ""}")
        },
        supportingContent = {
            val statusLabel = item.status.name.lowercase().replaceFirstChar { it.uppercase() }
            val line = if (item.details != null) "$statusLabel (${ item.details }) - $timeText"
            else "$statusLabel - $timeText"
            Text(line)
        },
    )
}
