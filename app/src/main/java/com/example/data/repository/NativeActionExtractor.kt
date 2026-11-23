package com.example.data.repository

sealed interface NativeActionChip {
    val label: String

    data class DraftEmail(
        override val label: String,
        val toAddress: String?,
        val subject: String,
        val body: String
    ) : NativeActionChip

    data class AddCalendarEvent(
        override val label: String,
        val title: String,
        val description: String
    ) : NativeActionChip

    data class OpenLink(
        override val label: String,
        val url: String
    ) : NativeActionChip
}

object NativeActionExtractor {

    private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val SUBJECT_REGEX = Regex("(?i)^\\s*Subject:\\s*(.+)$", RegexOption.MULTILINE)
    private val CALENDAR_CUE_REGEX = Regex(
        "\\b(schedule|meeting|calendar|appointment|reminder|deadline|agenda|event|tomorrow at|next monday|am|pm)\\b",
        RegexOption.IGNORE_CASE
    )
    private val URL_REGEX = Regex("https?://[^\\s)\"`<>]+", RegexOption.IGNORE_CASE)

    fun extractActions(content: String): List<NativeActionChip> {
        if (content.isBlank()) return emptyList()
        val chips = mutableListOf<NativeActionChip>()

        // 1. Draft Email detection (Subject line OR Dear/Hi + Regards OR explicit email address)
        val subjectMatch = SUBJECT_REGEX.find(content)
        val emailAddress = EMAIL_REGEX.find(content)?.value
        val looksLikeEmail = subjectMatch != null ||
            (content.contains("Dear ", ignoreCase = false) &&
                (content.contains("Regards", ignoreCase = true) || content.contains("Sincerely", ignoreCase = true)))
        if (looksLikeEmail) {
            val subject = subjectMatch?.groupValues?.getOrNull(1)?.trim()?.take(120)
                ?: "Follow-up from Aura AI"
            val cleanBody = content
                .replace(Regex("```[\\s\\S]*?```"), "")
                .trim()
                .take(1800)
            chips += NativeActionChip.DraftEmail(
                label = if (emailAddress != null) "Draft Email ($emailAddress)" else "Draft in Email App",
                toAddress = emailAddress,
                subject = subject,
                body = cleanBody
            )
        }

        // 2. Calendar Event detection
        if (CALENDAR_CUE_REGEX.containsMatchIn(content) &&
            (content.contains("meeting", ignoreCase = true) ||
                content.contains("schedule", ignoreCase = true) ||
                content.contains("appointment", ignoreCase = true) ||
                content.contains("deadline", ignoreCase = true) ||
                content.contains("calendar", ignoreCase = true))
        ) {
            val firstHeading = content.lines()
                .map { it.trim().removePrefix("#").removePrefix("##").removePrefix("###").trim() }
                .firstOrNull { it.length in 5..80 && !it.startsWith("```") }
                ?: "Aura AI Scheduled Event"
            chips += NativeActionChip.AddCalendarEvent(
                label = "Add to Calendar",
                title = firstHeading.take(70),
                description = content.take(600)
            )
        }

        // 3. First external link shortcut (when not overly noisy)
        val url = URL_REGEX.find(content)?.value?.trimEnd('.', ',', ';')
        if (url != null && url.length < 180) {
            val host = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: "Link"
            chips += NativeActionChip.OpenLink(
                label = "Open $host",
                url = url
            )
        }

        return chips.take(3)
    }
}
