package com.hapuya.spike.onphoneai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The user's own AI (ADR 0003), test build. Three kinds, each used only after the
 * user allows it:
 *  - an AI app that runs a local server on this phone (Ollama in Termux, llama.cpp,
 *    and similar), found by looking at a few local ports;
 *  - any OpenAI-compatible address: the user's own cloud key (OpenAI, Gemini, Claude)
 *    or a PC on the home network (Ollama, LM Studio);
 *  - an AI app such as ChatGPT or Gemini: Android has no way to ask it and read the
 *    answer, so Hapuya opens it with the command and the user shares the answer back.
 * Only the command text is sent. The model only reports codes; code decides.
 */
object OwnAi {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("own_ai", Context.MODE_PRIVATE)

    data class Server(val baseUrl: String, val model: String, val hasKey: Boolean)

    fun server(ctx: Context): Server? {
        val p = prefs(ctx)
        val url = p.getString("url", null) ?: return null
        return Server(url, p.getString("model", "").orEmpty(), p.contains("key"))
    }

    /** Saves the address and model; [key] is stored encrypted with a phone-only key (Android Keystore). */
    fun saveServer(ctx: Context, url: String, model: String, key: String?) {
        val e = prefs(ctx).edit().putString("url", url.trim().trimEnd('/')).putString("model", model.trim())
        if (!key.isNullOrBlank()) e.putString("key", SecretBox.seal(key.trim())) else if (key != null) e.remove("key")
        e.apply()
    }

    fun key(ctx: Context): String? = prefs(ctx).getString("key", null)?.let { runCatching { SecretBox.open(it) }.getOrNull() }

    /** Consent is per connection ("server:<host>", "app:<package>", "scan"), and can be taken back. */
    fun allowed(ctx: Context, what: String) = prefs(ctx).getBoolean("consent:$what", false)
    fun allow(ctx: Context, what: String) = prefs(ctx).edit().putBoolean("consent:$what", true).apply()

    /** Forgets the address, model, key and every consent. */
    fun disconnect(ctx: Context) = prefs(ctx).edit().clear().apply()

    fun host(url: String) = runCatching { URL(url).host }.getOrDefault(url)
    fun isOnPhone(url: String) = host(url).let { it == "127.0.0.1" || it == "localhost" }

    // --- AI on this phone (local server) ---

    data class Found(val name: String, val baseUrl: String, val models: List<String>)

    private val LOCAL = listOf(11434 to "Ollama", 1234 to "LM Studio", 8080 to "llama.cpp server", 8000 to "Local AI server")

    /** Looks for an AI server running on this phone. Nothing leaves the phone. */
    fun scanPhone(): List<Found> = LOCAL.mapNotNull { (port, name) ->
        val url = "http://127.0.0.1:$port/v1"
        runCatching { Found(name, url, models(url, null, connectMs = 700, readMs = 2000)) }.getOrNull()
    }

    // --- Any OpenAI-compatible address ---

    private fun open(url: String, key: String?, connectMs: Int, readMs: Int) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = connectMs
        readTimeout = readMs
        setRequestProperty("Accept", "application/json")
        if (!key.isNullOrBlank()) {
            setRequestProperty("Authorization", "Bearer $key")
            // Claude's API reads these; the others ignore them.
            setRequestProperty("x-api-key", key)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
    }

    private fun HttpURLConnection.body(): String {
        val code = responseCode
        val text = (if (code in 200..299) inputStream else errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val why = when (code) {
                401, 403 -> "the key was refused"
                404 -> "address or model not found"
                429 -> "too many requests or no credit left"
                else -> "error"
            }
            throw IllegalStateException("HTTP $code: $why")
        }
        return text
    }

    /** Model names the address offers (GET /models). */
    fun models(baseUrl: String, key: String?, connectMs: Int = 5000, readMs: Int = 15000): List<String> {
        val c = open("${baseUrl.trimEnd('/')}/models", key, connectMs, readMs)
        try {
            val data = JSONObject(c.body()).optJSONArray("data") ?: JSONArray()
            return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
        } finally { c.disconnect() }
    }

    /** One chat call (POST /chat/completions). Returns the answer text. */
    fun chat(baseUrl: String, model: String, key: String?, system: String, user: String): String {
        val c = open("${baseUrl.trimEnd('/')}/chat/completions", key, 8000, 60000)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
                .put("model", model)
                .put("temperature", 0)
                .put("max_tokens", 200)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val msg = JSONObject(c.body()).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            return msg.optString("content")
        } finally { c.disconnect() }
    }

    // --- AI apps on this phone ---

    data class AiApp(val pkg: String, val name: String)

    private val KNOWN_APPS = listOf(
        "com.openai.chatgpt" to "ChatGPT", "com.google.android.apps.bard" to "Gemini", "com.anthropic.claude" to "Claude",
        "com.microsoft.copilot" to "Copilot", "ai.perplexity.app.android" to "Perplexity", "com.deepseek.chat" to "DeepSeek",
    )

    fun installedApps(ctx: Context): List<AiApp> = KNOWN_APPS.filter { (pkg, _) ->
        try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false }
    }.map { AiApp(it.first, it.second) }

    fun handOff(pkg: String, text: String): Intent =
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg)
}

/** A [Runner] for the user's own AI at an OpenAI-compatible address. */
class ServerRunner(private val baseUrl: String, private val model: String, private val key: String?) : Runner {
    override val label = "your-ai:$model"
    override val loadMs = 0L
    override fun generate(system: String, user: String) = OwnAi.chat(baseUrl, model, key, system, user)
    override fun close() {}
}

/** Encrypts the user's key with an AES key that never leaves the phone's Keystore. */
object SecretBox {
    private const val ALIAS = "hapuya_own_ai"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun open(sealed: String): String {
        val all = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12)) }
        return String(c.doFinal(all, 12, all.size - 12))
    }
}
