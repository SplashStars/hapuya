package com.hapuya.spike.onphoneai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Runs downloads and test runs as a foreground service with a partial wake lock, so
 * they keep going with the screen off. (Samsung freezes a backgrounded activity
 * within seconds; found on the S22+ on 6 Nov 2026. The real app's model download
 * needs the same treatment.)
 *
 * From the laptop: adb shell am start-foreground-service -n com.hapuya.spike.onphoneai/.RunService --es run gpu --es set holdout
 */
class RunService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val run = intent?.getStringExtra("run") ?: "none"
        intent?.getStringExtra("set")?.let { UiState.set = it }
        intent?.getStringExtra("format")?.let { UiState.compact = it == "compact" || it == "short-compact"; UiState.short = it == "short" || it == "short-compact" }
        if (intent?.hasExtra("constrained") == true) UiState.constrained = intent.getBooleanExtra("constrained", false)
        goForeground(run)
        if (UiState.busy) {
            UiState.say("Busy: wait for the current step or tap Stop")
            return START_NOT_STICKY
        }
        val ctx = applicationContext
        val block: suspend () -> Unit = when (run) {
            "download" -> { { Jobs.download(ctx) } }
            "verify" -> { { Jobs.verify(ctx) } }
            "cpu" -> { { Jobs.runModel(ctx, gpu = false) } }
            "gpu" -> { { Jobs.runModel(ctx, gpu = true) } }
            "cpu-cached" -> { { Jobs.runModel(ctx, gpu = false, reuse = true) } }
            "gpu-cached" -> { { Jobs.runModel(ctx, gpu = true, reuse = true) } }
            "nano" -> { { Jobs.runNano(ctx) } }
            "nano-check" -> { { Jobs.nanoStatus() } }
            "nano-download" -> { { Jobs.downloadNano() } }
            // Laptop smoke tests of the Try and Connect cards (the phone can stay locked):
            //   --es run try --es cmd "Remind me…" [--es engine AUTO|RULES|YOUR_AI|BUILT_IN|PHONE_MODEL]
            //   --es run connect | disconnect
            //   --es run answer --es cmd "<command>" --es text "<an AI app's answer>"
            "try" -> {
                val cmd = intent?.getStringExtra("cmd").orEmpty()
                val engine = runCatching { TryIt.Engine.valueOf(intent?.getStringExtra("engine") ?: "AUTO") }.getOrDefault(TryIt.Engine.AUTO)
                ; {
                    runCatching { TryIt.understand(ctx, cmd, engine, UiState::say) }
                        .onSuccess { UiState.say("TRY [${it.engine}] ${it.ms} ms | ${it.intent} | raw=${it.raw.trim()} | ${it.note}") }
                        .onFailure { UiState.say("TRY failed: ${it.message}") }
                }
            }
            "connect" -> { { TryIt.connectAll(ctx, UiState::say).forEach { UiState.say("CONNECT $it") } } }
            "disconnect" -> { { OwnAi.disconnect(ctx); UiState.say("CONNECT disconnected") } }
            "answer" -> {
                val cmd = intent?.getStringExtra("cmd").orEmpty()
                val text = intent?.getStringExtra("text").orEmpty()
                ; {
                    TryState.waitingFor = cmd; TryState.waitingApp = "adb"
                    TryState.answerFromApp(text)
                    UiState.say("ANSWER ${TryState.result?.intent ?: TryState.error}")
                }
            }
            else -> { { UiState.say("Unknown run=$run") } }
        }
        val wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hapuya:a6").apply { acquire(3 * 60 * 60 * 1000L) }
        UiState.job = UiState.scope.launch {
            try { block() }
            catch (e: CancellationException) { UiState.say("Stopped") }
            catch (e: Throwable) { UiState.say("Failed: ${e.javaClass.simpleName}: ${e.message}") }
            finally {
                UiState.progress = ""
                UiState.modelStatus = ModelFile.status(ctx)
                if (wake.isHeld) wake.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(run: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("a6", "A6 test runs", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "a6")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Hapuya A6: $run")
            .setContentText("Running. Open the app to see progress.")
            .setOngoing(true)
            .build()
        val type = when {
            run == "download" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(1, n, type)
    }

    companion object {
        fun start(ctx: Context, run: String) =
            ctx.startForegroundService(Intent(ctx, RunService::class.java).putExtra("run", run))
    }
}

/** The steps themselves; each runs inside [RunService]. */
object Jobs {
    suspend fun download(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        if (cm.isActiveNetworkMetered) UiState.say("Warning: this network is metered (mobile data?). 2.6 GB download.")
        val free = StatFs(ctx.getExternalFilesDir(null)!!.path).availableBytes
        check(free > ModelFile.SIZE + 500_000_000) { "Not enough storage: ${free / 1_000_000} MB free" }
        UiState.say("Downloading ${ModelFile.NAME} (${ModelFile.SIZE / 1_000_000} MB) from Hugging Face (pinned)")
        val t0 = System.currentTimeMillis()
        var lastLog = 0L
        ModelFile.download(ctx) { have ->
            UiState.progress = "Download: " + ModelFile.pct(have)
            if (have - lastLog > 250_000_000) { UiState.say("Download: " + ModelFile.pct(have)); lastLog = have }
        }
        UiState.say("Download complete in ${(System.currentTimeMillis() - t0) / 1000} s; checking hash")
        verify(ctx)
    }

    suspend fun verify(ctx: Context) {
        val t0 = System.currentTimeMillis()
        val ok = ModelFile.verify(ctx) { UiState.progress = "Hash check: " + ModelFile.pct(it) }
        UiState.say(
            if (ok) "Hash OK in ${(System.currentTimeMillis() - t0) / 1000} s: model ready"
            else "Hash MISMATCH: model will not be used. Delete and download again.",
        )
    }

    suspend fun runModel(ctx: Context, gpu: Boolean, reuse: Boolean = false) {
        check(ModelFile.isVerified(ctx)) { "Model not ready: ${ModelFile.status(ctx)}" }
        UiState.say("Loading model on ${if (gpu) "GPU" else "CPU"}...")
        UiState.progress = "Loading model"
        val mode = UiState.mode
        val constrained = UiState.constrained && mode.json // a schema can't apply to the one-line format
        val tag = if (mode == com.hapuya.tools.intenteval.Mode.SPLIT) "" else mode.suffix
        LiteRtRunner(ModelFile.file(ctx).path, ctx.cacheDir.path, gpu, constrained, Bench.schema, reuse, tag).use { runner ->
            UiState.say("Loaded in ${runner.loadMs} ms")
            UiState.progress = "Running ${UiState.set} set on ${runner.label}"
            Bench.run(ctx, runner, UiState.set, mode, UiState::say)
        }
    }

    suspend fun nanoStatus(): Int {
        val s = try { Generation.getClient().checkStatus() } catch (e: Exception) {
            UiState.nanoStatus = "Check failed: ${e.javaClass.simpleName}: ${e.message}"
            UiState.say(UiState.nanoStatus); return FeatureStatus.UNAVAILABLE
        }
        UiState.nanoStatus = when (s) {
            FeatureStatus.AVAILABLE -> "AVAILABLE: built-in AI ready"
            FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE: phone supports it; tap Download"
            FeatureStatus.DOWNLOADING -> "DOWNLOADING: the system is fetching it"
            FeatureStatus.UNAVAILABLE -> "UNAVAILABLE: this phone has no built-in AI"
            else -> "Unknown status $s"
        }
        UiState.say("Gemini Nano: ${UiState.nanoStatus}")
        return s
    }

    suspend fun downloadNano() {
        Generation.getClient().download().collect { UiState.say("Nano download: $it") }
        nanoStatus()
    }

    suspend fun runNano(ctx: Context) {
        if (nanoStatus() != FeatureStatus.AVAILABLE) return
        NanoRunner(Generation.getClient()).use { Bench.run(ctx, it, UiState.set, UiState.mode, UiState::say) }
    }
}
