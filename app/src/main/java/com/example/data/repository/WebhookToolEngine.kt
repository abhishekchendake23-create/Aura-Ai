package com.example.data.repository

import com.example.data.local.CustomWebhookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder

data class WebhookExecutionResult(
    val webhookName: String,
    val success: Boolean,
    val statusCode: Int,
    val responseText: String
)

object WebhookToolEngine {

    private const val MAX_RESPONSE_CHARS = 3000
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun isSafePublicWebhookUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        val uriSafe = trimmed.replace(Regex("\\{\\{[^}]*\\}\\}"), "sample")
        val uri = runCatching { URI(uriSafe) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false
        val host = uri.host?.lowercase()?.trim() ?: return false
        if (host.isBlank()) return false
        if (host == "localhost" || host == "0.0.0.0" || host == "::1" || host == "[::1]") return false
        if (host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost")) return false
        if (isPrivateOrReservedIpLiteral(host)) return false

        // Best-effort DNS check if resolvable on device
        val resolved = runCatching { InetAddress.getAllByName(host) }.getOrNull()
        if (resolved != null && resolved.any { addr ->
                addr.isAnyLocalAddress ||
                    addr.isLoopbackAddress ||
                    addr.isLinkLocalAddress ||
                    addr.isSiteLocalAddress ||
                    addr.isMulticastAddress ||
                    isPrivateOrReservedIpLiteral(addr.hostAddress.orEmpty())
            }
        ) {
            return false
        }
        return true
    }

    fun isPrivateOrReservedIpLiteral(rawHost: String): Boolean {
        val host = rawHost.trim().trim('[', ']').lowercase()
        if (host.startsWith("fe80:") || host.startsWith("fc00:") || host.startsWith("fd")) return true
        val ipv4Parts = host.split('.')
        if (ipv4Parts.size == 4 && ipv4Parts.all { it.toIntOrNull() in 0..255 }) {
            val a = ipv4Parts[0].toInt()
            val b = ipv4Parts[1].toInt()
            return when {
                a == 10 -> true
                a == 127 -> true
                a == 0 -> true
                a == 169 && b == 254 -> true
                a == 172 && b in 16..31 -> true
                a == 192 && b == 168 -> true
                a == 100 && b in 64..127 -> true // Carrier-grade NAT
                else -> false
            }
        }
        val literalAddr = runCatching {
            if (host.matches(Regex("^[0-9.]+$"))) Inet4Address.getByName(host) else null
        }.getOrNull()
        return literalAddr?.let { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress } ?: false
    }

    fun substituteQueryTemplate(template: String, query: String, urlEncode: Boolean): String {
        val safeValue = if (urlEncode) {
            URLEncoder.encode(query, Charsets.UTF_8.name())
        } else {
            val quoted = JSONObject.quote(query)
            quoted.substring(1, quoted.length - 1)
        }
        return template.replace(Regex("\\{\\{\\s*query\\s*\\}\\}", RegexOption.IGNORE_CASE), safeValue)
    }

    suspend fun executeWebhook(
        webhook: CustomWebhookEntity,
        queryInput: String,
        httpClient: OkHttpClient
    ): WebhookExecutionResult = withContext(Dispatchers.IO) {
        if (!isSafePublicWebhookUrl(webhook.endpointUrl)) {
            return@withContext WebhookExecutionResult(
                webhookName = webhook.name,
                success = false,
                statusCode = 0,
                responseText = "Blocked unsafe or local/private webhook URL."
            )
        }

        val resolvedUrl = substituteQueryTemplate(webhook.endpointUrl.trim(), queryInput.trim(), urlEncode = true)
        val reqBuilder = Request.Builder().url(resolvedUrl)

        if (webhook.headersJson.isNotBlank()) {
            runCatching {
                val obj = JSONObject(webhook.headersJson)
                obj.keys().forEach { k ->
                    val v = obj.optString(k)
                    if (k.isNotBlank() && v.isNotBlank()) {
                        reqBuilder.header(k, v)
                    }
                }
            }
        }

        if (webhook.httpMethod.equals("POST", ignoreCase = true)) {
            val bodyStr = if (webhook.bodyTemplate.isNotBlank()) {
                substituteQueryTemplate(webhook.bodyTemplate, queryInput.trim(), urlEncode = false)
            } else {
                JSONObject().put("query", queryInput.trim()).toString()
            }
            reqBuilder.post(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            reqBuilder.get()
        }

        return@withContext runCatching {
            withTimeout(10_000L) {
                httpClient.newCall(reqBuilder.build()).execute().use { res ->
                    val bodyText = res.body?.string().orEmpty().trim()
                    val clipped = if (bodyText.length > MAX_RESPONSE_CHARS) {
                        bodyText.take(MAX_RESPONSE_CHARS) + "\n... [truncated]"
                    } else {
                        bodyText
                    }
                    WebhookExecutionResult(
                        webhookName = webhook.name,
                        success = res.isSuccessful,
                        statusCode = res.code,
                        responseText = clipped.ifBlank { "HTTP ${res.code} (empty body)" }
                    )
                }
            }
        }.getOrElse { err ->
            WebhookExecutionResult(
                webhookName = webhook.name,
                success = false,
                statusCode = 0,
                responseText = err.localizedMessage ?: "Webhook call failed"
            )
        }
    }

    suspend fun gatherWebhookToolContext(
        prompt: String,
        enabledWebhooks: List<CustomWebhookEntity>,
        httpClient: OkHttpClient
    ): Pair<String, String> = coroutineScope {
        if (enabledWebhooks.isEmpty()) return@coroutineScope "" to ""
        val activeTools = enabledWebhooks.take(3)
        val results = activeTools.map { hook ->
            async(Dispatchers.IO) {
                executeWebhook(hook, prompt.take(300), httpClient)
            }
        }.awaitAll()

        val logLines = results.joinToString("\n") { res ->
            val icon = if (res.success) "✓" else "!"
            "  • [$icon] Tool '${res.webhookName}' -> HTTP ${res.statusCode}"
        }

        val promptBlock = buildString {
            append("### CUSTOM WEBHOOK / REST TOOL RESPONSES (LIVE)\n")
            results.forEach { res ->
                append("Tool: ${res.webhookName} (Status: ${if (res.success) "OK" else "ERR"} ${res.statusCode})\n")
                append("```\n${res.responseText}\n```\n\n")
            }
        }.trim()

        logLines to promptBlock
    }
}
