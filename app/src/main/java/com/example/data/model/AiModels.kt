package com.example.data.model

enum class ThemeMode(val label: String) {
    DARK("AMOLED Black"),
    LIGHT("Clean Light"),
    SYSTEM("Match System")
}

enum class ModelCategory(val title: String) {
    CHAT_AND_VISION("Chat, Code & Multimodal Vision"),
    IMAGE_GENERATION("Imagine · Image Generation & Editing")
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
    GEMINI_3_8_FLASH(
        id = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash",
        shortName = "3.8 Flash",
        subtitle = "Most versatile frontier model for chat, code & vision",
        badge = "DEFAULT",
        contextLabel = "1M ctx",
        category = ModelCategory.CHAT_AND_VISION
    ),
    GEMINI_4_ARGON(
        id = "gemini-4-argon",
        displayName = "Gemini 4 Argon",
        shortName = "4 Argon",
        subtitle = "Most powerful model for deep reasoning, math & STEM",
        badge = "PRO",
        contextLabel = "Blaze",
        category = ModelCategory.CHAT_AND_VISION
    ),
    GEMINI_3_5_FLASH_LITE(
        id = "gemini-3.5-flash-lite",
        displayName = "Gemini 3.5 Flash-Lite",
        shortName = "3.5 Lite",
        subtitle = "Ultra-fast, low-latency everyday responses",
        badge = "LITE",
        contextLabel = "Fast",
        category = ModelCategory.CHAT_AND_VISION
    ),
    NANO_BANANA_2_1(
        id = "gemini-nano-banana-2.1",
        displayName = "Nano Banana 2.1",
        shortName = "Nano Banana 2.1",
        subtitle = "Fast text-to-image generation and photo editing",
        badge = "IMAGE",
        contextLabel = "Blaze",
        category = ModelCategory.IMAGE_GENERATION,
        isImageModel = true
    ),
    GEMINI_3_PRO_IMAGE(
        id = "gemini-3-pro-image",
        displayName = "Gemini 3 Pro Image",
        shortName = "3 Pro Image",
        subtitle = "Studio-quality asset production & crisp text rendering",
        badge = "PRO IMG",
        contextLabel = "Blaze",
        category = ModelCategory.IMAGE_GENERATION,
        isImageModel = true
    ),
    GEMINI_3_1_FLASH_LITE_IMAGE(
        id = "gemini-3.1-flash-lite-image",
        displayName = "Gemini 3.1 Flash-Lite Image",
        shortName = "3.1 Image Lite",
        subtitle = "Ultra-low latency image creation & remixing",
        badge = "FAST IMG",
        contextLabel = "Blaze",
        category = ModelCategory.IMAGE_GENERATION,
        isImageModel = true
    );

    companion object {
        fun fromId(id: String): AiModelTier =
            entries.find { it.id == id } ?: GEMINI_3_8_FLASH
    }
}

enum class CapabilityMode(
    val id: String,
    val label: String,
    val description: String
) {
    STANDARD("standard", "Balanced", "Clear, natural Gemini responses"),
    DEEP_THINK("deep_think", "Deep Think", "Step-by-step visible reasoning before final answer"),
    CODE_ARCHITECT("code", "Code Studio", "Production code, architecture & edge cases"),
    CONCISE("concise", "Concise", "Crisp executive summary & bullet points");

    companion object {
        fun fromId(id: String): CapabilityMode =
            entries.find { it.id == id } ?: STANDARD
    }
}

data class AttachmentDraft(
    val uri: String,
    val displayName: String,
    val isImage: Boolean,
    val textSnippet: String? = null
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

val DEFAULT_PROMPT_STARTERS = listOf(
    PromptStarter(
        id = "code_arch",
        title = "Build a Jetpack Compose screen",
        subtitle = "Clean MVVM + StateFlow Kotlin code",
        prompt = "Write a clean Jetpack Compose screen with a StateFlow ViewModel for a live habit tracker card with animated progress.",
        category = "Code",
        suggestedModel = AiModelTier.GEMINI_3_8_FLASH,
        suggestedMode = CapabilityMode.CODE_ARCHITECT
    ),
    PromptStarter(
        id = "imagine_art",
        title = "Create futuristic AMOLED art",
        subtitle = "Generate with Nano Banana 2.1",
        prompt = "A sleek futuristic glass prism floating over calm dark obsidian water at night, subtle bioluminescent sapphire and violet reflections, ultra clean minimalist photography",
        category = "Imagine",
        suggestedModel = AiModelTier.NANO_BANANA_2_1,
        suggestedMode = CapabilityMode.STANDARD
    ),
    PromptStarter(
        id = "deep_reason",
        title = "Compare system trade-offs",
        subtitle = "Step-by-step reasoning breakdown",
        prompt = "Compare edge caching vs server-side streaming for a mobile AI assistant. Break down latency, battery, and offline UX.",
        category = "Analyze",
        suggestedModel = AiModelTier.GEMINI_3_8_FLASH,
        suggestedMode = CapabilityMode.DEEP_THINK
    ),
    PromptStarter(
        id = "study_coach",
        title = "Explain quantum computing simply",
        subtitle = "Intuitive mental models & summary",
        prompt = "Explain qubits, superposition, and entanglement using simple everyday analogies, then give a 3-point takeaway.",
        category = "Learn",
        suggestedModel = AiModelTier.GEMINI_3_5_FLASH_LITE,
        suggestedMode = CapabilityMode.CONCISE
    )
)
