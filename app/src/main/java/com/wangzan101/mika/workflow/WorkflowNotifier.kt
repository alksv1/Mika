package com.wangzan101.mika.workflow

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wangzan101.mika.R
import com.wangzan101.mika.core.run.PauseFlag
import com.wangzan101.mika.core.trigger.WORKFLOW_EARLY_STOP
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TriggerRuntime
import com.wangzan101.mika.task.applyScheduledQuietHours
import com.wangzan101.mika.ui.MainActivity

/** Dispatches workflow-level notifications and settles linked scheduled trigger windows on run completion. */
object WorkflowNotifier {
    private const val TAG = "MikaWorkflow"

    fun openRunIntent(context: Context, runId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setIdentifier("wf:$runId")
            .putExtra(MainActivity.EXTRA_OPEN_WORKFLOW_RUN, runId)
        return PendingIntent.getActivity(
            context,
            ("wf:$runId").hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun onRunTransition(context: Context, run: WorkflowRun) {
        when (run.phase) {
            WorkflowPhase.Paused -> if (run.checkpointPaused) notifyCheckpoint(context, run)
            WorkflowPhase.Completed -> run.triggerKey?.let { key ->
                if (run.earlyStopped) {
                    TriggerRuntime.skippedWorkflow(context, key, WORKFLOW_EARLY_STOP)
                } else {
                    TriggerRuntime.workflowDelivered(context, key, run.originThreadId, run.template.title, run.result, run.id)
                }
            }
            WorkflowPhase.Review -> run.triggerKey?.let { key ->
                val reason = run.current?.problem.orEmpty().ifBlank { "review" }
                TriggerRuntime.actionFailed(context, key, reason)
                notifyTriggeredReview(context, run, reason)
            }
            WorkflowPhase.Active, WorkflowPhase.Pausing -> Unit
        }
    }

    private fun notifyCheckpoint(context: Context, run: WorkflowRun) {
        post(
            context = context,
            notificationId = ("wf-ckpt:${run.id}:${run.completed}").hashCode(),
            run = run,
            title = run.template.title,
            body = tr(R.string.workflow_checkpoint_notify, run.completed, run.template.steps.size),
        )
    }

    private fun notifyTriggeredReview(context: Context, run: WorkflowRun, problem: String) {
        val body = when (problem) {
            "interrupted" -> tr(R.string.workflow_interrupted)
            "start_failed" -> tr(R.string.workflow_start_failed)
            PauseFlag.REASON -> tr(R.string.workflow_stopped_paused)
            "result_too_long" -> tr(R.string.workflow_result_too_long)
            else -> tr(R.string.workflow_review_notify)
        }
        post(
            context = context,
            notificationId = ("wf-review:${run.id}").hashCode(),
            run = run,
            title = run.template.title,
            body = body,
        )
    }

    private fun post(context: Context, notificationId: Int, run: WorkflowRun, title: String, body: String) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        TaskService.ensureChannels(context)
        if (!notifications.areNotificationsEnabled()) return
        val builder = Notification.Builder(context, TaskService.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openRunIntent(context, run.id))
            .setAutoCancel(true)
        if (run.unattended) builder.applyScheduledQuietHours(context)
        runCatching { notifications.notify(notificationId, builder.build()) }
            .onFailure { Log.w(TAG, "workflow notification failed", it) }
    }
}
