package com.wangzan101.mika.workflow

import com.wangzan101.mika.settings.TaskTemplate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Parameter extraction, built-in date variables, and `{{step.N}}` references for multi-step workflows.
 * All substitutions run in a single pass so values are treated literally and never recursively expanded.
 */
object WorkflowParams {
    private val marker = Regex("\\{\\{([^{}\\r\\n]{1,40})\\}\\}")
    private val stepRef = Regex("^step\\.([1-8])$")
    private val enBuiltIns = setOf("today", "yesterday", "tomorrow", "weekday", "week", "month")
    private val zhBuiltIns = setOf("今天", "昨天", "明天", "星期", "星期几", "本周", "本月")

    fun isBuiltIn(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.lowercase(Locale.ROOT) in enBuiltIns || trimmed in zhBuiltIns
    }

    fun isStepRef(name: String): Boolean = stepRef.matches(name.trim())

    /** Distinct user-supplied parameter fields across all steps in first-appearance order. */
    fun userFields(steps: List<WorkflowStep>): List<String> = buildList {
        for (step in steps) {
            for (text in listOf(step.instruction, step.continueIf)) {
                marker.findAll(text).forEach { match ->
                    val name = match.groupValues[1].trim()
                    if (name.isNotEmpty() && !isBuiltIn(name) && !isStepRef(name) && name !in this) {
                        add(name)
                    }
                }
            }
        }
    }

    /** Renders user fields and built-in date variables at run start while keeping `{{step.N}}` untouched. */
    fun renderTemplate(
        template: WorkflowTemplate,
        values: Map<String, String>,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): WorkflowTemplate? {
        val fields = userFields(template.steps)
        if (fields.size > TaskTemplate.MAX_FIELDS || fields.any { values[it].isNullOrBlank() }) return null
        val builtIns = builtInValues(nowMs, zone, locale)
        fun renderText(text: String): String = marker.replace(text) { match ->
            val name = match.groupValues[1].trim()
            if (name.isEmpty() || isStepRef(name)) return@replace match.value
            values[name]?.trim()
                ?: builtIns[name.lowercase(Locale.ROOT)]
                ?: builtIns[name]
                ?: match.value
        }
        return template.copy(
            steps = template.steps.map { step ->
                step.copy(
                    instruction = renderText(step.instruction),
                    continueIf = renderText(step.continueIf),
                )
            },
        )
    }

    /** Replaces `{{step.k}}` (1-based) with completed step output; oversized outputs are clipped with a workspace hint. */
    fun resolveStepRefs(text: String, priorResults: List<String>, maxInlineChars: Int = 4000): String =
        marker.replace(text) { match ->
            val name = match.groupValues[1].trim()
            val ref = stepRef.matchEntire(name) ?: return@replace match.value
            val index = ref.groupValues[1].toInt() - 1
            val raw = priorResults.getOrNull(index) ?: return@replace match.value
            if (raw.length <= maxInlineChars) raw
            else raw.take(maxInlineChars) + "\n[Truncated; full output saved at /workspace/steps/step-${index + 1}/result.md]"
        }

    internal fun builtInValues(nowMs: Long, zone: ZoneId, locale: Locale): Map<String, String> {
        val date = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val today = DateTimeFormatter.ISO_LOCAL_DATE.format(date)
        val yesterday = DateTimeFormatter.ISO_LOCAL_DATE.format(date.minusDays(1))
        val tomorrow = DateTimeFormatter.ISO_LOCAL_DATE.format(date.plusDays(1))
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        val week = "%04d-W%02d".format(
            Locale.ROOT,
            date.get(WeekFields.ISO.weekBasedYear()),
            date.get(WeekFields.ISO.weekOfWeekBasedYear()),
        )
        val month = "%04d-%02d".format(Locale.ROOT, date.year, date.monthValue)
        return mapOf(
            "today" to today, "今天" to today,
            "yesterday" to yesterday, "昨天" to yesterday,
            "tomorrow" to tomorrow, "明天" to tomorrow,
            "weekday" to weekday, "星期" to weekday, "星期几" to weekday,
            "week" to week, "本周" to week,
            "month" to month, "本月" to month,
        )
    }
}
