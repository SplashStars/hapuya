package com.hapuya.spike.onphoneai

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State for the Try and Connect cards, kept outside the Activity. */
object TryState {
    var cmd by mutableStateOf("")
    var engine by mutableStateOf(TryIt.Engine.AUTO)
    var busy by mutableStateOf(false)
    var result by mutableStateOf<TryIt.Result?>(null)
    var error by mutableStateOf("")
    var status by mutableStateOf("")
    val summary = mutableStateListOf<String>()
    var askConnect by mutableStateOf(false)
    var askServer by mutableStateOf(false)
    /** The command sent to an AI app, waiting for its answer to be shared back. */
    var waitingFor by mutableStateOf<String?>(null)
    var waitingApp by mutableStateOf("")
    var advanced by mutableStateOf(false)
    var url by mutableStateOf("")
    var model by mutableStateOf("")
    var key by mutableStateOf("")
    var tick by mutableStateOf(0) // bumps to refresh status lines

    /** An AI app's answer, shared back to Hapuya or pasted. */
    fun answerFromApp(text: String) {
        val cmd = waitingFor ?: return
        result = runCatching { TryIt.Result("AI app ($waitingApp)", 0, TryIt.decode(text, cmd), text.take(300)) }
            .getOrElse { error = "Couldn't read that answer: ${it.message}"; null }
        if (result != null) { error = ""; waitingFor = null }
    }
}

private val EXAMPLES = listOf(
    "Remind me tomorrow at 9 to call Mum",
    "Remind me in 20 minutes to check the oven",
    "Add lunch with Tobi on Friday at 1pm",
    "Remind me every Monday at 8am to plan the week",
    "Set an alarm for 6:30am",
    "Set a timer for 12 minutes",
    "Remind me when I get home to feed the dog",
    "Send $50 to Kemi",
)

@Composable
fun ConnectCard(ctx: Context) {
    val t = TryState
    @Suppress("UNUSED_VARIABLE") val tick = t.tick
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your own AI", style = MaterialTheme.typography.titleMedium)
            if (!OwnAi.allowed(ctx, "scan")) {
                Text("Hapuya works without AI. It can also use AI you already have on this phone. Tap once and it sets everything up by itself.")
                Button(onClick = { t.askConnect = true }, enabled = !t.busy) { Text("Connect my AI") }
            } else {
                statusLines(ctx).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { t.askConnect = true }, enabled = !t.busy) { Text("Check again") }
                    OutlinedButton(onClick = { OwnAi.disconnect(ctx); t.summary.clear(); t.tick++ }, enabled = !t.busy) { Text("Disconnect all") }
                }
            }
            if (t.summary.isNotEmpty()) {
                Text("Last set-up:", style = MaterialTheme.typography.labelLarge)
                t.summary.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = { t.advanced = !t.advanced }) { Text(if (t.advanced) "Hide advanced" else "Advanced: add an address or key") }
            if (t.advanced) Advanced(ctx)
        }
    }
    if (t.askConnect) AlertDialog(
        onDismissRequest = { t.askConnect = false },
        title = { Text("Connect your AI?") },
        text = {
            Text(
                "Hapuya will, by itself:\n" +
                    "• turn on this phone's built-in AI if it has one\n" +
                    "• look for an AI app running on this phone and use it\n" +
                    "• link AI apps it finds (ChatGPT, Gemini, Claude…): it opens them with your command and you share the answer back\n" +
                    "• download the offline AI model (2.6 GB) on Wi-Fi if nothing else works offline\n\n" +
                    "Only the words of a command you type are used. No messages, contacts, passwords or banking details. " +
                    "AI never acts on its own: Hapuya checks every answer and you confirm. You can disconnect at any time.",
            )
        },
        confirmButton = {
            Button(onClick = {
                t.askConnect = false
                t.busy = true
                t.summary.clear()
                UiState.scope.launch {
                    val lines = runCatching { TryIt.connectAll(ctx) { s -> t.status = s } }
                        .getOrElse { listOf("Set-up stopped: ${it.message}") }
                    withContext(Dispatchers.Main) { t.summary.addAll(lines); t.status = ""; t.busy = false; t.tick++ }
                }
            }) { Text("Allow") }
        },
        dismissButton = { TextButton(onClick = { t.askConnect = false }) { Text("Not now") } },
    )
}

private fun statusLines(ctx: Context): List<String> = buildList {
    val s = OwnAi.server(ctx)
    if (s != null && OwnAi.allowed(ctx, "server:" + OwnAi.host(s.baseUrl))) {
        add("Your AI: ${s.model.ifBlank { "(no model chosen)" }} at ${OwnAi.host(s.baseUrl)}" + if (OwnAi.isOnPhone(s.baseUrl)) " (on this phone, offline)" else "")
    }
    OwnAi.installedApps(ctx).filter { OwnAi.allowed(ctx, "app:" + it.pkg) }.takeIf { it.isNotEmpty() }
        ?.let { add("AI apps: " + it.joinToString { a -> a.name }) }
    add("Offline model: " + if (ModelFile.file(ctx).length() == ModelFile.SIZE) "ready" else "not downloaded")
    add("Built-in AI: " + UiState.nanoStatus)
    add("Rules engine: always on, offline")
}

@Composable
private fun Advanced(ctx: Context) {
    val t = TryState
    Text("For people who already have an AI address: a PC running Ollama or LM Studio, or your own OpenAI, Gemini or Claude key.", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "OpenAI" to "https://api.openai.com/v1", "Gemini" to "https://generativelanguage.googleapis.com/v1beta/openai",
            "Claude" to "https://api.anthropic.com/v1", "PC: Ollama" to "http://192.168.1.10:11434/v1", "PC: LM Studio" to "http://192.168.1.10:1234/v1",
        ).forEach { (n, u) -> AssistChip(onClick = { t.url = u }, label = { Text(n) }) }
    }
    OutlinedTextField(t.url, { t.url = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(t.key, { t.key = it }, label = { Text("Key (leave empty for a PC)") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth())
    OutlinedTextField(t.model, { t.model = it }, label = { Text("Model (Find fills it)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !t.busy && t.url.isNotBlank(), onClick = {
            t.busy = true; t.error = ""
            UiState.scope.launch {
                val r = runCatching { OwnAi.models(t.url.trim(), t.key.ifBlank { null }) }
                withContext(Dispatchers.Main) {
                    r.onSuccess { t.model = it.firstOrNull().orEmpty(); t.status = "Found ${it.size} models: ${it.take(6).joinToString()}" }
                        .onFailure { t.error = "Couldn't reach it: ${it.message}" }
                    t.busy = false
                }
            }
        }) { Text("Find models") }
        Button(enabled = !t.busy && t.url.isNotBlank() && t.model.isNotBlank(), onClick = { t.askServer = true }) { Text("Save") }
    }
    if (t.askServer) AlertDialog(
        onDismissRequest = { t.askServer = false },
        title = { Text("Use this AI?") },
        text = {
            Text(
                if (OwnAi.isOnPhone(t.url)) "Your commands go to the AI app on this phone. Nothing leaves the phone."
                else "The words of commands you type go to ${OwnAi.host(t.url)}" + (if (t.key.isNotBlank()) " under your own account" else "") +
                    ". Nothing else is sent. Your key is stored encrypted on this phone only.",
            )
        },
        confirmButton = {
            Button(onClick = {
                OwnAi.saveServer(ctx, t.url, t.model, t.key)
                OwnAi.allow(ctx, "server:" + OwnAi.host(t.url)); OwnAi.allow(ctx, "scan")
                t.key = ""; t.askServer = false; t.tick++; t.status = "Saved."
            }) { Text("Allow") }
        },
        dismissButton = { TextButton(onClick = { t.askServer = false }) { Text("Cancel") } },
    )
}

@Composable
fun TryCard(activity: Activity) {
    val t = TryState
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Try a command", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(t.cmd, { t.cmd = it }, label = { Text("Type a command") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EXAMPLES.forEach { e -> AssistChip(onClick = { t.cmd = e }, label = { Text(e, fontSize = 12.sp) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TryIt.Engine.entries.forEach { e -> FilterChip(selected = t.engine == e, onClick = { t.engine = e }, label = { Text(e.label) }) }
            }
            Button(enabled = !t.busy && t.cmd.isNotBlank(), onClick = { go(activity) }) {
                Text(if (t.engine == TryIt.Engine.AI_APP) "Send to AI app" else "Understand")
            }
            if (t.waitingFor != null) {
                Text("In ${t.waitingApp}, copy its answer and share it to Hapuya, or copy it and tap Paste answer.")
                OutlinedButton(onClick = {
                    val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
                    if (clip.isNullOrBlank()) t.error = "The clipboard is empty." else t.answerFromApp(clip)
                }) { Text("Paste answer") }
            }
            if (t.status.isNotEmpty()) Text(t.status, style = MaterialTheme.typography.bodySmall)
            if (t.error.isNotEmpty()) Text(t.error, color = MaterialTheme.colorScheme.error)
            t.result?.let { ResultView(activity, it) }
        }
    }
}

private fun go(activity: Activity) {
    val t = TryState
    val cmd = t.cmd.trim()
    t.error = ""; t.result = null
    if (t.engine == TryIt.Engine.AI_APP) {
        val app = OwnAi.installedApps(activity).firstOrNull { OwnAi.allowed(activity, "app:" + it.pkg) }
        if (app == null) { t.error = "No AI app linked. Tap Connect my AI first (it finds ChatGPT, Gemini, Claude…)."; return }
        try {
            activity.startActivity(OwnAi.handOff(app.pkg, TryIt.appPrompt(cmd)))
            t.waitingFor = cmd; t.waitingApp = app.name
        } catch (e: ActivityNotFoundException) { t.error = "${app.name} didn't accept the command." }
        return
    }
    t.busy = true
    UiState.scope.launch {
        val r = runCatching { TryIt.understand(activity.applicationContext, cmd, t.engine) { s -> t.status = s } }
        withContext(Dispatchers.Main) {
            r.onSuccess { t.result = it }.onFailure { t.error = it.message ?: it.javaClass.simpleName }
            t.status = ""; t.busy = false
        }
    }
}

@Composable
private fun ResultView(activity: Activity, r: TryIt.Result) {
    val i = r.intent
    val what = when {
        i.expect == "block:money" -> "Blocked: Hapuya never moves money."
        i.expect.startsWith("unsupported:commerce") -> "Not supported: Hapuya doesn't buy or book."
        i.expect.startsWith("unsupported:location") -> "Not supported yet: place-based reminders."
        i.expect.startsWith("unsupported:clock-app") -> "Change existing alarms in the Clock app."
        i.expect.startsWith("clarify:") -> "Hapuya would ask you: " + when (i.expect.removePrefix("clarify:")) {
            "when" -> "When?"; "time" -> "What time?"; "past-time" -> "That time has passed. When instead?"
            "which-day" -> "Which day do you mean?"; else -> i.expect.removePrefix("clarify:")
        }
        else -> "Ready to do."
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(what, style = MaterialTheme.typography.titleSmall)
            Text("Understood by: ${r.engine} · ${r.ms} ms", style = MaterialTheme.typography.bodySmall)
            listOf(
                "What" to i.intent, "Title" to i.title, "When" to i.whenLocal.replace('T', ' '),
                "Repeats" to i.recurrence, "Length (min)" to i.durationMin,
            ).filter { it.second.isNotBlank() }.forEach { (k, v) -> Text("$k: $v") }
            if (r.note.isNotEmpty()) Text(r.note, style = MaterialTheme.typography.bodySmall)
            if (r.raw.isNotEmpty()) Text("AI said: ${r.raw.trim().take(200)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            TryIt.action(i)?.let { intent ->
                Button(onClick = {
                    try { activity.startActivity(intent) } catch (e: ActivityNotFoundException) { TryState.error = "No app on this phone can do that." }
                }) { Text("Do it (opens ${if (i.intent.startsWith("alarm") || i.intent == "timer.set") "Clock" else "Calendar"})") }
            }
        }
    }
}
