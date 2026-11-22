package com.example.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val publishedAt: String? = null,
    val sourceName: String? = null
)

data class LiveWebContext(
    val cleanedQuery: String,
    val executedQueries: List<String>,
    val results: List<WebSearchResult>,
    val fetchedPageExcerpts: List<Pair<String, String>>,
    val formattedPromptBlock: String,
    val sourceCitations: Map<String, SourceCitation>
)

class LiveWebSearchEngine(baseHttpClient: OkHttpClient) {

    private val searchClient: OkHttpClient = baseHttpClient.newBuilder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .writeTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun gatherLiveContext(
        queries: List<String>,
        explicitUrls: List<String> = emptyList(),
        maxResults: Int = 7,
        exaApiKey: String = "",
        preferLiveCrawl: Boolean = true
    ): LiveWebContext = withContext(Dispatchers.IO) {
        val normalizedQueries = queries
            .map { extractCleanSearchQuery(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)

        val primaryQuery = normalizedQueries.firstOrNull().orEmpty()
        val allResults = linkedMapOf<String, WebSearchResult>()
        val cleanExaKey = exaApiKey.trim()
        val exaUrlContents = linkedMapOf<String, String>()

        // 1. Primary Engine: Exa Neural + Live Web Search API when EXA_API_KEY is configured
        if (cleanExaKey.isNotBlank() && (normalizedQueries.isNotEmpty() || explicitUrls.isNotEmpty())) {
            withTimeoutOrNull(11_000L) {
                coroutineScope {
                    val exaSearchTasks = normalizedQueries.map { q ->
                        async {
                            runCatching {
                                searchExaApi(
                                    apiKey = cleanExaKey,
                                    query = q,
                                    maxResults = maxResults,
                                    preferLiveCrawl = preferLiveCrawl
                                )
                            }.getOrDefault(emptyList())
                        }
                    }
                    val exaContentsTask: kotlinx.coroutines.Deferred<List<WebSearchResult>>? =
                        if (explicitUrls.isNotEmpty()) {
                            async {
                                runCatching {
                                    fetchExaContentsApi(apiKey = cleanExaKey, urls = explicitUrls)
                                }.getOrDefault(emptyList())
                            }
                        } else {
                            null
                        }

                    exaSearchTasks.forEach { task ->
                        task.await().forEach { item ->
                            val key = normalizeUrlKey(item.url)
                            val existing = allResults[key]
                            if (existing == null || item.snippet.length > existing.snippet.length) {
                                allResults[key] = item
                            }
                        }
                    }

                    exaContentsTask?.await()?.forEach { item ->
                        if (item.snippet.isNotBlank()) {
                            exaUrlContents[item.url] = item.snippet.take(1800)
                        }
                        val key = normalizeUrlKey(item.url)
                        if (!allResults.containsKey(key)) {
                            allResults[key] = item
                        }
                    }
                }
            }
        }

        // 2. Multi-Source Fallback / Enrichment (if Exa key absent or returned sparse results)
        if (normalizedQueries.isNotEmpty() && allResults.size < 3) {
            withTimeoutOrNull(10_500L) {
                coroutineScope {
                    val searchTasks = normalizedQueries.flatMap { q ->
                        listOf(
                            async { runCatching { searchDuckDuckGoHtml(q, maxResults = 5) }.getOrDefault(emptyList()) },
                            async { runCatching { searchNewsRss(q, maxResults = 4) }.getOrDefault(emptyList()) },
                            async { runCatching { searchWikipedia(q, maxResults = 2) }.getOrDefault(emptyList()) }
                        )
                    }
                    searchTasks.forEach { task ->
                        task.await().forEach { item ->
                            val key = normalizeUrlKey(item.url)
                            val existing = allResults[key]
                            if (existing == null || item.snippet.length > existing.snippet.length) {
                                allResults[key] = item
                            }
                        }
                    }

                    if (allResults.size < 2 && primaryQuery.isNotBlank()) {
                        runCatching { searchDuckDuckGoLite(primaryQuery, maxResults = 5) }
                            .getOrDefault(emptyList())
                            .forEach { item ->
                                val key = normalizeUrlKey(item.url)
                                if (!allResults.containsKey(key)) {
                                    allResults[key] = item
                                }
                            }
                    }
                }
            }
        }

        val rankedResults = allResults.values.take(maxResults)

        // Fetch readable page text for explicit URLs not already retrieved via Exa, plus top direct results with short snippets
        val urlsToFetch = buildList {
            explicitUrls
                .filter { !exaUrlContents.containsKey(it) }
                .forEach { add(it) }
            rankedResults
                .asSequence()
                .filter { it.snippet.length < 320 }
                .map { it.url }
                .filter { !it.contains("news.google.com/rss/articles", ignoreCase = true) }
                .filter { !it.contains("wikipedia.org/wiki/", ignoreCase = true) }
                .take(2)
                .forEach { add(it) }
        }.distinctBy { normalizeUrlKey(it) }.take(3)

        val directFetchedExcerpts = if (urlsToFetch.isNotEmpty()) {
            withTimeoutOrNull(6_000L) {
                coroutineScope {
                    urlsToFetch.map { url ->
                        async {
                            val excerpt = runCatching { fetchPageSummary(url, maxChars = 1600) }.getOrNull()
                            if (!excerpt.isNullOrBlank()) url to excerpt else null
                        }
                    }.mapNotNull { it.await() }
                }
            }.orEmpty()
        } else {
            emptyList()
        }

        val pageExcerpts = buildList {
            exaUrlContents.forEach { (url, text) -> add(url to text) }
            directFetchedExcerpts.forEach { pair ->
                if (none { normalizeUrlKey(it.first) == normalizeUrlKey(pair.first) }) {
                    add(pair)
                }
            }
        }

        val sourceCitations = linkedMapOf<String, SourceCitation>()
        rankedResults.forEach { item ->
            if (item.url.isNotBlank()) {
                val label = if (!item.sourceName.isNullOrBlank() && !item.title.contains(item.sourceName, ignoreCase = true)) {
                    "${item.sourceName}: ${item.title}"
                } else {
                    item.title
                }
                sourceCitations[item.url] = SourceCitation(
                    title = label.ifBlank { item.url },
                    uri = item.url
                )
            }
        }
        explicitUrls.forEach { url ->
            if (!sourceCitations.containsKey(url)) {
                sourceCitations[url] = SourceCitation(title = url, uri = url)
            }
        }

        val fetchedTimestamp = SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.US).format(Date())
        val promptBlock = buildString {
            if (rankedResults.isNotEmpty() || pageExcerpts.isNotEmpty()) {
                append("### LIVE WEB SEARCH RESULTS (Fetched $fetchedTimestamp)\n")
                append("Use these live fetched results as primary ground truth for current dates, facts, versions, prices, scores, and officeholders.\n\n")
                rankedResults.forEachIndexed { idx, item ->
                    append("[${idx + 1}] ${item.title}")
                    if (!item.sourceName.isNullOrBlank()) append(" (${item.sourceName})")
                    if (!item.publishedAt.isNullOrBlank()) append(" · ${item.publishedAt}")
                    append("\nURL: ${item.url}\n")
                    if (item.snippet.isNotBlank()) {
                        append("Content: ${item.snippet}\n")
                    }
                    append("\n")
                }
                if (pageExcerpts.isNotEmpty()) {
                    append("### FETCHED LIVE PAGE EXCERPTS\n")
                    pageExcerpts.forEach { (url, excerpt) ->
                        append("Source Page ($url):\n$excerpt\n\n")
                    }
                }
            }
        }.trim()

        LiveWebContext(
            cleanedQuery = primaryQuery,
            executedQueries = normalizedQueries,
            results = rankedResults,
            fetchedPageExcerpts = pageExcerpts,
            formattedPromptBlock = promptBlock,
            sourceCitations = sourceCitations
        )
    }

    private fun searchExaApi(
        apiKey: String,
        query: String,
        maxResults: Int,
        preferLiveCrawl: Boolean
    ): List<WebSearchResult> {
        val contentsObj = JSONObject().apply {
            put("text", JSONObject().put("maxCharacters", 1800))
            put(
                "highlights",
                JSONObject().apply {
                    put("numSentences", 3)
                    put("highlightsPerUrl", 2)
                }
            )
            put("livecrawl", if (preferLiveCrawl) "preferred" else "fallback")
        }
        val payload = JSONObject().apply {
            put("query", query)
            put("type", "auto")
            put("numResults", maxResults.coerceIn(1, 10))
            put("contents", contentsObj)
        }
        val req = Request.Builder()
            .url(EXA_SEARCH_URL)
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(EXA_JSON_MEDIA_TYPE))
            .build()

        searchClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            val body = res.body?.string().orEmpty()
            return parseExaResultsJson(body, maxResults = maxResults)
        }
    }

    private fun fetchExaContentsApi(
        apiKey: String,
        urls: List<String>
    ): List<WebSearchResult> {
        if (urls.isEmpty()) return emptyList()
        val payload = JSONObject().apply {
            put("urls", JSONArray(urls.take(3)))
            put("text", JSONObject().put("maxCharacters", 2000))
            put("livecrawl", "preferred")
        }
        val req = Request.Builder()
            .url(EXA_CONTENTS_URL)
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(EXA_JSON_MEDIA_TYPE))
            .build()

        searchClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            val body = res.body?.string().orEmpty()
            return parseExaResultsJson(body, maxResults = urls.size)
        }
    }

    private fun searchDuckDuckGoHtml(query: String, maxResults: Int): List<WebSearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encoded&kl=wt-wt"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://html.duckduckgo.com/")
            .get()
            .build()

        searchClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            val html = res.body?.string().orEmpty()
            return parseDuckDuckGoHtml(html, maxResults = maxResults)
        }
    }

    private fun searchDuckDuckGoLite(query: String, maxResults: Int): List<WebSearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://lite.duckduckgo.com/lite/?q=$encoded"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()

        searchClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            val html = res.body?.string().orEmpty()
            return parseDuckDuckGoLiteHtml(html, maxResults = maxResults)
        }
    }

    private fun searchNewsRss(query: String, maxResults: Int): List<WebSearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val indiaBias = hasIndiaOrRegionalCue(query)
        val url = if (indiaBias) {
            "https://news.google.com/rss/search?q=$encoded&hl=en-IN&gl=IN&ceid=IN:en"
        } else {
            "https://news.google.com/rss/search?q=$encoded&hl=en-US&gl=US&ceid=US:en"
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "application/rss+xml, application/xml, text/xml")
            .get()
            .build()

        searchClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            val xml = res.body?.string().orEmpty()
            return parseNewsRssXml(xml, maxResults = maxResults)
        }
    }

    private fun searchWikipedia(query: String, maxResults: Int): List<WebSearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srprop=snippet|timestamp&format=json&utf8=1&srlimit=$maxResults&srsearch=$encoded"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "AuraAI/1.0 (Abhynex Mobile Assistant)")
            .header("Accept", "application/json")
            .get()
            .build()

        searchClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            val body = res.body?.string().orEmpty()
            return parseWikipediaSearchJson(body, maxResults = maxResults)
        }
    }

    fun fetchPageSummary(url: String, maxChars: Int = 1800): String? {
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()

        searchClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val contentType = res.header("Content-Type").orEmpty().lowercase()
            if (contentType.isNotBlank() &&
                !contentType.contains("text") &&
                !contentType.contains("html") &&
                !contentType.contains("json") &&
                !contentType.contains("xml")
            ) {
                return null
            }
            val raw = res.body?.charStream()?.use { reader ->
                val buffer = CharArray(8192)
                val sb = StringBuilder()
                var total = 0
                while (total < 120_000) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    sb.append(buffer, 0, read)
                    total += read
                }
                sb.toString()
            }.orEmpty()
            if (raw.isBlank()) return null

            return if (contentType.contains("json") || contentType.contains("text/plain")) {
                raw.replace(Regex("\\s+"), " ").trim().take(maxChars)
            } else {
                extractReadableTextFromHtml(raw, maxChars = maxChars)
            }
        }
    }

    companion object {
        private const val EXA_SEARCH_URL = "https://api.exa.ai/search"
        private const val EXA_CONTENTS_URL = "https://api.exa.ai/contents"
        private val EXA_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun parseExaResultsJson(jsonString: String, maxResults: Int = 7): List<WebSearchResult> {
            if (jsonString.isBlank()) return emptyList()
            return runCatching {
                val root = JSONObject(jsonString)
                val items = root.optJSONArray("results") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until minOf(items.length(), maxResults)) {
                        val obj = items.optJSONObject(i) ?: continue
                        val url = obj.optString("url").trim()
                        if (!url.startsWith("http", ignoreCase = true)) continue
                        val rawTitle = obj.optString("title").trim()
                        val hostDomain = runCatching {
                            java.net.URI(url).host?.removePrefix("www.")
                        }.getOrNull()?.takeIf { it.isNotBlank() }

                        val publishedDate = obj.optString("publishedDate").trim().take(10).ifBlank { null }
                        val highlightsArr = obj.optJSONArray("highlights")
                        val highlights = buildList {
                            if (highlightsArr != null) {
                                for (j in 0 until highlightsArr.length()) {
                                    val h = highlightsArr.optString(j).replace(Regex("\\s+"), " ").trim()
                                    if (h.isNotBlank()) add(h)
                                }
                            }
                        }.joinToString(" … ")

                        val fullText = obj.optString("text")
                            .replace(Regex("\\s+"), " ")
                            .trim()

                        val combinedSnippet = when {
                            highlights.isNotBlank() && fullText.isNotBlank() ->
                                "$highlights\nExcerpt: ${fullText.take(1200)}".take(1650)
                            fullText.isNotBlank() -> fullText.take(1650)
                            highlights.isNotBlank() -> highlights.take(1200)
                            else -> ""
                        }

                        add(
                            WebSearchResult(
                                title = rawTitle.ifBlank { hostDomain ?: url },
                                url = url,
                                snippet = combinedSnippet,
                                publishedAt = publishedDate,
                                sourceName = hostDomain
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

        private val SLASH_COMMAND_PREFIXES = listOf(
            "Conduct deep multi-angle research, cross-verify facts, compare trade-offs in Markdown tables, and produce an authoritative cited report on:",
            "Search live web sources and verify the latest authoritative details with a structured breakdown for:",
            "Architect a complete, production-ready solution with clean file separation, defensive error handling, concurrency safety, and verification tests for:",
            "Perform a ruthless Principal Engineer audit on this issue/code. Identify root causes, edge cases, crashes, memory leaks, or security flaws, then provide a clean unified diff and drop-in fixed code:",
            "Write and execute Python in the live sandbox to compute and verify the exact answer step-by-step for:",
            "Solve rigorously step-by-step using KaTeX display blocks ($$...$$), verify with an independent sanity check, and box the final result:",
            "Write a complete, deterministic unit test suite covering normal execution, boundary edge cases, concurrency, and failure paths for:",
            "Explain clearly in crisp, natural Marathi (or Romanized Marathi matching my style) with practical examples:"
        )

        fun extractUrls(text: String): List<String> {
            return Regex("https?://[^\\s<>\"'\\)\\]]+", RegexOption.IGNORE_CASE)
                .findAll(text)
                .map { it.value.trimEnd('.', ',', ';', ')', ']') }
                .distinct()
                .take(3)
                .toList()
        }

        fun extractCleanSearchQuery(rawPrompt: String): String {
            var cleaned = rawPrompt.trim()
            for (prefix in SLASH_COMMAND_PREFIXES) {
                if (cleaned.startsWith(prefix, ignoreCase = true)) {
                    cleaned = cleaned.substring(prefix.length).trim()
                    break
                }
            }
            // Also strip attached file/vault headers if present
            val promptMarker = "Original Research Request:"
            if (cleaned.contains(promptMarker)) {
                cleaned = cleaned.substringAfter(promptMarker).substringBefore("\n\n").trim()
            }
            return cleaned
                .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("[\\r\\n]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(180)
        }

        fun hasIndiaOrRegionalCue(query: String): Boolean {
            val lower = query.lowercase()
            if (Regex("[\\u0900-\\u097F]").containsMatchIn(query)) return true
            return Regex(
                "\\b(india|indian|maharashtra|pune|mumbai|nagpur|nashik|delhi|bangalore|bengaluru|hyderabad|chennai|kolkata|inr|rupee|rupees|rs|nifty|sensex|bse|nse|ipl|bcci|isro|rbi|upi|aadhaar|aaj|aajcha|aajche|aajchi|kal|kalcha|bhav|kimmat|sadhya|taaja|kon|jinkla|havaman|bharat|pm modi|modi)\\b"
            ).containsMatchIn(lower)
        }

        fun decodeDuckDuckGoRedirectUrl(href: String): String {
            val trimmed = href.trim()
            if (trimmed.isBlank()) return ""
            val normalized = when {
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.startsWith("/") -> "https://duckduckgo.com$trimmed"
                else -> trimmed
            }
            val uddgMatch = Regex("[?&]uddg=([^&]+)").find(normalized)
            if (uddgMatch != null) {
                val decoded = runCatching {
                    URLDecoder.decode(uddgMatch.groupValues[1], StandardCharsets.UTF_8.name())
                }.getOrNull()
                if (!decoded.isNullOrBlank()) return decoded
            }
            return normalized
        }

        fun parseDuckDuckGoHtml(html: String, maxResults: Int = 6): List<WebSearchResult> {
            if (html.isBlank()) return emptyList()
            val results = mutableListOf<WebSearchResult>()
            val blocks = html.split(Regex("<div[^>]*class=\"[^\"]*\\bresults_links\\b[^\"]*\"[^>]*>", RegexOption.IGNORE_CASE))
                .drop(1)

            val linkRegex = Regex(
                "<a[^>]*class=\"[^\"]*\\bresult__a\\b[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
                RegexOption.IGNORE_CASE
            )
            val snippetRegex = Regex(
                "<(?:a|div|span)[^>]*class=\"[^\"]*\\bresult__snippet\\b[^\"]*\"[^>]*>([\\s\\S]*?)</(?:a|div|span)>",
                RegexOption.IGNORE_CASE
            )

            for (block in blocks) {
                if (results.size >= maxResults) break
                val linkMatch = linkRegex.find(block) ?: continue
                val rawHref = decodeHtmlEntities(linkMatch.groupValues[1])
                val url = decodeDuckDuckGoRedirectUrl(rawHref)
                if (!url.startsWith("http", ignoreCase = true) || url.contains("duckduckgo.com/y.js")) {
                    continue
                }
                val title = stripHtmlToText(linkMatch.groupValues[2])
                if (title.isBlank()) continue
                val snippet = snippetRegex.find(block)?.let { stripHtmlToText(it.groupValues[1]) }.orEmpty()
                results.add(
                    WebSearchResult(
                        title = title,
                        url = url,
                        snippet = snippet
                    )
                )
            }

            if (results.isNotEmpty()) return results

            // Secondary regex scan if container class structure differs slightly
            val allLinks = linkRegex.findAll(html).toList()
            val allSnippets = snippetRegex.findAll(html).toList()
            for (i in allLinks.indices) {
                if (results.size >= maxResults) break
                val linkMatch = allLinks[i]
                val url = decodeDuckDuckGoRedirectUrl(decodeHtmlEntities(linkMatch.groupValues[1]))
                if (!url.startsWith("http", ignoreCase = true) || url.contains("duckduckgo.com/y.js")) continue
                val title = stripHtmlToText(linkMatch.groupValues[2])
                if (title.isBlank()) continue
                val snippet = allSnippets.getOrNull(i)?.let { stripHtmlToText(it.groupValues[1]) }.orEmpty()
                results.add(WebSearchResult(title = title, url = url, snippet = snippet))
            }
            return results
        }

        fun parseDuckDuckGoLiteHtml(html: String, maxResults: Int = 6): List<WebSearchResult> {
            if (html.isBlank()) return emptyList()
            val results = mutableListOf<WebSearchResult>()
            val linkRegex = Regex(
                "<a[^>]*class=[\"'][^\"']*result-link[^\"']*[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>",
                RegexOption.IGNORE_CASE
            )
            val snippetRegex = Regex(
                "<td[^>]*class=[\"'][^\"']*result-snippet[^\"']*[\"'][^>]*>([\\s\\S]*?)</td>",
                RegexOption.IGNORE_CASE
            )
            val links = linkRegex.findAll(html).toList()
            val snippets = snippetRegex.findAll(html).toList()
            for (i in links.indices) {
                if (results.size >= maxResults) break
                val url = decodeDuckDuckGoRedirectUrl(decodeHtmlEntities(links[i].groupValues[1]))
                if (!url.startsWith("http", ignoreCase = true)) continue
                val title = stripHtmlToText(links[i].groupValues[2])
                if (title.isBlank()) continue
                val snippet = snippets.getOrNull(i)?.let { stripHtmlToText(it.groupValues[1]) }.orEmpty()
                results.add(WebSearchResult(title = title, url = url, snippet = snippet))
            }
            return results
        }

        fun parseNewsRssXml(xml: String, maxResults: Int = 5): List<WebSearchResult> {
            if (xml.isBlank()) return emptyList()
            val results = mutableListOf<WebSearchResult>()
            val itemRegex = Regex("<item>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE)
            val titleRegex = Regex("<title>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
            val linkRegex = Regex("<link>([\\s\\S]*?)</link>", RegexOption.IGNORE_CASE)
            val pubDateRegex = Regex("<pubDate>([\\s\\S]*?)</pubDate>", RegexOption.IGNORE_CASE)
            val descRegex = Regex("<description>([\\s\\S]*?)</description>", RegexOption.IGNORE_CASE)
            val sourceRegex = Regex("<source(?:\\s+url=\"([^\"]*)\")?[^>]*>([\\s\\S]*?)</source>", RegexOption.IGNORE_CASE)

            for (match in itemRegex.findAll(xml)) {
                if (results.size >= maxResults) break
                val block = match.groupValues[1]
                val rawTitle = stripHtmlToText(unwrapCdata(titleRegex.find(block)?.groupValues?.get(1).orEmpty()))
                val link = decodeHtmlEntities(unwrapCdata(linkRegex.find(block)?.groupValues?.get(1).orEmpty())).trim()
                if (rawTitle.isBlank() || !link.startsWith("http", ignoreCase = true)) continue

                val pubDate = stripHtmlToText(pubDateRegex.find(block)?.groupValues?.get(1).orEmpty())
                val desc = stripHtmlToText(unwrapCdata(descRegex.find(block)?.groupValues?.get(1).orEmpty()))
                val sourceMatch = sourceRegex.find(block)
                val sourceName = sourceMatch?.groupValues?.getOrNull(2)?.let { stripHtmlToText(it) }?.ifBlank { null }

                val cleanDesc = if (desc.equals(rawTitle, ignoreCase = true)) "" else desc
                results.add(
                    WebSearchResult(
                        title = rawTitle,
                        url = link,
                        snippet = cleanDesc.ifBlank { rawTitle },
                        publishedAt = pubDate.ifBlank { null },
                        sourceName = sourceName
                    )
                )
            }
            return results
        }

        fun parseWikipediaSearchJson(jsonString: String, maxResults: Int = 3): List<WebSearchResult> {
            if (jsonString.isBlank()) return emptyList()
            return runCatching {
                val root = JSONObject(jsonString)
                val items = root.optJSONObject("query")?.optJSONArray("search") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until minOf(items.length(), maxResults)) {
                        val obj = items.optJSONObject(i) ?: continue
                        val title = obj.optString("title").trim()
                        if (title.isBlank()) continue
                        val slug = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
                        val snippet = stripHtmlToText(obj.optString("snippet"))
                        val timestamp = obj.optString("timestamp").take(10).ifBlank { null }
                        add(
                            WebSearchResult(
                                title = "$title — Wikipedia",
                                url = "https://en.wikipedia.org/wiki/$slug",
                                snippet = snippet,
                                publishedAt = timestamp,
                                sourceName = "Wikipedia"
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

        fun extractReadableTextFromHtml(html: String, maxChars: Int = 1800): String {
            if (html.isBlank()) return ""
            val titleMatch = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)
            val pageTitle = titleMatch?.let { stripHtmlToText(it.groupValues[1]) }.orEmpty()

            val stripped = html
                .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<noscript[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<svg[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<!--([\\s\\S]*?)-->"), " ")
                .replace(Regex("</(p|div|li|tr|h1|h2|h3|h4|h5|h6|br)>", RegexOption.IGNORE_CASE), "\n")

            val bodyText = stripHtmlToText(stripped)
                .lines()
                .map { it.trim() }
                .filter { it.length >= 25 }
                .distinct()
                .joinToString("\n")
                .take(maxChars)

            return if (pageTitle.isNotBlank() && !bodyText.startsWith(pageTitle, ignoreCase = true)) {
                "$pageTitle\n$bodyText".take(maxChars)
            } else {
                bodyText
            }
        }

        fun stripHtmlToText(rawHtml: String): String {
            if (rawHtml.isBlank()) return ""
            val noTags = rawHtml.replace(Regex("<[^>]+>"), " ")
            return decodeHtmlEntities(noTags)
                .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }

        private fun unwrapCdata(raw: String): String {
            val trimmed = raw.trim()
            return if (trimmed.startsWith("<![CDATA[") && trimmed.endsWith("]]>")) {
                trimmed.removePrefix("<![CDATA[").removeSuffix("]]>")
            } else {
                trimmed
            }
        }

        fun decodeHtmlEntities(input: String): String {
            var out = input
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&ndash;", "–")
                .replace("&mdash;", "—")
                .replace("&rsquo;", "’")
                .replace("&lsquo;", "‘")
                .replace("&rdquo;", "”")
                .replace("&ldquo;", "“")
                .replace("&hellip;", "…")
                .replace("&#x27;", "'")
                .replace("&#x2F;", "/")

            out = Regex("&#(\\d+);").replace(out) { m ->
                val code = m.groupValues[1].toIntOrNull()
                if (code != null && code in 32..0x10FFFF) code.toChar().toString() else ""
            }
            out = Regex("&#x([0-9a-fA-F]+);").replace(out) { m ->
                val code = m.groupValues[1].toIntOrNull(16)
                if (code != null && code in 32..0x10FFFF) code.toChar().toString() else ""
            }
            return out
        }

        private fun normalizeUrlKey(url: String): String {
            return url.trim()
                .lowercase()
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
                .trimEnd('/')
        }
    }
}
