package com.hapuya.tools.intenteval

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Outcome of one provider call. [intent] is null when the call or parsing failed. */
data class CallResult(
    val intent: Intent?,
    val latencyMs: Long,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Double = 0.0,
    val error: String? = null,
)

interface IntentProvider {
    val label: String
    fun parse(phrase: Phrase): CallResult
}

private val mapper = ObjectMapper()

/** Answers with the expected values. Checks the harness end to end without any API spend. */
class OracleProvider : IntentProvider {
    override val label = "oracle"
    override fun parse(phrase: Phrase) = CallResult(phrase.expected, latencyMs = 0)
}

/** No model: the intent is guessed from the words and [CrossCheck] reads the rest (A6, 8 Nov 2026). */
class RulesProvider : IntentProvider {
    override val label = "rules-only"
    override fun parse(phrase: Phrase): CallResult {
        val start = System.nanoTime()
        val got = Resolver.resolve(CrossCheck.fromWords(phrase.phrase))
        return CallResult(got, (System.nanoTime() - start) / 1_000_000)
    }
}

/**
 * Claude through the official Java SDK, with the response constrained to
 * intent-schema.json via structured outputs. Reads ANTHROPIC_API_KEY.
 */
class ClaudeProvider(
    private val model: String,
    private val effort: OutputConfig.Effort,
    private val mode: Mode = Mode.DIRECT,
    private val crossCheck: Boolean = true,
) : IntentProvider {
    override val label = "claude:$model:${effort.asString()}${mode.suffix}" + if (crossCheck) "" else "-nocheck"

    init { require(mode.schema.isNotEmpty()) { "The claude provider uses structured JSON output; use --mode direct or split" } }

    private val client: AnthropicClient = AnthropicOkHttpClient.fromEnv()
    private val system = resource(mode.prompt)
    private val schema: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder().apply {
        @Suppress("UNCHECKED_CAST")
        val map = mapper.readValue(resource(mode.schema), Map::class.java) as Map<String, Any>
        map.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
    }.build()

    /** USD per million input/output tokens (prompts under 100K tokens). */
    private val price = PRICES[model]

    override fun parse(phrase: Phrase): CallResult {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4000L)
            .system(system)
            .outputConfig(
                OutputConfig.builder()
                    .effort(effort)
                    .format(JsonOutputFormat.builder().schema(schema).build())
                    .build(),
            )
            .addUserMessage(mode.userMessage(phrase.phrase))
            .build()

        val start = System.nanoTime()
        return try {
            val msg = client.messages().create(params)
            val ms = (System.nanoTime() - start) / 1_000_000
            val inTok = msg.usage().inputTokens()
            val outTok = msg.usage().outputTokens()
            val cost = price?.let { (pin, pout) -> (inTok * pin + outTok * pout) / 1_000_000.0 } ?: 0.0
            if (msg.stopReason().orElse(null) == StopReason.REFUSAL) {
                return CallResult(null, ms, inTok, outTok, cost, error = "refusal")
            }
            val text = msg.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
            CallResult(mode.decode(text, if (crossCheck) phrase.phrase else ""), ms, inTok, outTok, cost)
        } catch (e: RateLimitException) {
            CallResult(null, elapsed(start), error = "rate_limited")
        } catch (e: AnthropicServiceException) {
            CallResult(null, elapsed(start), error = "api_error:${e.statusCode()}")
        } catch (e: AnthropicIoException) {
            CallResult(null, elapsed(start), error = "network:${e.message}")
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            CallResult(null, elapsed(start), error = "bad_json")
        }
    }

    companion object {
        val PRICES = mapOf(
            "claude-haiku-5-5" to (0.10 to 0.50),
            "claude-sonnet-5-5" to (2.00 to 10.00),
            "claude-opus-5-5" to (4.00 to 20.00),
        )
    }
}

/**
 * The second provider, through any OpenAI-style `/chat/completions` endpoint
 * (configured entirely by environment variables, so no vendor is baked in):
 *   EVAL_ALT_BASE_URL, EVAL_ALT_API_KEY, EVAL_ALT_MODEL,
 *   EVAL_ALT_PRICE_IN / EVAL_ALT_PRICE_OUT (USD per million tokens, optional),
 *   EVAL_ALT_REASONING_EFFORT (optional, e.g. "none" to turn thinking off),
 *   EVAL_ALT_TIMEOUT_S (optional, default 60; local CPU models need more).
 * The schema goes in the prompt and JSON mode is requested.
 */
class AltProvider(
    env: Map<String, String> = System.getenv(),
    private val mode: Mode = Mode.DIRECT,
    private val crossCheck: Boolean = true,
) : IntentProvider {
    private val baseUrl = env.req("EVAL_ALT_BASE_URL").trimEnd('/')
    private val apiKey = env.req("EVAL_ALT_API_KEY")
    private val model = env.req("EVAL_ALT_MODEL")
    private val priceIn = env["EVAL_ALT_PRICE_IN"]?.toDoubleOrNull()
    private val priceOut = env["EVAL_ALT_PRICE_OUT"]?.toDoubleOrNull()
    private val effort = env["EVAL_ALT_REASONING_EFFORT"]?.takeIf { it.isNotBlank() }
    private val timeoutS = env["EVAL_ALT_TIMEOUT_S"]?.toLongOrNull() ?: 60
    override val label = "alt:$model" + (effort?.let { "-effort_$it" } ?: "") + mode.suffix + if (crossCheck) "" else "-nocheck"

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    private val system = mode.systemPrompt()

    override fun parse(phrase: Phrase): CallResult {
        val body = mapper.writeValueAsString(
            buildMap {
                put("model", model)
                if (mode.json) put("response_format", mapOf("type" to "json_object"))
                effort?.let { put("reasoning_effort", it) }
                put("messages", listOf(
                    mapOf("role" to "system", "content" to system),
                    mapOf("role" to "user", "content" to mode.userMessage(phrase.phrase)),
                ))
            },
        )
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/chat/completions"))
            .timeout(Duration.ofSeconds(timeoutS))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val start = System.nanoTime()
        return try {
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            val ms = elapsed(start)
            if (resp.statusCode() !in 200..299) return CallResult(null, ms, error = "http_${resp.statusCode()}")
            val n = mapper.readTree(resp.body())
            val inTok = n.path("usage").path("prompt_tokens").asLong()
            val outTok = n.path("usage").path("completion_tokens").asLong()
            val cost = if (priceIn != null && priceOut != null) (inTok * priceIn + outTok * priceOut) / 1_000_000.0 else 0.0
            val text = n.path("choices").path(0).path("message").path("content").asText()
            CallResult(mode.decode(text, if (crossCheck) phrase.phrase else ""), ms, inTok, outTok, cost)
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            CallResult(null, elapsed(start), error = "bad_json")
        } catch (e: IllegalArgumentException) {
            CallResult(null, elapsed(start), error = "bad_format")
        } catch (e: java.io.IOException) {
            CallResult(null, elapsed(start), error = "network:${e.javaClass.simpleName}")
        }
    }

    private fun Map<String, String>.req(k: String) = requireNotNull(this[k]) { "Set $k for the alt provider" }
}

private fun elapsed(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000
