package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AuraDatabase
import com.example.data.local.AutopilotTaskEntity
import com.example.data.local.MessageEntity
import com.example.data.model.AiModelTier
import com.example.data.model.AppLanguage
import com.example.data.model.CapabilityMode
import com.example.data.preferences.PreferencesManager
import com.example.data.repository.AiRequest
import com.example.data.repository.AiStreamEvent
import com.example.data.repository.ChatRepository
import com.example.data.repository.DirectGeminiAiRepository
import com.example.data.repository.serializeSources
import kotlinx.coroutines.flow.first
import java.util.UUID
import kotlin.math.absoluteValue

object AutopilotScheduler {
    const val EXTRA_TASK_ID = "autopilot_task_id"
    const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    private const val JOB_ID_BASE = 41_000
    private const val JOB_ID_SPAN = 8_000

    fun jobIdForTask(taskId: String): Int {
        return JOB_ID_BASE + (taskId.hashCode().absoluteValue % JOB_ID_SPAN)
    }

    fun syncSchedules(context: Context, tasks: List<AutopilotTaskEntity>) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val enabledIds = tasks.filter { it.isEnabled }.map { jobIdForTask(it.id) }.toSet()

        scheduler.allPendingJobs
            .filter { it.id in JOB_ID_BASE until (JOB_ID_BASE + JOB_ID_SPAN) && it.id !in enabledIds }
            .forEach { scheduler.cancel(it.id) }

        tasks.filter { it.isEnabled }.forEach { task ->
            val jobId = jobIdForTask(task.id)
            val intervalMs = (task.intervalHours.coerceIn(1, 168) * 3_600_000L).coerceAtLeast(900_000L)
            val extras = PersistableBundle().apply {
                putString(EXTRA_TASK_ID, task.id)
            }
            val builder = JobInfo.Builder(
                jobId,
                ComponentName(context, AuraAutopilotJobService::class.java)
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setExtras(extras)
                .setPeriodic(intervalMs)

            scheduler.schedule(builder.build())
        }
    }

    fun cancelTaskJob(context: Context, taskId: String) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        scheduler.cancel(jobIdForTask(taskId))
    }
}

object AutopilotRunner {
    private const val CHANNEL_ID = "aura_autopilot"

    suspend fun runTaskById(context: Context, taskId: String): Result<String> {
        val appContext = context.applicationContext
        val dao = AuraDatabase.getInstance(appContext).auraDao()
        val task = dao.getAutopilotTaskById(taskId)
            ?: return Result.failure(IllegalArgumentException("Autopilot task not found"))
        return runTask(appContext, task)
    }

    suspend fun runTask(context: Context, task: AutopilotTaskEntity): Result<String> {
        val appContext = context.applicationContext
        val dao = AuraDatabase.getInstance(appContext).auraDao()
        val chatRepository = ChatRepository(dao)
        val prefs = PreferencesManager(appContext)
        val aiRepository = DirectGeminiAiRepository(appContext)

        chatRepository.ensureDefaultPersonas()
        val personas = dao.getAllPersonasOnce()
        val activePersonaId = prefs.activePersonaId.first()
        val activePersona = personas.find { it.id == activePersonaId } ?: personas.firstOrNull()
        val activeMemories = chatRepository.getActiveMemoriesOnce()
            .filter { it.isEnabled }
            .map { "[${it.category}] ${it.content}" }

        val targetConvo = task.lastConversationId?.let { chatRepository.getConversation(it) }
            ?: chatRepository.createConversation(
                title = "Autopilot · ${task.title.take(44)}",
                modelId = AiModelTier.GEMINI_3_8_FLASH.id,
                personaId = activePersona?.id ?: "persona_general"
            )

        val historyBefore = chatRepository.getMessagesOnce(targetConvo.id)
        val now = System.currentTimeMillis()
        chatRepository.saveMessage(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = targetConvo.id,
                role = "user",
                content = "[Aura Autopilot · ${task.title}]\n${task.prompt}",
                timestamp = now
            )
        )

        val request = AiRequest(
            prompt = task.prompt,
            modelTier = AiModelTier.GEMINI_3_8_FLASH,
            capabilityMode = if (task.useWebSearch) CapabilityMode.DEEP_THINK else CapabilityMode.STANDARD,
            language = prefs.appLanguage.first(),
            persona = activePersona,
            history = historyBefore,
            activeMemories = activeMemories,
            useWebSearch = task.useWebSearch,
            useCodeExecution = false,
            enableSelfVerify = false,
            enableCouncilMode = false
        )

        var completedEvent: AiStreamEvent.Completed? = null
        var failedMessage: String? = null
        aiRepository.streamResponse(request).collect { event ->
            when (event) {
                is AiStreamEvent.Completed -> completedEvent = event
                is AiStreamEvent.Failed -> failedMessage = event.errorMessage
                else -> Unit
            }
        }

        val completed = completedEvent
        return if (completed != null && completed.fullText.isNotBlank()) {
            val reply = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = targetConvo.id,
                role = "assistant",
                content = completed.fullText,
                thinkingContent = completed.thinkingText,
                thinkingDurationMs = completed.thinkingDurationMs,
                modelDisplayName = "Aura Autopilot",
                sourcesJson = serializeSources(completed.sources),
                isError = false,
                timestamp = System.currentTimeMillis()
            )
            chatRepository.saveMessage(reply)

            val summary = completed.fullText
                .replace(Regex("[#*`>\\n]+"), " ")
                .trim()
                .take(150)

            dao.updateAutopilotTaskRun(
                id = task.id,
                lastRunAt = System.currentTimeMillis(),
                summary = summary,
                convoId = targetConvo.id
            )

            postAutopilotNotification(
                context = appContext,
                task = task,
                summary = summary,
                conversationId = targetConvo.id
            )
            Result.success(targetConvo.id)
        } else {
            val errText = failedMessage ?: "Autopilot brief could not complete."
            chatRepository.saveMessage(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = targetConvo.id,
                    role = "assistant",
                    content = errText,
                    modelDisplayName = "Aura Autopilot",
                    isError = true,
                    timestamp = System.currentTimeMillis()
                )
            )
            Result.failure(IllegalStateException(errText))
        }
    }

    private fun postAutopilotNotification(
        context: Context,
        task: AutopilotTaskEntity,
        summary: String,
        conversationId: String
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aura Autopilot Briefings",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Autonomous scheduled briefings and alerts prepared by Aura AI"
            }
            manager.createNotificationChannel(channel)
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AutopilotScheduler.EXTRA_CONVERSATION_ID, conversationId)
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            AutopilotScheduler.jobIdForTask(task.id),
            tapIntent,
            pendingFlags
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Aura Autopilot · ${task.title}")
            .setContentText(summary.ifBlank { "Your scheduled briefing is ready." })
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            manager.notify(AutopilotScheduler.jobIdForTask(task.id), notification)
        }
    }
}
