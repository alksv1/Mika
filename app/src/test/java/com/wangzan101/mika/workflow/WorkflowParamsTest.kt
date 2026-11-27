package com.wangzan101.mika.workflow

import com.wangzan101.mika.settings.TaskTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class WorkflowParamsTest {
    private val now = Instant.parse("2026-11-27T06:00:00Z").toEpochMilli() // 2026-11-27 14:00 Asia/Shanghai (Friday)
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun extractsOnlyUserFieldsExcludingBuiltInsAndStepRefs() {
        val steps = listOf(
            WorkflowStep(
                title = "Collect",
                instruction = "Read {{group}} on {{today}} ({{今天}}) for {{project}}.",
                continueIf = "{{project}} has new messages in {{group}}",
            ),
            WorkflowStep(
                title = "Summarize",
                instruction = "Using {{step.1}}, prepare report for {{recipient}} in {{month}}.",
            ),
        )
        assertEquals(listOf("group", "project", "recipient"), WorkflowParams.userFields(steps))
    }

    @Test fun enforcesMaxUserFieldsLimit() {
        val many = (1..TaskTemplate.MAX_FIELDS + 1).joinToString(" ") { "{{field$it}}" }
        val template = WorkflowTemplate(
            "t", 1, "Many",
            listOf(WorkflowStep(title = "A", instruction = many), WorkflowStep(title = "B", instruction = "Done")),
        )
        val full = (1..TaskTemplate.MAX_FIELDS + 1).associate { "field$it" to "v$it" }
        assertNull(WorkflowParams.renderTemplate(template, full, now, zone, Locale.US))
    }

    @Test fun singlePassRenderingResolvesDatesAndKeepsStepRefsLiteral() {
        val template = WorkflowTemplate(
            "t", 1, "Report",
            listOf(
                WorkflowStep(
                    title = "Collect",
                    instruction = "Check {{group}} for {{today}}, {{yesterday}}, {{week}}, {{month}}, {{weekday}}.",
                    continueIf = "{{group}} updated on {{今天}}",
                ),
                WorkflowStep(
                    title = "Send",
                    instruction = "Send {{step.1}} to {{target}}.",
                ),
            ),
        )
        assertNull(WorkflowParams.renderTemplate(template, mapOf("group" to "A群"), now, zone, Locale.US))
        // User value contains another marker to ensure non-recursive single-pass substitution.
        val rendered = WorkflowParams.renderTemplate(
            template,
            mapOf("group" to "A群 {{target}}", "target" to "老板"),
            now,
            zone,
            Locale.SIMPLIFIED_CHINESE,
        )!!
        assertEquals(
            "Check A群 {{target}} for 2026-11-27, 2026-11-26, 2026-W48, 2026-11, 星期五.",
            rendered.steps[0].instruction,
        )
        assertEquals("A群 {{target}} updated on 2026-11-27", rendered.steps[0].continueIf)
        assertEquals("Send {{step.1}} to 老板.", rendered.steps[1].instruction)
    }

    @Test fun resolvesStepRefsClipsOversizedAndBuildsWorkspacePrompt() {
        val longStep1 = "A".repeat(4500)
        val resolved = WorkflowParams.resolveStepRefs("Summary: {{step.1}} / {{step.2}}", listOf(longStep1, "Step2Data"))
        assertTrue(resolved.contains("/workspace/steps/step-1/result.md"))
        assertTrue(resolved.contains("Step2Data"))
        assertFalse(resolved.contains("{{step.1}}"))

        val dispatch = WorkflowDispatch(
            runId = "run-1",
            attemptId = "att-3",
            conversationId = "conv-3",
            step = WorkflowStep(title = "Final", instruction = "Format {{step.1}} and {{step.2}}."),
            input = "Use markdown",
            previous = "Step 2 output",
            stepIndex = 2,
            priorResults = listOf("Step 1 output", "Step 2 output"),
            priorStepTitles = listOf("Gather", "Analyze"),
        )
        val noFilesPrompt = WorkflowPromptBuilder.build(dispatch)
        assertTrue(noFilesPrompt.contains("Earlier steps summary:"))
        assertTrue(noFilesPrompt.contains("Step 1 (Gather)"))
        assertTrue(noFilesPrompt.contains("Format Step 1 output and Step 2 output."))
        assertFalse(noFilesPrompt.contains("Shared workspace files"))

        val withFilesPrompt = WorkflowPromptBuilder.build(
            dispatch,
            artifacts = listOf(WorkflowWorkspace.Artifact("/workspace/steps/step-1/data.csv", "steps/step-1/data.csv", 2048)),
        )
        assertTrue(withFilesPrompt.contains("Shared workspace files (save new outputs for this step under /workspace/steps/step-3/):"))
        assertTrue(withFilesPrompt.contains("- /workspace/steps/step-1/data.csv (2 KB)"))
    }
}
