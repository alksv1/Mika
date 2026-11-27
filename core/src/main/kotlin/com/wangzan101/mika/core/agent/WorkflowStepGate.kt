package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Decision from evaluating a workflow step's [continueIf] gate after the step finishes.
 * [shouldContinue] is null when the evaluation failed, timed out, or produced unparseable output.
 */
data class StepGateDecision(
    val shouldContinue: Boolean?,
    val reason: String = "",
    val model: String? = null,
)

/**
 * Lightweight, prompt-injection-resistant evaluator for workflow step continuation conditions.
 * Tries [judge] (typically the light model) and falls back to [fallback] on error/timeout/unparseable reply.
 */
class WorkflowStepGate(
    private val judge: ChatModel,
    private val fallback: ChatModel? = null,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    suspend fun evaluate(condition: String, stepResult: String): StepGateDecision {
        val input = buildString {
            // Strip closing/opening tags from untrusted inputs so they cannot break out of their section.
            append("<condition>\n").append(untag(condition)).append("\n</condition>\n\n")
            append("<result>\n").append(untag(stepResult)).append("\n</result>")
        }
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        for (candidate in listOfNotNull(judge, fallback?.takeUnless { it === judge })) {
            val parsed = try {
                withTimeoutOrNull(timeoutMs) {
                    val reply = candidate.complete(Prompts.role("workflow_gate"), messages, emptyList())
                    parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (parsed != null) return parsed.copy(model = candidate.name.ifBlank { null })
        }
        return StepGateDecision(null)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L

        private val TAG = Regex("</?\\s*(condition|result)\\s*>", RegexOption.IGNORE_CASE)

        private fun untag(s: String) = s.replace(TAG, "")

        /**
         * Parses the last valid JSON object in [raw] containing a strict boolean `"continue"` field
         * and an optional `"reason"` string.
         */
        fun parse(raw: String): StepGateDecision? {
            var end = raw.lastIndexOf('}')
            while (end >= 0) {
                var start = raw.lastIndexOf('{', end)
                while (start >= 0) {
                    of(raw.substring(start, end + 1))?.let { return it }
                    start = if (start == 0) -1 else raw.lastIndexOf('{', start - 1)
                }
                end = if (end == 0) -1 else raw.lastIndexOf('}', end - 1)
            }
            return null
        }

        private fun of(json: String): StepGateDecision? {
            val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            val contPrimitive = (obj["continue"] as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
            val shouldContinue = when (contPrimitive.content) {
                "true" -> true
                "false" -> false
                else -> return null
            }
            val reason = (obj["reason"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            return StepGateDecision(shouldContinue, if (shouldContinue) "" else reason)
        }
    }
}
