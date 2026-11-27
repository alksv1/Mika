package com.wangzan101.mika.chat

import android.content.Context
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import java.util.concurrent.ConcurrentHashMap

/**
 * 把对话编号解析成它此刻该用的沙箱工作区([WorkspaceKey]):
 * - 属于多步骤工作流运行 -> [WorkspaceKey.Workflow]
 * - 已绑定或草稿选定有效项目 -> [WorkspaceKey.Project]
 * - 未归属项目或项目已不存在 -> [WorkspaceKey.Conversation]
 */
class WorkspaceResolver(
    private val conversations: ConversationStore,
    private val projects: ProjectStore,
    private val draftProject: (String) -> String? = { null },
    private val workflowRunIdOf: (String) -> String? = { null },
) {
    private val draftProjects = ConcurrentHashMap<String, String>()

    fun setDraftProject(conversationId: String, projectId: String?) {
        if (projectId == null) draftProjects.remove(conversationId)
        else draftProjects[conversationId] = projectId
    }

    fun workflowRunOf(conversationId: String): String? =
        workflowRunIdOf.invoke(conversationId)?.takeIf(WorkspaceKey::isValidWorkflowId)

    fun projectIdOf(conversationId: String): String? =
        if (workflowRunOf(conversationId) != null) null
        else (conversations.projectId(conversationId) ?: draftProjects[conversationId] ?: draftProject(conversationId))
            ?.takeIf { projects.get(it) != null }

    fun resolve(conversationId: String): WorkspaceKey =
        workflowRunOf(conversationId)?.let(WorkspaceKey::Workflow)
            ?: projectIdOf(conversationId)?.let(WorkspaceKey::Project)
            ?: WorkspaceKey.Conversation(conversationId)

    fun isShared(conversationId: String): Boolean =
        workflowRunOf(conversationId) != null || projectIdOf(conversationId) != null

    companion object {
        fun resolveFor(context: Context, conversationId: String): WorkspaceKey =
            (context.applicationContext as? MikaApp)?.workspaceResolver?.resolve(conversationId)
                ?: WorkspaceKey.Conversation(conversationId)

        fun resolver(context: Context): (String) -> WorkspaceKey = { id -> resolveFor(context, id) }
    }
}
