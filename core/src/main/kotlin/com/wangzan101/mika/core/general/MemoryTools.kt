package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.learn.memory.Change
import com.wangzan101.mika.core.learn.memory.MemoryContext
import com.wangzan101.mika.core.learn.memory.MemoryIndex
import com.wangzan101.mika.core.learn.memory.MemoryKind
import com.wangzan101.mika.core.learn.memory.MemoryRejected
import com.wangzan101.mika.core.learn.memory.MemorySource
import com.wangzan101.mika.core.learn.memory.MemoryStore
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.EvidenceLog
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private const val MEMORY_SEARCH_LIMIT = 8

/**
 * 按规矩不收(不是用户说的、敏感、没明确要改删):这是对这一条的答复,不是操作出了故障。不计入连续失败——
 * 用户让一次记好几条时,其中几条不收很正常,为此把整个任务按「操作连续失败」停掉的话,已经存上的那几条也跟着算成了失败。
 * 仍然标成不可恢复:同一条别再试。存储满了、读不出来这类真的故障不走这里。
 *
 * 有上限:同一条(同样的参数、同样的原因)被原样再提,或者一个任务里不收的超过 [com.wangzan101.mika.core.tool.MAX_QUIET_MEMORY_REFUSALS] 条,
 * 就不再标「不计入失败」,连续三条就由守卫停下(见 [ToolContext.noteMemoryRefusal])。
 */
private fun refused(ctx: ToolContext, a: Args, error: String, message: String): ToolExecution {
    val result = fail(error, message, recoverable = false)
    return if (ctx.noteMemoryRefusal("${a.tool.name}|$error|${a.o}")) ToolExecution(JsonObject(result.content + ("counts_as_failure" to JsonPrimitive(false))))
    else result
}

/** 记忆和用户本次说过的话都给了才提供。 */
private fun memoryOn(ctx: ToolContext) = ctx.config.memory != null && ctx.config.utterances != null

internal object MemorySearch : AgentTool {
    override val verb = Verb.MemorySearch
    override val label = "查找记忆"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("memory_search",
        Prompts.tool("memory_search"),
        mapOf("query" to str("Words to look for, 1-100 characters.")), listOf("query"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val q = a.str("query", max = 100)!!
        val found = MemoryIndex(ctx.config.memory!!.items.value).search(q, MEMORY_SEARCH_LIMIT)
        ctx.noteMemorySearch(found.map { (m, _) -> m.id })
        return ok {
            put("memories", JsonArray(found.map { (m, _) ->
                buildJsonObject {
                    put("id", m.id); put("kind", m.kind.name); put("content", m.content)
                    // Mika 自己归纳出来的:不是用户说的,标出来
                    if (m.source.type == MemoryContext.INFERRED) put("inferred", true)
                }
            }))
        }
    }

    /** 记忆里的是用户以前说的:作为用户给的事实交给核查。Mika 自己归纳出来的那几条不是用户说的,另作一条工具查到的事实。 */
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        val (own, said) = (r.content["memories"] as? JsonArray).orEmpty().partition { (it as? JsonObject)?.get("inferred") == JsonPrimitive(true) }
        fun lines(list: List<JsonElement>, head: String) =
            list.joinToString("\n", head) { m -> ((m as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty() }
        return listOfNotNull(
            said.takeIf { it.isNotEmpty() }?.let { Observation(ObsKind.UserSaid, lines(it, "记忆检索「${o.argStr("query")}」:\n"), source = "memory") },
            // 归纳的那几条是工具查到的东西,不是用户给的事实:进账本,写明出处
            own.takeIf { it.isNotEmpty() }?.let {
                Observation(ObsKind.Fact, lines(it, "记忆检索「${o.argStr("query")}」查到的、Mika 以前自己归纳的(不是用户说的):\n"), source = "memory")
            },
        )
    }
}

/** 只改 Mika 自己的记忆,不动屏幕和外部世界。 */
internal object MemorySave : AgentTool {
    override val verb = Verb.MemorySave
    override val label = "写入记忆"
    override val risk = Risk.Read
    override val def = ToolDef("memory_save",
        Prompts.tool("memory_save"),
        mapOf(
            "kind" to str("fact = about the user, preference = what the user likes, procedure = how the user wants a kind of task done.", listOf("fact", "preference", "procedure")),
            "content" to str("One sentence in the third person, at most 200 characters, e.g. 用户点外卖不要香菜."),
            "quote" to str("The user's exact words this is based on."),
            "keywords" to buildJsonObject {
                put("type", "array"); put("description", "Optional, up to 8 search words incl. synonyms.")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("kind", "content", "quote"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val kind = a.str("kind")!!.let { k -> MemoryKind.entries.find { it.name == k } ?: throw ArgError("kind", "must be fact, preference or procedure") }
        val content = a.str("content", max = MemoryStore.MAX_CONTENT)!!
        val quote = a.str("quote", max = 1000)!!
        val keywords = a.strList("keywords", 0, MemoryStore.MAX_KEYWORDS, itemMax = 30) ?: emptyList()
        val utterances = ctx.config.utterances!!
        // 引文出自本轮用户原话,或严格登记的上一轮明确确认;屏幕、网页和助手建议本身都不算
        if (!utterances.containsMemoryQuote(quote)) return refused(ctx, a, "quote_not_from_user", Feedback["memory.quote_not_from_user"])
        val store = ctx.config.memory!!
        // 引文对上了,内容里的具体值(地址、号码、名字)也得是用户说过的,不能从屏幕上补。
        // 用户打开了「让 Mika 自己归纳着记」、这一轮又亲口让它记的时候不拒:照存,但来源记成 Mika 归纳的,不冒充用户说的
        val inferred = (listOf(content) + keywords).any { utterances.unsupportedMemorySpecifics(it, quote).isNotEmpty() }
        if (inferred) {
            if (!store.inferenceAllowed()) return refused(ctx, a, "content_not_from_user", Feedback["memory.content_not_from_user"])
            // 开着也不是见什么收什么:引文得落在用户这一轮自己说的、让它记的那半句上。
            // 不然随便一句「帮我看看这个网页」都能被拿来当引文,把屏幕、网页上的字存进来;
            // 「别记我的地址,记住我喜欢喝茶」里引前半句也不行——让记的是后半句
            if (utterances.quotedClauses(quote).none { clauses -> clauses.any(MemoryRequests::asksToRemember) }) {
                return refused(ctx, a, "inference_not_requested", Feedback["memory.inference_not_requested"])
            }
        }
        val added = store.add(kind, content, keywords, MemorySource(if (inferred) MemoryContext.INFERRED else "user_said", quote.trim(),
            ctx.config.conversationId, store.clock(), ctx.config.runSpec?.provenance?.origin?.name))
        added.getOrNull()?.let(ctx::noteSaved)
        return added.fold(
            onSuccess = { c ->
                ok {
                    put("id", c.item.id); put("kind", c.item.kind.name); put("status", c.item.status.name)
                    if (inferred) { put("inferred", true); put("note", Feedback["memory.saved_as_inferred"]) }
                }
            },
            onFailure = { e ->
                when ((e as? MemoryRejected)?.code) {
                    "sensitive" -> refused(ctx, a, "sensitive", Feedback["memory.sensitive"])
                    "memory_full" -> fail("memory_full", Feedback["memory.full"], recoverable = false)
                    "unavailable" -> fail("memory_unavailable", Feedback["memory.unavailable"], recoverable = false)
                    "too_long" -> throw ArgError("content", "must be 1-${MemoryStore.MAX_CONTENT} characters")
                    else -> throw e
                }
            },
        )
    }
}

/** 用户本轮明确要求时,按 memory_search 返回的稳定编号改或删一条;变更交给现有结果提示与撤销机制。 */
internal object MemoryEdit : AgentTool {
    override val verb = Verb.MemoryEdit
    override val label = "修改记忆"
    override val risk = Risk.Read
    override val def = ToolDef("memory_edit",
        Prompts.tool("memory_edit"),
        mapOf(
            "op" to str("update = replace content and optionally keywords; delete = forget the entry.", listOf("update", "delete")),
            "id" to str("Exact stable id returned by memory_search. Never guess an id or choose between multiple plausible entries."),
            "content" to str("For update: the replacement sentence, at most 200 characters."),
            "quote" to str("The current user message explicitly requesting this update or deletion."),
            "keywords" to buildJsonObject {
                put("type", "array"); put("description", "For update: optional replacement search words, up to 8.")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("op", "id", "quote"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val op = a.str("op")!!
        val id = a.str("id", max = 200)!!
        val quote = a.str("quote", max = 1000)!!
        val content = a.str("content", max = MemoryStore.MAX_CONTENT)
        val keywords = a.strList("keywords", 0, MemoryStore.MAX_KEYWORDS, itemMax = 30)
        if (op == "update" && content == null) throw ArgError("content", "required for update")
        val utterances = ctx.config.utterances!!
        // 用含引文的完整原话判断否定，避免模型只摘出「不要删除」里的「删除」。
        val messages = utterances.matchingCurrentMessages(quote)
        if (messages.isEmpty()) return refused(ctx, a, "quote_not_from_user", Feedback["memory.edit_quote_not_from_user"])
        if (op != "update" && op != "delete") throw ArgError("op", "must be update or delete")
        // 话里明说了要改(删),没说不要,也不是「如果我说……你会怎么做」这种只是问问的;各语言的说法在 MemoryRequests
        if (!MemoryRequests.requested(op, quote, messages)) return refused(ctx, a, "edit_not_requested", Feedback["memory.edit_not_requested"])
        val candidates = ctx.memorySearchCandidates()
        if (candidates == null || id !in candidates) {
            return fail("not_found", Feedback["memory.not_found"], recoverable = true)
        }
        if (candidates.size > 1 && !quotesExactId(quote, id)) {
            return fail("ambiguous_target", Feedback["memory.ambiguous_target"], recoverable = false)
        }
        val store = ctx.config.memory!!
        // 这一条原来的样子:里面本来就有的具体值(地址、数字、名字)照样带着不算新补的
        val before = store.items.value.firstOrNull { it.id == id }
        var carried = false
        if (op == "update") {
            val texts = listOf(content!!) + keywords.orEmpty()
            val known = before?.let { listOf(it.content) + it.keywords }.orEmpty()
            if (texts.any { utterances.unsupportedEditSpecifics(it, quote, known).isNotEmpty() }) {
                return refused(ctx, a, "content_not_from_user", Feedback["memory.edit_content_not_from_user"])
            }
            // 新内容里有靠原来那条才站得住的具体值(用户这一轮没说)
            carried = texts.any { utterances.unsupportedSpecifics(it, quote).isNotEmpty() }
        }
        // Mika 自己归纳的那条改写之后还是它归纳的:里面的具体值仍然不是用户说的。用户这一轮把内容都亲口说了的,才改记成用户说的
        val type = if (carried && before?.source?.type == MemoryContext.INFERRED) MemoryContext.INFERRED else "user_said"
        val changed: Result<Change> = when (op) {
            "update" -> store.update(id, content!!, keywords,
                MemorySource(type, quote.trim(), ctx.config.conversationId, store.clock(), ctx.config.runSpec?.provenance?.origin?.name))
            "delete" -> store.forget(id)
            else -> throw ArgError("op", "must be update or delete")
        }
        changed.getOrNull()?.let(ctx::noteSaved)
        return changed.fold(
            onSuccess = { c ->
                ok {
                    put("op", op); put("id", id)
                    when (c) {
                        is Change.Updated -> { put("kind", c.item.kind.name); put("status", c.item.status.name) }
                        is Change.Forgot -> put("deleted", true)
                        else -> Unit
                    }
                }
            },
            onFailure = { e ->
                when ((e as? MemoryRejected)?.code) {
                    "sensitive" -> refused(ctx, a, "sensitive", Feedback["memory.sensitive"])
                    "unavailable" -> fail("memory_unavailable", Feedback["memory.unavailable"], recoverable = false)
                    "not_found" -> fail("not_found", Feedback["memory.not_found"], recoverable = true)
                    "duplicate_id" -> fail("ambiguous_target", Feedback["memory.ambiguous_target"], recoverable = false)
                    "too_long" -> throw ArgError("content", "must be 1-${MemoryStore.MAX_CONTENT} characters")
                    else -> throw e
                }
            },
        )
    }

    private fun quotesExactId(quote: String, id: String): Boolean =
        Regex("(?<![A-Za-z0-9_-])" + Regex.escape(id) + "(?![A-Za-z0-9_-])").containsMatchIn(quote)
}
