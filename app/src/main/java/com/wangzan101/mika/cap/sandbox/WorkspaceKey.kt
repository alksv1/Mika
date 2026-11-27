package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.chat.ProjectStore

/**
 * 沙箱工作区标识:归属单条对话([Conversation])、整个项目([Project])或一次多步骤工作流运行([Workflow])。
 *
 * 对话与内置固定工作区(`self-check`、`mcp-settings`)沿用原 ID 作为传输标识([wire]),
 * 宿主目录为 `c-<id>`(或 `h-<hash>`);项目工作区使用 `p:<id>`,宿主目录为 `p-<id>`;
 * 工作流运行工作区使用 `w:<runId>`,宿主目录为 `w-<runId>`。
 * 合法对话编号不含 `:`,三者在磁盘目录和序列化键上不会撞名。
 */
sealed interface WorkspaceKey {
    val wire: String

    data class Conversation(val id: String) : WorkspaceKey {
        override val wire: String get() = id
    }

    data class Project(val id: String) : WorkspaceKey {
        init {
            require(ProjectStore.isValidId(id)) { "invalid project workspace id: $id" }
        }

        override val wire: String get() = "$PROJECT_PREFIX$id"
    }

    data class Workflow(val runId: String) : WorkspaceKey {
        init {
            require(isValidWorkflowId(runId)) { "invalid workflow workspace id: $runId" }
        }

        override val wire: String get() = "$WORKFLOW_PREFIX$runId"
    }

    companion object {
        private const val PROJECT_PREFIX = "p:"
        private const val WORKFLOW_PREFIX = "w:"
        private val SAFE_WORKFLOW_ID = Regex("^[A-Za-z0-9_-]{1,100}$")

        fun isValidWorkflowId(id: String): Boolean = SAFE_WORKFLOW_ID.matches(id)

        fun parse(wire: String): WorkspaceKey = when {
            wire.startsWith(PROJECT_PREFIX) -> {
                val id = wire.removePrefix(PROJECT_PREFIX)
                if (ProjectStore.isValidId(id)) Project(id) else Conversation(wire)
            }
            wire.startsWith(WORKFLOW_PREFIX) -> {
                val id = wire.removePrefix(WORKFLOW_PREFIX)
                if (isValidWorkflowId(id)) Workflow(id) else Conversation(wire)
            }
            else -> Conversation(wire)
        }
    }
}
