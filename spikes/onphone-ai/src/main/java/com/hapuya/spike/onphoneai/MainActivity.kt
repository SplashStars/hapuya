package com.hapuya.spike.onphoneai

import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Screen state kept outside the Activity so a rotation or relaunch keeps the run going. */
object UiState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var job: Job? by mutableStateOf(null)
    var modelStatus by mutableStateOf("")
    var nanoStatus by mutableStateOf("Not checked")
    var progress by mutableStateOf("")
    var set by mutableStateOf("holdout")
    var constrained by mutableStateOf(false)
    /** Short one-line answers instead of JSON (fewer tokens to write; A6 speed test). */
    var compact by mutableStateOf(false)
    /** Set over adb only (--es format short): about 350 tokens of instructions instead of 1,700. */
    var short by mutableStateOf(false)
    val mode get() = when {
        compact && short -> com.hapuya.tools.intenteval.Mode.SHORT_COMPACT
        compact -> com.hapuya.tools.intenteval.Mode.COMPACT
        short -> com.hapuya.tools.intenteval.Mode.SHORT
        else -> com.hapuya.tools.intenteval.Mode.SPLIT
    }
    val log = mutableStateListOf<String>()

    fun say(line: String) {
        Log.i(Bench.TAG, line)
        scope.launch(Dispatchers.Main) {
            log.add(line)
            if (log.size > 60) log.removeRange(0, log.size - 60)
        }
    }

    val busy get() = job?.isActive == true
}

/**
 * Spike A6 tester screen. Every step runs in [RunService]. Hands-off from the laptop:
 *   adb shell am start-foreground-service -n com.hapuya.spike.onphoneai/.RunService --es run cpu --es set holdout
 *   (run = cpu | gpu | cpu-cached | gpu-cached | nano | download | verify; set = holdout | holdout2 | v1 | quick;
 *    --es format compact for one-line answers, short for ~350-token instructions; --ez constrained true)
 * Results: /sdcard/Android/data/com.hapuya.spike.onphoneai/files/results/
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        UiState.modelStatus = ModelFile.status(this)
        setContent { MaterialTheme { Screen() } }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val run = intent?.getStringExtra("run") ?: return
        intent.removeExtra("run")
        startForegroundService(Intent(intent).setClass(this, RunService::class.java).putExtra("run", run))
    }

    private fun go(run: String) = RunService.start(this, run)

    private fun deviceLine(): String {
        val mem = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val free = StatFs(getExternalFilesDir(null)!!.path).availableBytes
        val soc = if (Build.VERSION.SDK_INT >= 31) " · ${Build.SOC_MODEL}" else ""
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}$soc · " +
            "RAM ${"%.1f".format(mem.totalMem / 1e9)} GB · storage free ${free / 1_000_000_000} GB"
    }

    @Composable
    private fun Screen() {
        val busy = UiState.busy
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("A6 on-phone AI test", style = MaterialTheme.typography.headlineSmall)
            Text(deviceLine(), style = MaterialTheme.typography.bodySmall)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Built-in AI (Gemini Nano)", style = MaterialTheme.typography.titleMedium)
                    Text(UiState.nanoStatus)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { go("nano-check") }, enabled = !busy) { Text("Check") }
                        OutlinedButton(onClick = { go("nano-download") }, enabled = !busy) { Text("Download") }
                        Button(onClick = { go("nano") }, enabled = !busy) { Text("Run test") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2. Downloaded model (Gemma 4 E2B, 2.6 GB)", style = MaterialTheme.typography.titleMedium)
                    Text(UiState.modelStatus)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { go("download") }, enabled = !busy) { Text("Download") }
                        OutlinedButton(onClick = { go("verify") }, enabled = !busy) { Text("Check hash") }
                        OutlinedButton(onClick = {
                            ModelFile.delete(applicationContext); UiState.modelStatus = ModelFile.status(applicationContext)
                        }, enabled = !busy) { Text("Delete") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("3. Run the test", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("quick" to "Quick (10)", "holdout" to "Holdout (64)", "holdout2" to "Holdout 2 (64)", "v1" to "Full (200)").forEach { (k, l) ->
                            FilterChip(selected = UiState.set == k, onClick = { UiState.set = k }, label = { Text(l) })
                        }
                    }
                    Row {
                        Checkbox(checked = UiState.constrained, onCheckedChange = { UiState.constrained = it })
                        Text("Force JSON output (constrained)", Modifier.padding(top = 12.dp))
                    }
                    Row {
                        Checkbox(checked = UiState.compact, onCheckedChange = { UiState.compact = it })
                        Text("Short answers (one line, not JSON)", Modifier.padding(top = 12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { go("gpu") }, enabled = !busy) { Text("Run on GPU") }
                        Button(onClick = { go("cpu") }, enabled = !busy) { Text("Run on CPU") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { go("gpu-cached") }, enabled = !busy) { Text("GPU, reuse prompt") }
                        OutlinedButton(onClick = { go("cpu-cached") }, enabled = !busy) { Text("CPU, reuse prompt") }
                        OutlinedButton(onClick = { UiState.job?.cancel() }, enabled = busy) { Text("Stop") }
                    }
                    Text("For battery figures, unplug the phone before you run.", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (UiState.progress.isNotEmpty()) Text(UiState.progress, style = MaterialTheme.typography.titleSmall)
            Text(
                UiState.log.joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}
