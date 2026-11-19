package com.wangzan101.mika.chat

import com.wangzan101.mika.cap.sandbox.WorkspaceKey

/**
 * 把对话编号解析成它此刻该用的沙箱工作区([WorkspaceKey]):
 * - 已绑定或草稿选定有效项目 -> [WorkspaceKey.Project]
 * - 未归属项目或项目已不存在 -> [WorkspaceKey.Conversation]
 */
class WorkspaceResolver(
    private val conversations: ConversationStore,
    private val projects: ProjectStore,
    private val draftProject: (String) -> String? = { null },
) {
    fun projectIdOf(conversationId: String): String? =
        (conversations.projectId(conversationId) ?: draftProject(conversationId))
            ?.takeIf { projects.get(it) != null }

    fun resolve(conversationId: String): WorkspaceKey =
        projectIdOf(conversationId)?.let(WorkspaceKey::Project) ?: WorkspaceKey.Conversation(conversationId)

    fun isShared(conversationId: String): Boolean = projectIdOf(conversationId) != null
}
