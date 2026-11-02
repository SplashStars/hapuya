package com.hapuya.spike.notify

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat

class MainActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen(refresh) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    // `version` re-runs the listener-enabled check after returning from Settings.
    @Suppress("UNUSED_PARAMETER")
    @Composable
    private fun Screen(version: Int) {
        val enabled = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
        var confirming by remember { mutableStateOf<Captured?>(null) }
        val items = CaptureLog.items

        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            Text("Notification access: ${if (enabled) "granted" else "NOT granted"}")
            if (!enabled) {
                Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                    Text("Grant notification access")
                }
            }
            OutlinedButton(onClick = { share(items) }) { Text("Share results (CSV, no message content)") }
            OutlinedButton(onClick = { items.clear() }) { Text("Clear") }

            Text("Captured: ${items.size} (watching ${WATCHED.values.joinToString()})",
                style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items) { item ->
                    Column {
                        Text("${item.app} · ${item.title.ifBlank { "(no title)" }}")
                        Text("Arrived ${item.latencyMs} ms after posting · reply action: ${if (item.reply != null) "yes" else "no"}")
                        item.replyResult?.let { Text("Reply: $it") }
                        if (item.reply != null && item.replyResult == null) {
                            TextButton(onClick = { confirming = item }) { Text("Send test reply…") }
                        }
                    }
                }
            }
        }

        // Every send is confirmed by the tester (CLAUDE.md: confirm before any send).
        confirming?.let { item ->
            AlertDialog(
                onDismissRequest = { confirming = null },
                title = { Text("Send test reply?") },
                text = { Text("Sends \"$TEST_REPLY\" to \"${item.title}\" in ${item.app}. Use a test chat only.") },
                confirmButton = {
                    TextButton(onClick = {
                        item.replyResult = sendTestReply(this@MainActivity, item)
                        confirming = null
                    }) { Text("Send") }
                },
                dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
            )
        }
    }

    private fun share(items: List<Captured>) {
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        val rows = items.map {
            listOf(device, Build.VERSION.RELEASE, it.app, it.latencyMs, it.reply != null,
                it.replyResult?.substringBefore(" (").orEmpty()).joinToString(",")
        }
        val csv = (listOf("device,android,app,latency_ms,has_reply_action,reply_result") + rows).joinToString("\n")
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, csv),
            "Share notification results",
        ))
    }
}
