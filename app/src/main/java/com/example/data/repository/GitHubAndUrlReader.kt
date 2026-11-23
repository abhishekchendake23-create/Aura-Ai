package com.example.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI

data class GitHubRepoSpec(
    val owner: String,
    val repo: String,
    val branch: String? = null,
    val path: String? = null,
    val isBlob: Boolean = false
)

data class WebLinkContext(
    val formattedPromptBlock: String,
    val sourceCitations: Map<String, SourceCitation>,
    val summaryLabel: String
)

class GitHubAndUrlReader(private val httpClient: OkHttpClient) {

    suspend fun gatherContextForUrls(urls: List<String>): WebLinkContext? = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext null
        val uniqueUrls = urls.distinct().take(3)
        val sections = mutableListOf<String>()
        val citations = linkedMapOf<String, SourceCitation>()

        for (url in uniqueUrls) {
            val ghSpec = parseGitHubRepoUrl(url)
            if (ghSpec != null) {
                val repoContext = fetchGitHubContext(url, ghSpec)
                if (repoContext != null) {
                    sections.add(repoContext.first)
                    citations[url] = SourceCitation(
                        title = repoContext.second,
                        uri = url
                    )
                    continue
                }
            }

            val pageText = fetchCleanWebPageText(url)
            if (pageText != null) {
                val (title, body) = pageText
                sections.add(
                    "### Linked Webpage: $title\nURL: $url\nContent:\n$body"
                )
                citations[url] = SourceCitation(title = title, uri = url)
            }
        }

        if (sections.isEmpty()) return@withContext null
        val label = if (citations.size == 1) {
            citations.values.first().title
        } else {
            "${citations.size} linked web/repository sources"
        }
        WebLinkContext(
            formattedPromptBlock = "### AUTOMATIC LINK & REPOSITORY CONTEXT\n" +
                sections.joinToString("\n\n"),
            sourceCitations = citations,
            summaryLabel = label
        )
    }

    private fun fetchGitHubContext(originalUrl: String, spec: GitHubRepoSpec): Pair<String, String>? {
        val repoDisplay = "${spec.owner}/${spec.repo}"
        if (spec.isBlob && !spec.path.isNullOrBlank() && !spec.branch.isNullOrBlank()) {
            val rawUrl = "https://raw.githubusercontent.com/${spec.owner}/${spec.repo}/${spec.branch}/${spec.path}"
            val fileBody = httpGetText(rawUrl, maxChars = 14_000)
            if (!fileBody.isNullOrBlank()) {
                val block = buildString {
                    append("### GitHub File: $repoDisplay/${spec.path} (branch: ${spec.branch})\n")
                    append("URL: $originalUrl\n")
                    append("```\n$fileBody\n```")
                }
                return block to "GitHub · $repoDisplay/${spec.path.substringAfterLast('/')}"
            }
        }

        val repoMetaJson = httpGetText(
            "https://api.github.com/repos/${spec.owner}/${spec.repo}",
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to "AuraAI-Android"
            ),
            maxChars = 16_000
        ) ?: return null

        val metaObj = runCatching { JSONObject(repoMetaJson) }.getOrNull() ?: return null
        val defaultBranch = spec.branch ?: metaObj.optString("default_branch").ifBlank { "main" }
        val description = metaObj.optString("description").trim()
        val language = metaObj.optString("language").trim()
        val stars = metaObj.optInt("stargazers_count", 0)

        val treeJson = httpGetText(
            "https://api.github.com/repos/${spec.owner}/${spec.repo}/git/trees/$defaultBranch?recursive=1",
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to "AuraAI-Android"
            ),
            maxChars = 180_000
        )

        val treePaths = mutableListOf<String>()
        if (!treeJson.isNullOrBlank()) {
            runCatching {
                val arr = JSONObject(treeJson).optJSONArray("tree")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        if (item.optString("type") != "blob") continue
                        val path = item.optString("path")
                        val size = item.optInt("size", 0)
                        if (path.isNotBlank() && size in 1..180_000) {
                            if (!spec.path.isNullOrBlank() && !path.startsWith(spec.path.trimEnd('/'))) continue
                            treePaths.add(path)
                        }
                    }
                }
            }
        }

        val prioritized = prioritizeRepoFiles(treePaths)
        val fetchedFiles = StringBuilder()
        var totalBudget = 14_500
        var includedCount = 0

        for (filePath in prioritized) {
            if (totalBudget <= 400 || includedCount >= 12) break
            val rawUrl = "https://raw.githubusercontent.com/${spec.owner}/${spec.repo}/$defaultBranch/$filePath"
            val content = httpGetText(rawUrl, maxChars = minOf(3500, totalBudget))
            if (!content.isNullOrBlank()) {
                fetchedFiles.append("// File: $filePath\n```\n")
                fetchedFiles.append(content.trim())
                fetchedFiles.append("\n```\n\n")
                totalBudget -= content.length
                includedCount++
            }
        }

        val block = buildString {
            append("### GitHub Repository: $repoDisplay (branch: $defaultBranch)\n")
            if (description.isNotBlank()) append("Description: $description\n")
            if (language.isNotBlank()) append("Primary Language: $language · Stars: $stars\n")
            if (treePaths.isNotEmpty()) {
                append("Key Tree Files (${treePaths.size} total):\n")
                append(treePaths.take(65).joinToString("\n") { "- $it" })
                append("\n\n")
            }
            if (fetchedFiles.isNotEmpty()) {
                append("Extracted Key Files ($includedCount):\n")
                append(fetchedFiles)
            }
        }.trim()

        return block to "GitHub · $repoDisplay"
    }

    private fun fetchCleanWebPageText(url: String): Pair<String, String>? {
        val html = httpGetText(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AuraAI/1.0"),
            maxChars = 160_000
        ) ?: return null
        val (title, cleanText) = htmlToCleanText(html, url)
        if (cleanText.isBlank()) return null
        return title to cleanText.take(6500)
    }

    private fun httpGetText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxChars: Int = 64_000
    ): String? {
        return runCatching {
            val builder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            httpClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                if (body.length > maxChars) body.take(maxChars) else body
            }
        }.getOrNull()
    }

    companion object {
        private val URL_REGEX = Regex("https?://[^\\s<>\"'\\)\\]]+", RegexOption.IGNORE_CASE)

        private val PRIORITY_FILE_REGEX = Regex(
            "(?i)(^|/)(readme(\\.md|\\.txt)?|build\\.gradle(\\.kts)?|settings\\.gradle(\\.kts)?|package\\.json|pyproject\\.toml|cargo\\.toml|pom\\.xml|androidmanifest\\.xml|dockerfile|main\\.(kt|java|py|ts|tsx|js|go|rs)|app\\.(kt|tsx|jsx|py)|index\\.(ts|tsx|js|html))$"
        )

        private val SOURCE_EXT_REGEX = Regex("(?i)\\.(kt|java|py|ts|tsx|js|jsx|go|rs|cpp|c|h|cs|swift|md|json|yaml|yml|toml|sql)$")
        private val IGNORED_PATH_REGEX = Regex("(?i)(^|/)(node_modules|build|dist|\\.git|\\.gradle|target|__pycache__|vendor|coverage|\\.idea|assets/fonts)/|\\.(png|jpg|jpeg|gif|webp|ico|pdf|zip|jar|apk|ttf|woff2?)$")

        fun extractUrls(text: String): List<String> {
            return URL_REGEX.findAll(text)
                .map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }
                .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
                .distinct()
                .toList()
        }

        fun parseGitHubRepoUrl(rawUrl: String): GitHubRepoSpec? {
            val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
            if (host != "github.com") return null
            val segments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
            if (segments.size < 2) return null
            val owner = segments[0]
            val repo = segments[1].removeSuffix(".git")
            if (owner.isBlank() || repo.isBlank()) return null

            return when {
                segments.size >= 4 && segments[2] == "tree" -> GitHubRepoSpec(
                    owner = owner,
                    repo = repo,
                    branch = segments[3],
                    path = segments.drop(4).joinToString("/").ifBlank { null },
                    isBlob = false
                )
                segments.size >= 5 && segments[2] == "blob" -> GitHubRepoSpec(
                    owner = owner,
                    repo = repo,
                    branch = segments[3],
                    path = segments.drop(4).joinToString("/"),
                    isBlob = true
                )
                else -> GitHubRepoSpec(owner = owner, repo = repo)
            }
        }

        fun prioritizeRepoFiles(paths: List<String>): List<String> {
            val filtered = paths.filter { !IGNORED_PATH_REGEX.containsMatchIn(it) }
            val highPriority = filtered.filter { PRIORITY_FILE_REGEX.containsMatchIn(it) }
            val sourceFiles = filtered.filter { it !in highPriority && SOURCE_EXT_REGEX.containsMatchIn(it) }
            val remaining = filtered.filter { it !in highPriority && it !in sourceFiles }
            return (highPriority + sourceFiles + remaining).distinct()
        }

        fun htmlToCleanText(html: String, fallbackUrl: String): Pair<String, String> {
            val titleMatch = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
            val title = decodeBasicHtmlEntities(
                titleMatch?.groupValues?.getOrNull(1)?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty()
            ).ifBlank {
                runCatching { URI(fallbackUrl).host?.removePrefix("www.") }.getOrNull() ?: "Web Page"
            }

            val stripped = html
                .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
                .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
                .replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
                .replace(Regex("(?is)<svg[^>]*>.*?</svg>"), " ")
                .replace(Regex("(?is)<nav[^>]*>.*?</nav>"), " ")
                .replace(Regex("(?is)<footer[^>]*>.*?</footer>"), " ")
                .replace(Regex("(?i)<(br|p|div|li|tr|h[1-6]|section|article)[^>]*>"), "\n")
                .replace(Regex("<[^>]+>"), " ")

            val decoded = decodeBasicHtmlEntities(stripped)
            val normalizedLines = decoded.lines()
                .map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
                .filter { it.isNotBlank() }
                .distinct()
            return title to normalizedLines.joinToString("\n")
        }

        private fun decodeBasicHtmlEntities(input: String): String {
            return input
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
        }
    }
}
