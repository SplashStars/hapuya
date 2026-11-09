package com.hapuya.spike.onphoneai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The downloaded on-phone model: Gemma 4 E2B in LiteRT-LM format, Apache-2.0,
 * from Google's litert-community page on Hugging Face. The URL is pinned to one
 * commit and the file is checked against its published SHA-256, so a changed or
 * damaged file is never loaded. Stored in the app's own folder (no permission needed,
 * removed on uninstall).
 */
object ModelFile {
    const val NAME = "gemma-4-E2B-it.litertlm"
    private const val COMMIT = "3d48132a00cf76de57d926de30f5c5c5ed9b8c0d"
    const val URL_PINNED = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/$COMMIT/$NAME"
    const val SIZE = 2_588_147_712L
    const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    private fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null), "models").apply { mkdirs() }
    fun file(ctx: Context) = File(dir(ctx), NAME)
    fun partFile(ctx: Context) = File(dir(ctx), "$NAME.part")
    private fun verifiedMarker(ctx: Context) = File(dir(ctx), "$NAME.verified")

    fun isVerified(ctx: Context) = file(ctx).length() == SIZE && verifiedMarker(ctx).exists()

    fun status(ctx: Context): String = when {
        isVerified(ctx) -> "Ready (hash checked)"
        file(ctx).exists() -> "Downloaded, not yet checked (${file(ctx).length() / 1_000_000} MB)"
        partFile(ctx).exists() -> "Partly downloaded: ${pct(partFile(ctx).length())}"
        else -> "Not downloaded (needs ${SIZE / 1_000_000} MB)"
    }

    fun pct(bytes: Long) = "%.1f%% of %.2f GB".format(bytes * 100.0 / SIZE, SIZE / 1e9)

    /** Downloads, resuming a partial file with an HTTP Range request, then verifies. */
    suspend fun download(ctx: Context, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val part = partFile(ctx)
        var have = if (part.exists()) part.length() else 0L
        if (have < SIZE) {
            val conn = (URL(URL_PINNED).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = conn.responseCode
            when {
                code == 206 -> Unit
                code == 200 -> have = 0 // the server ignored the range: start again
                else -> error("Download failed: HTTP $code")
            }
            FileOutputStream(part, have > 0).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 20)
                    var last = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        val now = System.currentTimeMillis()
                        if (now - last > 500) { progress(have); last = now }
                    }
                }
            }
            progress(have)
        }
        check(part.length() == SIZE) { "Download incomplete: ${part.length()} of $SIZE bytes. Tap Download to resume." }
        val target = file(ctx)
        target.delete()
        check(part.renameTo(target)) { "Could not rename the downloaded file" }
    }

    /** SHA-256 of the whole file against the published hash. */
    suspend fun verify(ctx: Context, progress: (Long) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val f = file(ctx)
        if (f.length() != SIZE) return@withContext false
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        f.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            var last = 0L
            while (true) {
                ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                val now = System.currentTimeMillis()
                if (now - last > 500) { progress(done); last = now }
            }
        }
        val ok = md.digest().joinToString("") { "%02x".format(it) } == SHA256
        if (ok) verifiedMarker(ctx).writeText(SHA256) else verifiedMarker(ctx).delete()
        ok
    }

    fun delete(ctx: Context) {
        file(ctx).delete(); partFile(ctx).delete(); verifiedMarker(ctx).delete()
    }
}
