package com.wangzan101.mika.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.QueuedFollowUp
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 输入栏上方排着的补充消息(打断后留下的);没任务时可从第一件开始。聊天走前台后不再有「引导」箭头。 */
@Composable
fun FollowUpTray(
    items: List<QueuedFollowUp>,
    active: Boolean,
    onEdit: (String) -> Unit,
    onCancel: (String) -> Unit,
    onStartNow: () -> Unit,
    onActions: (QueuedFollowUp) -> Unit = {},
) {
    val c = MikaTheme.colors
    val reduce = MikaTheme.reduceMotion
    var lastItems by remember { mutableStateOf(items) }
    if (items.isNotEmpty()) lastItems = items
    AnimatedVisibility(
        visible = items.isNotEmpty(),
        enter = expandVertically(MikaMotion.spatialSize(reduce)) + fadeIn(MikaMotion.effects(reduce)),
        exit = shrinkVertically(MikaMotion.spatialSize(reduce)) + fadeOut(MikaMotion.effects(reduce, MikaMotion.EXIT_MS)),
    ) {
        val shown = if (items.isNotEmpty()) items else lastItems
        Column(
            Modifier.fillMaxWidth().testTag("follow-up-tray").heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState()).padding(bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (!active) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr(R.string.follow_up_count, shown.size) + " · " + tr(R.string.follow_up_idle_hint),
                        Modifier.weight(1f), style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        tr(R.string.follow_up_start_now), Modifier.clip(MikaShapes.Pill)
                            .clickable(role = Role.Button, onClick = onStartNow).padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MikaType.Hint, color = c.accent,
                    )
                }
            }
            shown.forEach { item -> PendingRow(item, onActions) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PendingRow(
    item: QueuedFollowUp,
    onActions: (QueuedFollowUp) -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ChatCopy.plainTitle(item.text),
            Modifier.weight(1f, fill = false).testTag("follow-up-bubble")
                .background(c.accent.copy(alpha = 0.14f), MikaShapes.UserBubble).clip(MikaShapes.UserBubble)
                .combinedClickable(
                    onLongClickLabel = stringResource(R.string.drawer_actions),
                    onLongClick = { onActions(item) },
                    onClick = {},
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            style = MikaType.Body, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}
