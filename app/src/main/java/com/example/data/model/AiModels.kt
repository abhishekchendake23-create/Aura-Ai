package com.example.data.model

enum class ThemeMode(val label: String) {
    DARK("AMOLED Black"),
    LIGHT("Clean Light"),
    SYSTEM("Match System")
}

enum class AppLanguage(
    val id: String,
    val label: String,
    val nativeLabel: String,
    val speechLocaleTag: String,
    val promptInstruction: String
) {
    AUTO(
        id = "auto",
        label = "Auto",
        nativeLabel = "Auto",
        speechLocaleTag = "",
        promptInstruction = "Automatically match the user's language and script naturally (English, Devanagari Marathi मराठी, Romanized Marathi e.g. 'kasa ahes', Devanagari Hindi हिन्दी, or Hinglish)."
    ),
    MARATHI(
        id = "mr",
        label = "Marathi",
        nativeLabel = "मराठी",
        speechLocaleTag = "mr-IN",
        promptInstruction = "Respond primarily in natural, fluent Marathi (मराठी) using clear Devanagari script (or match Romanized Marathi if the user prefers), while keeping code blocks, technical symbols, and KaTeX formulas crisp and accurate."
    ),
    HINDI(
        id = "hi",
        label = "Hindi",
        nativeLabel = "हिन्दी",
        speechLocaleTag = "hi-IN",
        promptInstruction = "Respond primarily in natural, fluent Hindi (हिन्दी) using clear Devanagari script (or match Hinglish if preferred), while keeping code blocks, technical symbols, and KaTeX formulas crisp and accurate."
    ),
    ENGLISH(
        id = "en",
        label = "English",
        nativeLabel = "English",
        speechLocaleTag = "en-US",
        promptInstruction = "Respond in crisp, clear, authoritative English."
    );

    companion object {
        fun fromId(id: String): AppLanguage =
            entries.find { it.id == id } ?: AUTO
    }
}

enum class ModelCategory(val title: String) {
    SMART_AUTO("Smart Auto · Auto-Failover"),
    GEMINI_FREE("Gemini Flash Series (3.8–2.0) & Free Models"),
    DEEPSEEK_FREE("Latest Free DeepSeek Models"),
    IMAGE_GENERATION("Aura Imagine · Visual Creation")
}

enum class AiModelTier(
    val id: String,
    val displayName: String,
    val shortName: String,
    val subtitle: String,
    val badge: String,
    val contextLabel: String,
    val category: ModelCategory,
    val isImageModel: Boolean = false
) {
    AUTO_ROUTER(
        id = "aura-auto-router",
        displayName = "Aura Auto Router",
        shortName = "Auto Smart",
        subtitle = "Smart-routes every prompt and automatically fails over across all Gemini Flash, DeepSeek & Groq models",
        badge = "AUTO",
        contextLabel = "FAILOVER",
        category = ModelCategory.SMART_AUTO
    ),
    GEMINI_3_8_FLASH(
        id = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash",
        shortName = "3.8 Flash",
        subtitle = "Flagship 3.8 Flash — elite Claude-tier software engineering, deep thinking & vision",
        badge = "3.8 FLASH",
        contextLabel = "1M CTX",
        category = ModelCategory.GEMINI_FREE
    ),
    GEMINI_3_7_FLASH(
        id = "gemini-3.7-flash",
        displayName = "Gemini 3.7 Flash",
        shortName = "3.7 Flash",
        subtitle = "High-intelligence 3.7 Flash for complex coding, reasoning & multimodal analysis",
        badge = "3.7 FLASH",
        contextLabel = "1M CTX",
        category = ModelCategory.GEMINI_FREE
    ),
    GEMINI_3_6_FLASH(
        id = "gemini-3.6-flash",
        displayName = "Gemini 3.6 Flash",
        shortName = "3.6 Flash",
        subtitle = "Balanced 3.6 Flash built for crisp architecture, debugging & fast chat",
        badge = "3.6 FLASH",
        contextLabel = "1M CTX",
        category = ModelCategory.GEMINI_FREE
    ),
    GEMINI_3_5_FLASH(
        id = "gemini-3.5-flash",
        displayName = "Gemini 3.5 Flash",
        shortName = "3.5 Flash",
        subtitle = "Versatile 3.5 Flash workhorse for code generation, math & web research",
        badge = "3.5 FLASH",
        contextLabel = "1M CTX",
        category = ModelCategory.GEMINI_FREE
    ),
    GEMINI_3_5_FLASH_LITE(
        id = "gemini-3.5-flash-lite",
        displayName = "Gemini 3.5 Flash-Lite",
        shortName = "3.5 Lite",
        subtitle = "Ultra-fast, low-latency 3.5 Flash-Lite for instant coding & summaries",
        badge = "3.5 LITE",
        contextLabel = "1M CTX",
        category = ModelCategory.GEMINI_FREE
    ),
    GEMINI_2_5_FLASH(
        id = "gemini-2.5-flash",
        displayName = "Gemini 2.5 Flash",
        shortName = "2.5 Flash",
        subtitle = "Free Gemini 2.5 Flash with hybrid thinking, code execution & Google Search",
        badge = "FREE 2.5",
        contextLabel = "1M CTX",
        category = ModelCategory.GEMINI_FREE
    ),
    GEMINI_4_ARGON(
        id = "gemini-4-argon",
        displayName = "Gemini 2.5 Pro",
        shortName = "2.5 Pro",
        subtitle = "Free top-tier deep reasoning, Olympiad math, code architecture & analysis",
        badge = "FREE PRO",
        contextLabel = "1M CTX",
        category = ModelCategory.GEMINI_FREE
    ),
    GEMINI_2_5_FLASH_LITE(
        id = "gemini-2.5-flash-lite",
        displayName = "Gemini 2.5 Flash-Lite",
        shortName = "2.5 Lite",
        subtitle = "Free high-RPM, low-latency 2.5 Flash-Lite for rapid replies & summaries",
        badge = "FREE LITE",
        contextLabel = "1M CTX",
        category = ModelCategory.GEMINI_FREE
    ),
    GEMINI_2_0_FLASH(
        id = "gemini-2.0-flash",
        displayName = "Gemini 2.0 Flash",
        shortName = "2.0 Flash",
        subtitle = "Free high-speed multimodal 2.0 Flash with Google Search grounding",
        badge = "FREE 2.0",
        contextLabel = "1M CTX",
        category = ModelCategory.GEMINI_FREE
    ),
    GEMMA_3_27B(
        id = "gemma-3-27b-it",
        displayName = "Gemma 3 27B",
        shortName = "Gemma 3 27B",
        subtitle = "Free Google open-weights 27B multimodal model on Gemini API",
        badge = "FREE OPEN",
        contextLabel = "128K",
        category = ModelCategory.GEMINI_FREE
    ),
    GEMMA_3_12B(
        id = "gemma-3-12b-it",
        displayName = "Gemma 3 12B",
        shortName = "Gemma 3 12B",
        subtitle = "Free lightweight open-weights model for fast chat & reasoning",
        badge = "FREE OPEN",
        contextLabel = "128K",
        category = ModelCategory.GEMINI_FREE
    ),
    DEEPSEEK_R1_FREE(
        id = "deepseek-r1-free",
        displayName = "DeepSeek R1 (0528)",
        shortName = "DeepSeek R1",
        subtitle = "Latest free DeepSeek R1 reasoning model with live <think> streaming",
        badge = "FREE R1",
        contextLabel = "164K",
        category = ModelCategory.DEEPSEEK_FREE
    ),
    DEEPSEEK_V3_FREE(
        id = "deepseek-v3-free",
        displayName = "DeepSeek V3 (0324)",
        shortName = "DeepSeek V3",
        subtitle = "Latest free DeepSeek V3 flagship chat, coding & multilingual MoE",
        badge = "FREE V3",
        contextLabel = "164K",
        category = ModelCategory.DEEPSEEK_FREE
    ),
    DEEPSEEK_CHIMERA_FREE(
        id = "deepseek-chimera-free",
        displayName = "DeepSeek R1T2 Chimera",
        shortName = "R1T2 Chimera",
        subtitle = "Free hybrid R1/V3 reasoning & high-speed coding model",
        badge = "FREE",
        contextLabel = "164K",
        category = ModelCategory.DEEPSEEK_FREE
    ),
    NANO_BANANA_2_1(
        id = "gemini-nano-banana-2.1",
        displayName = "Gemini Flash Image",
        shortName = "Flash Image",
        subtitle = "Create and transform high-quality images from text or photos",
        badge = "ART",
        contextLabel = "GEMINI",
        category = ModelCategory.IMAGE_GENERATION,
        isImageModel = true
    ),
    GEMINI_3_PRO_IMAGE(
        id = "gemini-3-pro-image",
        displayName = "Aura Studio Art",
        shortName = "Studio Art",
        subtitle = "Studio-grade visual synthesis & sharp poster typography",
        badge = "PRO ART",
        contextLabel = "GEMINI",
        category = ModelCategory.IMAGE_GENERATION,
        isImageModel = true
    ),
    GEMINI_3_1_FLASH_LITE_IMAGE(
        id = "gemini-3.1-flash-lite-image",
        displayName = "Aura Rapid Art",
        shortName = "Rapid Art",
        subtitle = "Instant visual drafts, concept sketches & photo remixing",
        badge = "RAPID",
        contextLabel = "GEMINI",
        category = ModelCategory.IMAGE_GENERATION,
        isImageModel = true
    );

    val isDeepSeekTier: Boolean
        get() = category == ModelCategory.DEEPSEEK_FREE

    val isGeminiTier: Boolean
        get() = category == ModelCategory.GEMINI_FREE

    companion object {
        fun fromId(id: String): AiModelTier =
            entries.find { it.id == id } ?: when (id) {
                "gemini-2.5-flash" -> GEMINI_2_5_FLASH
                "gemini-2.5-pro" -> GEMINI_4_ARGON
                "gemini-2.5-flash-lite" -> GEMINI_2_5_FLASH_LITE
                "deepseek/deepseek-r1-0528:free", "deepseek/deepseek-r1:free", "deepseek-reasoner" -> DEEPSEEK_R1_FREE
                "deepseek/deepseek-chat-v3-0324:free", "deepseek-chat" -> DEEPSEEK_V3_FREE
                "tngtech/deepseek-r1t2-chimera:free" -> DEEPSEEK_CHIMERA_FREE
                else -> GEMINI_3_8_FLASH
            }
    }
}

enum class CapabilityMode(
    val id: String,
    val label: String,
    val description: String
) {
    STANDARD("standard", "Balanced", "Clear, natural Aura AI responses"),
    DEEP_THINK("deep_think", "Deep Think", "Step-by-step visible reasoning before final answer"),
    CODE_ARCHITECT("code", "Code Studio", "Production code, architecture & edge cases"),
    DEEP_RESEARCH("deep_research", "Research", "Multi-step agentic web search & cited executive report"),
    CONCISE("concise", "Concise", "Crisp executive summary & bullet points");

    companion object {
        fun fromId(id: String): CapabilityMode =
            entries.find { it.id == id } ?: STANDARD
    }
}

enum class ThinkingBudgetMode(val id: String, val label: String, val tokenBudget: Int?) {
    AUTO("AUTO", "Auto", null),
    OFF("OFF", "Off", 0),
    LIGHT("LIGHT", "Light", 2048),
    DEEP("DEEP", "Deep", 12288);

    companion object {
        fun fromId(id: String): ThinkingBudgetMode = entries.find { it.id == id } ?: AUTO
    }
}

data class WorkbenchConfig(
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val maxOutputTokens: Int = 8192,
    val thinkingBudgetMode: ThinkingBudgetMode = ThinkingBudgetMode.AUTO,
    val jsonSchemaMode: Boolean = false,
    val jsonSchemaText: String = "",
    val systemInstructionOverride: String = ""
)

val AURA_JSON_SCHEMA_PRESETS: List<Pair<String, String>> = listOf(
    "API Spec" to """{"endpoint": "string", "method": "GET|POST", "auth": "string", "requestSchema": {}, "responseSchema": {}, "errorCodes": []}""",
    "Decision Matrix" to """{"topic": "string", "winner": "string", "criteria": [], "options": [{"name": "string", "score": 0, "pros": [], "cons": []}]}""",
    "Study Flashcards" to """{"topic": "string", "cards": [{"question": "string", "answer": "string", "difficulty": "easy|medium|hard"}]}""",
    "Action Plan" to """{"goal": "string", "milestones": [{"step": 1, "title": "string", "owner": "string", "deliverable": "string", "risk": "string"}]}"""
)

enum class VoiceTone(val label: String) {
    ALL("All (30)"),
    WARM("Warm"),
    ENERGETIC("Upbeat"),
    CALM("Calm"),
    AUTHORITATIVE("Bold"),
    CRISP("Crisp")
}

data class AuraVoiceCharacter(
    val apiName: String,
    val displayName: String,
    val trait: String,
    val description: String,
    val tone: VoiceTone
)

val ALL_30_VOICE_CHARACTERS: List<AuraVoiceCharacter> = listOf(
    AuraVoiceCharacter("Zephyr", "Zephyr", "Bright", "Crisp, uplifting & effortless clarity", VoiceTone.ENERGETIC),
    AuraVoiceCharacter("Puck", "Puck", "Upbeat", "Lively, playful & quick-witted energy", VoiceTone.ENERGETIC),
    AuraVoiceCharacter("Charon", "Charon", "Informative", "Calm, steady & deeply reassuring", VoiceTone.CALM),
    AuraVoiceCharacter("Kore", "Kore", "Firm", "Focused, confident & executive poise", VoiceTone.AUTHORITATIVE),
    AuraVoiceCharacter("Fenrir", "Fenrir", "Excitable", "Passionate, dynamic & expressive", VoiceTone.ENERGETIC),
    AuraVoiceCharacter("Leda", "Leda", "Youthful", "Fresh, bright & approachable warmth", VoiceTone.WARM),
    AuraVoiceCharacter("Orus", "Orus", "Grounded", "Steady, resolute & authoritative", VoiceTone.AUTHORITATIVE),
    AuraVoiceCharacter("Aoede", "Aoede", "Breezy", "Relaxed, natural storytelling flow", VoiceTone.CALM),
    AuraVoiceCharacter("Callirrhoe", "Callirrhoe", "Easy-going", "Friendly, fluid & poised cadence", VoiceTone.WARM),
    AuraVoiceCharacter("Autonoe", "Autonoe", "Radiant", "Bright, optimistic & clear tone", VoiceTone.ENERGETIC),
    AuraVoiceCharacter("Enceladus", "Enceladus", "Breathy", "Soft-spoken, intimate & soothing", VoiceTone.CALM),
    AuraVoiceCharacter("Iapetus", "Iapetus", "Clear", "Articulate, clean & precise diction", VoiceTone.CRISP),
    AuraVoiceCharacter("Umbriel", "Umbriel", "Easy-going", "Mellow, casual & natural warmth", VoiceTone.WARM),
    AuraVoiceCharacter("Algieba", "Algieba", "Smooth", "Balanced, velvety & composed", VoiceTone.CALM),
    AuraVoiceCharacter("Despina", "Despina", "Smooth", "Silky, gentle & effortless delivery", VoiceTone.WARM),
    AuraVoiceCharacter("Erinome", "Erinome", "Lucid", "Pure, transparent & crisp articulation", VoiceTone.CRISP),
    AuraVoiceCharacter("Algenib", "Algenib", "Gravelly", "Deep, textured & commanding timbre", VoiceTone.AUTHORITATIVE),
    AuraVoiceCharacter("Rasalgethi", "Rasalgethi", "Thoughtful", "Measured, analytical & clear", VoiceTone.CRISP),
    AuraVoiceCharacter("Laomedeia", "Laomedeia", "Lively", "Sparkling, upbeat & enthusiastic", VoiceTone.ENERGETIC),
    AuraVoiceCharacter("Achernar", "Achernar", "Soft", "Gentle, comforting & serene", VoiceTone.CALM),
    AuraVoiceCharacter("Alnilam", "Alnilam", "Bold", "Strong, decisive & direct", VoiceTone.AUTHORITATIVE),
    AuraVoiceCharacter("Schedar", "Schedar", "Even", "Balanced, poised & steady pace", VoiceTone.CRISP),
    AuraVoiceCharacter("Gacrux", "Gacrux", "Mature", "Rich, seasoned & trustworthy", VoiceTone.AUTHORITATIVE),
    AuraVoiceCharacter("Pulcherrima", "Pulcherrima", "Forward", "Expressive, vivid & engaging", VoiceTone.ENERGETIC),
    AuraVoiceCharacter("Achird", "Achird", "Friendly", "Welcoming, warm & conversational", VoiceTone.WARM),
    AuraVoiceCharacter("Zubenelgenubi", "Zubenelgenubi", "Casual", "Laid-back, natural & relaxed", VoiceTone.CALM),
    AuraVoiceCharacter("Vindemiatrix", "Vindemiatrix", "Gentle", "Kind, empathetic & calm", VoiceTone.WARM),
    AuraVoiceCharacter("Sadachbia", "Sadachbia", "Curious", "Vibrant, animated & alert", VoiceTone.ENERGETIC),
    AuraVoiceCharacter("Sadaltager", "Sadaltager", "Wise", "Knowledgeable, calm & insightful", VoiceTone.CRISP),
    AuraVoiceCharacter("Sulafat", "Sulafat", "Warm", "Heartfelt, smooth & conversational", VoiceTone.WARM)
)

data class AttachmentDraft(
    val uri: String,
    val displayName: String,
    val isImage: Boolean,
    val textSnippet: String? = null,
    val extraImageUris: List<String> = emptyList()
)

data class PromptStarter(
    val id: String,
    val title: String,
    val subtitle: String,
    val prompt: String,
    val category: String,
    val suggestedModel: AiModelTier? = null,
    val suggestedMode: CapabilityMode = CapabilityMode.STANDARD
)

enum class SlashCommandSpecialAction {
    NONE,
    OPEN_SCANNER,
    OPEN_VAULT,
    OPEN_MEMORY
}

data class SlashCommand(
    val trigger: String,
    val title: String,
    val subtitle: String,
    val promptPrefix: String,
    val suggestedMode: CapabilityMode,
    val requiresWeb: Boolean = false,
    val requiresSandbox: Boolean = false,
    val specialAction: SlashCommandSpecialAction = SlashCommandSpecialAction.NONE
)

val AURA_SLASH_COMMANDS = listOf(
    SlashCommand(
        trigger = "/research",
        title = "Deep Research Agent",
        subtitle = "Multi-step web investigation + cited executive report",
        promptPrefix = "Conduct deep multi-angle research, cross-verify facts, compare trade-offs in Markdown tables, and produce an authoritative cited report on:\n",
        suggestedMode = CapabilityMode.DEEP_RESEARCH,
        requiresWeb = true
    ),
    SlashCommand(
        trigger = "/arch",
        title = "System & Code Architect",
        subtitle = "Full multi-file architecture + zero-error code",
        promptPrefix = "Architect a complete, production-ready solution with clean file separation, defensive error handling, concurrency safety, and verification tests for:\n",
        suggestedMode = CapabilityMode.CODE_ARCHITECT
    ),
    SlashCommand(
        trigger = "/debug",
        title = "Principal Bug & Security Audit",
        subtitle = "Find root cause, race conditions & output ```diff fix",
        promptPrefix = "Perform a ruthless Principal Engineer audit on this issue/code. Identify root causes, edge cases, crashes, memory leaks, or security flaws, then provide a clean unified diff and drop-in fixed code:\n",
        suggestedMode = CapabilityMode.CODE_ARCHITECT
    ),
    SlashCommand(
        trigger = "/sandbox",
        title = "Python Verified Sandbox",
        subtitle = "Execute live Python to verify math, algorithms & data",
        promptPrefix = "Write and execute Python in the live sandbox to compute and verify the exact answer step-by-step for:\n",
        suggestedMode = CapabilityMode.DEEP_THINK,
        requiresSandbox = true
    ),
    SlashCommand(
        trigger = "/math",
        title = "Olympiad KaTeX Proof",
        subtitle = "Step-by-step KaTeX derivation + boxed check",
        promptPrefix = "Solve rigorously step-by-step using KaTeX display blocks ($$...$$), verify with an independent sanity check, and box the final result:\n",
        suggestedMode = CapabilityMode.DEEP_THINK
    ),
    SlashCommand(
        trigger = "/tests",
        title = "Comprehensive Unit Tests",
        subtitle = "Happy path, edge cases & failure-path coverage",
        promptPrefix = "Write a complete, deterministic unit test suite covering normal execution, boundary edge cases, concurrency, and failure paths for:\n",
        suggestedMode = CapabilityMode.CODE_ARCHITECT
    ),
    SlashCommand(
        trigger = "/web",
        title = "Live Web Deep Check",
        subtitle = "Verify latest docs, releases & facts with citations",
        promptPrefix = "Search live web sources and verify the latest authoritative details with a structured breakdown for:\n",
        suggestedMode = CapabilityMode.DEEP_THINK,
        requiresWeb = true
    ),
    SlashCommand(
        trigger = "/scan",
        title = "PDF & Paper Scanner",
        subtitle = "Scan multi-page PDFs or exam papers (Q1..QN)",
        promptPrefix = "",
        suggestedMode = CapabilityMode.DEEP_THINK,
        specialAction = SlashCommandSpecialAction.OPEN_SCANNER
    ),
    SlashCommand(
        trigger = "/vault",
        title = "Private Document Vault",
        subtitle = "Query your offline indexed PDFs & notes (RAG)",
        promptPrefix = "",
        suggestedMode = CapabilityMode.STANDARD,
        specialAction = SlashCommandSpecialAction.OPEN_VAULT
    ),
    SlashCommand(
        trigger = "/remember",
        title = "Save to Aura Memory",
        subtitle = "Remember a preference, goal or fact across chats",
        promptPrefix = "Remember this about me: ",
        suggestedMode = CapabilityMode.STANDARD,
        specialAction = SlashCommandSpecialAction.OPEN_MEMORY
    ),
    SlashCommand(
        trigger = "/ui",
        title = "Jetpack Compose AMOLED UI",
        subtitle = "Material 3 Compose screen + ViewModel StateFlow",
        promptPrefix = "Build a complete Jetpack Compose Material 3 AMOLED UI screen with StateFlow ViewModel, accessibility, and responsive layout for:\n",
        suggestedMode = CapabilityMode.CODE_ARCHITECT
    ),
    SlashCommand(
        trigger = "/marathi",
        title = "Marathi + Technical Clarity",
        subtitle = "Crisp natural Marathi explanation with examples",
        promptPrefix = "Explain clearly in crisp, natural Marathi (or Romanized Marathi matching my style) with practical examples:\n",
        suggestedMode = CapabilityMode.STANDARD
    ),
    SlashCommand(
        trigger = "/agent",
        title = "Manus Autonomous Agent",
        subtitle = "Multi-step plan -> Web/URL -> Quantitative -> Deliverable",
        promptPrefix = "Run an autonomous multi-step agent workflow (plan steps, verify live facts & calculations, and deliver the final complete solution) for:\n",
        suggestedMode = CapabilityMode.DEEP_THINK,
        requiresWeb = true
    ),
    SlashCommand(
        trigger = "/slides",
        title = "Interactive Slide Deck",
        subtitle = "AMOLED slide deck with Next/Prev navigation in Sandbox",
        promptPrefix = "Create an executive presentation formatted inside a ```slides code block separated by `---` (use # Titles, crisp bullets, and stats for 5–7 slides) about:\n",
        suggestedMode = CapabilityMode.STANDARD
    ),
    SlashCommand(
        trigger = "/csv",
        title = "CSV Dataset & Chart Studio",
        subtitle = "Interactive sortable table + live bar chart in Sandbox",
        promptPrefix = "Output clean CSV data inside a ```csv code block (header row + crisp numeric metrics) ready for interactive table & bar chart preview for:\n",
        suggestedMode = CapabilityMode.CODE_ARCHITECT
    )
)

val DEFAULT_PROMPT_STARTERS = listOf(
    PromptStarter(
        id = "deep_research_agent",
        title = "Deep Research Agent",
        subtitle = "Multi-angle web report + trade-offs",
        prompt = "Research and compare the top local-first Android AI architectures in 2025–2026: on-device SLM vs edge hybrid routing, latency benchmarks, and a Markdown decision matrix.",
        category = "Research",
        suggestedModel = AiModelTier.AUTO_ROUTER,
        suggestedMode = CapabilityMode.DEEP_RESEARCH
    ),
    PromptStarter(
        id = "code_arch",
        title = "Staff Engineer Code Studio",
        subtitle = "Zero-error Kotlin MVVM + StateFlow",
        prompt = "Architect a production-grade Jetpack Compose screen with a StateFlow ViewModel, offline-first Room sync, and defensive error handling for a live habit tracker.",
        category = "Code",
        suggestedModel = AiModelTier.GEMINI_3_8_FLASH,
        suggestedMode = CapabilityMode.CODE_ARCHITECT
    ),
    PromptStarter(
        id = "hard_math",
        title = "Solve hard KaTeX Integration",
        subtitle = "Olympiad-grade steps & boxed proof",
        prompt = "Pick a genuinely challenging JEE Advanced / Olympiad definite integration problem and solve it step-by-step using crisp KaTeX equations and verification.",
        category = "Math",
        suggestedModel = AiModelTier.GEMINI_3_8_FLASH,
        suggestedMode = CapabilityMode.DEEP_THINK
    ),
    PromptStarter(
        id = "live_diagram",
        title = "Interactive Flow & HTML Lab",
        subtitle = "Preview live Mermaid & SVG diagrams",
        prompt = "Create an interactive HTML + SVG dark AMOLED solar system explorer card and a Mermaid architecture flowchart showing how Aura AI processes requests.",
        category = "Artifact",
        suggestedModel = AiModelTier.AUTO_ROUTER,
        suggestedMode = CapabilityMode.CODE_ARCHITECT
    ),
    PromptStarter(
        id = "imagine_art",
        title = "Create futuristic AMOLED art",
        subtitle = "Generate artwork with Aura Imagine",
        prompt = "A sleek futuristic glass prism floating over calm dark obsidian water at night, subtle bioluminescent sapphire and violet reflections, ultra clean minimalist photography",
        category = "Imagine",
        suggestedModel = AiModelTier.NANO_BANANA_2_1,
        suggestedMode = CapabilityMode.STANDARD
    )
)
