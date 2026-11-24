package com.example.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class NamedCodeFile(
    val relativePath: String,
    val language: String,
    val content: String
)

object ProjectZipExporter {

    private val FENCED_BLOCK_REGEX = Regex("```([^\\n`]*)\\n([\\s\\S]*?)(?:```|$)")
    private val COMMENT_FILE_REGEX = Regex(
        "^(?://|#|--|/\\*|<!--)\\s*(?:File|Path)\\s*:\\s*([^\\s*]+)(?:\\s*\\*/|\\s*-->)?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val HEADING_FILE_REGEX = Regex(
        "(?:`([^`\\s]+\\.[a-zA-Z0-9]{1,8})`|(?:^|\\s)([a-zA-Z0-9_./-]+\\.[a-zA-Z0-9]{1,8})(?:\\s*\\*\\*|\\s*:|\\s*$))"
    )

    fun extractNamedFilesFromMarkdown(markdown: String): List<NamedCodeFile> {
        if (markdown.isBlank()) return emptyList()
        val seenPaths = mutableSetOf<String>()
        val results = mutableListOf<NamedCodeFile>()

        for (match in FENCED_BLOCK_REGEX.findAll(markdown)) {
            val header = match.groupValues[1].trim()
            val body = match.groupValues[2].trimEnd()
            if (body.isBlank()) continue

            val colonIdx = header.indexOf(':')
            val lang = if (colonIdx > 0) header.substring(0, colonIdx).trim().lowercase() else header.lowercase()
            if (lang == "diff" || lang == "sandbox-output") continue

            val headerFile = if (colonIdx > 0) header.substring(colonIdx + 1).trim() else ""
            val firstLine = body.lineSequence().firstOrNull()?.trim().orEmpty()
            val commentFile = COMMENT_FILE_REGEX.find(firstLine)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val precedingFile = if (headerFile.isBlank() && commentFile.isBlank()) {
                extractPrecedingFileHint(markdown, match.range.first)
            } else ""

            val rawPath = headerFile.ifBlank { commentFile.ifBlank { precedingFile } }
            if (rawPath.isBlank()) continue

            val safePath = sanitizeArchivePath(rawPath) ?: continue
            val dedupPath = deduplicatePath(safePath, seenPaths)
            seenPaths.add(dedupPath)

            val cleanedContent = if (commentFile.isNotBlank() && headerFile.isBlank()) {
                body.lineSequence().drop(1).joinToString("\n").trimStart('\r', '\n')
            } else {
                body
            }
            if (cleanedContent.isBlank()) continue

            results.add(
                NamedCodeFile(
                    relativePath = dedupPath,
                    language = lang.ifBlank { dedupPath.substringAfterLast('.', "txt").lowercase() },
                    content = cleanedContent + "\n"
                )
            )
        }
        return results
    }

    private fun extractPrecedingFileHint(markdown: String, fenceStartOffset: Int): String {
        if (fenceStartOffset <= 0) return ""
        val prefix = markdown.substring(0, fenceStartOffset)
        val nonBlankLines = prefix.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .takeLast(2)
        for (line in nonBlankLines.asReversed()) {
            val cleaned = line
                .removePrefix("###")
                .removePrefix("##")
                .removePrefix("#")
                .trim()
            val isStructuredLine = line.startsWith("#") ||
                line.contains("`") ||
                line.startsWith("**") ||
                line.contains("File:", ignoreCase = true) ||
                Regex("^\\d+\\.\\s+").containsMatchIn(line)
            if (!isStructuredLine) continue

            val m = HEADING_FILE_REGEX.find(cleaned)
            val candidate = (m?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: m?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() })
                .orEmpty()
            if (candidate.isNotBlank() && !candidate.startsWith("http", ignoreCase = true)) {
                return candidate.trim('`', '*', ':', ' ')
            }
        }
        return ""
    }

    fun sanitizeArchivePath(rawPath: String): String? {
        val normalized = rawPath
            .replace('\\', '/')
            .trim()
            .trimStart('/')
        if (normalized.isBlank()) return null
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." || it == "." || it.contains(':') }) return null
        return segments.joinToString("/")
    }

    private fun deduplicatePath(path: String, existing: Set<String>): String {
        if (path !in existing) return path
        val dot = path.lastIndexOf('.')
        val base = if (dot > 0) path.substring(0, dot) else path
        val ext = if (dot > 0) path.substring(dot) else ""
        var counter = 2
        while ("${base}_$counter$ext" in existing) {
            counter++
        }
        return "${base}_$counter$ext"
    }

    fun buildZipBytes(files: List<NamedCodeFile>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val written = mutableSetOf<String>()
            files.forEach { file ->
                val safe = sanitizeArchivePath(file.relativePath) ?: return@forEach
                val unique = deduplicatePath(safe, written)
                written.add(unique)
                zos.putNextEntry(ZipEntry(unique))
                zos.write(file.content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    fun buildAndroidApkProjectFiles(appName: String, htmlCode: String): List<NamedCodeFile> {
        val cleanTitle = appName.trim().ifBlank { "Aura App" }.take(28)
        val xmlSafeTitle = cleanTitle
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "\\'")
        val slug = cleanTitle
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "")
            .take(18)
            .ifBlank { "auraapp" }
        val appId = "com.aura.generated.$slug"

        val settingsGradle = """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "$slug"
            include(":app")
        """.trimIndent() + "\n"

        val rootBuildGradle = """
            plugins {
                id("com.android.application") version "8.7.2" apply false
                id("org.jetbrains.kotlin.android") version "2.0.21" apply false
            }
        """.trimIndent() + "\n"

        val gradleProperties = """
            org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
            android.useAndroidX=true
            kotlin.code.style=official
            android.nonTransitiveRClass=true
        """.trimIndent() + "\n"

        val appBuildGradle = """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }

            android {
                namespace = "$appId"
                compileSdk = 35

                defaultConfig {
                    applicationId = "$appId"
                    minSdk = 24
                    targetSdk = 35
                    versionCode = 1
                    versionName = "1.0.0"
                }

                buildTypes {
                    release {
                        isMinifyEnabled = false
                    }
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                kotlinOptions {
                    jvmTarget = "17"
                }
            }

            dependencies {
                implementation("androidx.core:core-ktx:1.15.0")
                implementation("androidx.appcompat:appcompat:1.7.0")
            }
        """.trimIndent() + "\n"

        val manifestXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.INTERNET" />
                <application
                    android:allowBackup="true"
                    android:label="@string/app_name"
                    android:supportsRtl="true"
                    android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true"
                        android:configChanges="orientation|screenSize|keyboardHidden">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent() + "\n"

        val stringsXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">$xmlSafeTitle</string>
            </resources>
        """.trimIndent() + "\n"

        val packagePath = appId.replace('.', '/')
        val mainActivityKt = """
            package $appId

            import android.annotation.SuppressLint
            import android.os.Bundle
            import android.webkit.WebChromeClient
            import android.webkit.WebSettings
            import android.webkit.WebView
            import android.webkit.WebViewClient
            import androidx.activity.OnBackPressedCallback
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
                private lateinit var webView: WebView

                @SuppressLint("SetJavaScriptEnabled")
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    webView = WebView(this).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            allowFileAccess = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        }
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        loadUrl("file:///android_asset/index.html")
                    }
                    setContentView(webView)

                    onBackPressedDispatcher.addCallback(
                        this,
                        object : OnBackPressedCallback(true) {
                            override fun handleOnBackPressed() {
                                if (webView.canGoBack()) webView.goBack() else finish()
                            }
                        }
                    )
                }
            }
        """.trimIndent() + "\n"

        val githubWorkflow = """
            name: Build Debug APK
            on:
              push:
              workflow_dispatch:
            jobs:
              build-apk:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - uses: actions/setup-java@v4
                    with:
                      distribution: temurin
                      java-version: 17
                  - uses: gradle/actions/setup-gradle@v4
                    with:
                      gradle-version: '8.10.2'
                  - name: Assemble Debug APK
                    run: gradle assembleDebug
                  - name: Upload APK Artifact
                    uses: actions/upload-artifact@v4
                    with:
                      name: ${slug}-debug-apk
                      path: app/build/outputs/apk/debug/*.apk
        """.trimIndent() + "\n"

        val readmeMd = """
            # $cleanTitle — Android APK Project

            Generated by **Aura App Builder** (`$appId`).

            ## Build Installable `.apk` in 1 Click (No PC Needed via GitHub)
            1. Push this extracted folder to a GitHub repository.
            2. Open the **Actions** tab and run **Build Debug APK** (runs automatically on push).
            3. Download `${slug}-debug-apk` (`app-debug.apk`) directly onto your Android phone!

            ## Build Locally in Android Studio
            Open this folder in Android Studio and select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
        """.trimIndent() + "\n"

        return listOf(
            NamedCodeFile("settings.gradle.kts", "kotlin", settingsGradle),
            NamedCodeFile("build.gradle.kts", "kotlin", rootBuildGradle),
            NamedCodeFile("gradle.properties", "properties", gradleProperties),
            NamedCodeFile("app/build.gradle.kts", "kotlin", appBuildGradle),
            NamedCodeFile("app/src/main/AndroidManifest.xml", "xml", manifestXml),
            NamedCodeFile("app/src/main/res/values/strings.xml", "xml", stringsXml),
            NamedCodeFile("app/src/main/java/$packagePath/MainActivity.kt", "kotlin", mainActivityKt),
            NamedCodeFile("app/src/main/assets/index.html", "html", htmlCode.trim() + "\n"),
            NamedCodeFile(".github/workflows/build-apk.yml", "yaml", githubWorkflow),
            NamedCodeFile("README.md", "markdown", readmeMd)
        )
    }

    fun exportAndroidApkProjectZipUri(
        context: Context,
        appName: String,
        htmlCode: String
    ): Pair<Uri, String> {
        val files = buildAndroidApkProjectFiles(appName = appName, htmlCode = htmlCode)
        val bytes = buildZipBytes(files)
        val exportDir = File(context.cacheDir, "exports").apply {
            if (!exists()) mkdirs()
        }
        val cleanBase = appName
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .take(32)
            .ifBlank { "aura_apk_project" }
        val fileName = "${cleanBase}_android_apk_project.zip"
        val outFile = File(exportDir, fileName)
        FileOutputStream(outFile).use { fos ->
            fos.write(bytes)
            fos.flush()
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        return uri to fileName
    }

    fun exportMultiFileProjectZipUri(
        context: Context,
        markdown: String,
        projectName: String = "aura_project"
    ): Pair<Uri, Int> {
        val files = extractNamedFilesFromMarkdown(markdown)
        require(files.isNotEmpty()) { "No named files found to export." }
        val bytes = buildZipBytes(files)
        val exportDir = File(context.cacheDir, "exports").apply {
            if (!exists()) mkdirs()
        }
        val cleanBase = projectName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "aura_project" }
        val outFile = File(exportDir, "${cleanBase}_${System.currentTimeMillis()}.zip")
        FileOutputStream(outFile).use { fos ->
            fos.write(bytes)
            fos.flush()
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        return uri to files.size
    }
}
