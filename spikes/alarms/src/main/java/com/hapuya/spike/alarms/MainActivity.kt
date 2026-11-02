package com.hapuya.spike.alarms

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    // Bumped on resume and after actions so the screen re-reads the store.
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Scheduler.ensureChannel(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Screen(refresh, onChanged = { refresh++ })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    // `version` is unused in the body; passing it makes Compose re-run this
    // (and re-read the store) whenever it changes.
    @Suppress("UNUSED_PARAMETER")
    @Composable
    private fun Screen(version: Int, onChanged: () -> Unit) {
        val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
        val probes = ProbeStore.all(this).sortedByDescending { it.targetAt }

        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Device", style = MaterialTheme.typography.titleMedium)
            Text(statusText())

            Button(onClick = { Scheduler.scheduleLadder(this@MainActivity); onChanged() }) {
                Text("Schedule test ladder (2 min → 8 h)")
            }
            if (Build.VERSION.SDK_INT >= 33) {
                OutlinedButton(onClick = { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("Allow notifications")
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                OutlinedButton(onClick = {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                }) { Text("Exact alarm setting") }
            }
            OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                Text("Battery optimisation settings")
            }
            OutlinedButton(onClick = { share(probes) }) { Text("Share results (CSV)") }
            OutlinedButton(onClick = { ProbeStore.clear(this@MainActivity); onChanged() }) { Text("Clear results") }

            Text("Probes (${probes.count { it.passed == true }} pass / ${probes.count { it.firedAt != null }} fired / ${probes.size})",
                style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(probes, key = { it.id }) { p ->
                    val result = when {
                        p.firedAt == null && p.targetAt > System.currentTimeMillis() -> "pending"
                        p.firedAt == null -> "MISSED"
                        p.passed == true -> "pass, ${p.delaySeconds}s late"
                        else -> "FAIL, ${p.delaySeconds}s late"
                    }
                    Text("${time(p.targetAt)}  ${p.label}  [${p.mode}]  $result")
                }
            }
        }
    }

    private fun statusText(): String {
        val pm = getSystemService(PowerManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        return listOf(
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Exact alarms allowed: ${Scheduler.canExact(this)}",
            "Battery optimisation ignored: ${pm.isIgnoringBatteryOptimizations(packageName)}",
            "Notifications enabled: ${nm.areNotificationsEnabled()}",
        ).joinToString("\n")
    }

    private fun share(probes: List<Probe>) {
        val header = "device,android,label,mode,target,fired,delay_s,result"
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        val rows = probes.map {
            listOf(device, Build.VERSION.RELEASE, it.label, it.mode, time(it.targetAt),
                it.firedAt?.let(::time).orEmpty(), it.delaySeconds?.toString().orEmpty(),
                when (it.passed) { true -> "pass"; false -> "fail"; null -> if (it.targetAt > System.currentTimeMillis()) "pending" else "missed" },
            ).joinToString(",")
        }
        val csv = (listOf(header) + rows).joinToString("\n")
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, csv),
            "Share alarm results",
        ))
    }

    private fun time(ms: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
