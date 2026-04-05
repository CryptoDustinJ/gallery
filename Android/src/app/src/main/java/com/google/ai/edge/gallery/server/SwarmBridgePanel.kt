/*
 * Compose UI panel for controlling the Swarm Bridge server.
 * Can be embedded in the main settings screen or as a standalone page.
 */

package com.google.ai.edge.gallery.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun SwarmBridgePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isRunning by SwarmBridgeManager.isRunning.collectAsState()
    val port by SwarmBridgeManager.port.collectAsState()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "Swarm Bridge",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (isRunning) "Serving on port $port" else "Offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isRunning,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            SwarmBridgeManager.startServer(context)
                        } else {
                            SwarmBridgeManager.stopServer(context)
                        }
                    },
                )
            }

            if (isRunning) {
                Spacer(modifier = Modifier.height(12.dp))

                val a11yConnected = SwarmAccessibilityService.instance != null
                val notifConnected = SwarmNotificationListener.instance != null

                StatusRow("HTTP Server", true)
                StatusRow("Accessibility Service", a11yConnected)
                StatusRow("Notification Listener", notifConnected)

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "curl http://phone-ip:$port/api/status",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, connected: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            if (connected) "Connected" else "Not enabled",
            style = MaterialTheme.typography.bodySmall,
            color = if (connected)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error,
        )
    }
}
