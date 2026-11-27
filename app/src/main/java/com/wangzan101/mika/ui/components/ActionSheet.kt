package com.wangzan101.mika.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 操作面板里一行行尾的小按钮(例如试听):点它只做这件事,不选这一行、不关面板。[description] 给读屏念。 */
class SheetTrailing(
    val icon: ImageVector,
    val description: String,
    val onClick: () -> Unit,
)

/**
 * 操作面板里的一项;[icon] 放在文字左边;[destructive] 为真时文字用出错红。
 * [searchable] 为假的项(「手动输入」「默认」这类)搜索时也一直留着。
 */
class SheetAction(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val trailing: SheetTrailing? = null,
    val searchable: Boolean = true,
    val onClick: () -> Unit,
)

/** 项数到这么多时(且调用方给了 searchHint)顶上出搜索框。 */
const val SHEET_SEARCH_MIN = 12

/** 按 [query] 留下文字里含它的项(不分大小写);不参与搜索的项一直留着。 */
internal fun filterSheetActions(actions: List<SheetAction>, query: String): List<SheetAction> {
    val q = query.trim()
    if (q.isEmpty()) return actions
    return actions.filter { !it.searchable || it.label.contains(q, ignoreCase = true) }
}

/**
 * 点一行后从底部升起的操作面板:顶上一行说明是在对什么操作,下面每项一行。
 * 点任何一项都会先执行它、再关掉面板。项多到一屏放不下时(例如引擎的几百个音色)列表在面板里上下滑,标题不动。
 * 给了 [searchHint] 且项数不少于 [SHEET_SEARCH_MIN] 时,标题下多一个搜索框按文字筛选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String?, actions: List<SheetAction>, searchHint: String? = null, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    val searching = searchHint != null && actions.size >= SHEET_SEARCH_MIN
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(actions, query, searching) { if (searching) filterSheetActions(actions, query) else actions }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = MikaShapes.SheetTop,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            if (!title.isNullOrEmpty()) {
                Text(
                    title, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MikaType.Caption, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (searching) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SearchField(
                        query, { query = it }, searchHint!!,
                        modifier = Modifier.testTag("sheet-search"),
                        onClear = { query = "" }, clearTag = "sheet-search-clear",
                    )
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (searching && shown.none { it.searchable }) {
                    EmptyNote(stringResource(R.string.sheet_no_matches))
                }
                shown.forEachIndexed { i, a ->
                    ListRow(
                        a.icon, a.label, first = i == 0,
                        titleColor = if (a.destructive) c.bad else c.ink,
                        onClick = { a.onClick(); onDismiss() },
                    ) {
                        a.trailing?.let { t ->
                            // IconButton 自带 48dp 触摸区;图标本身不分左右,RTL 下随行一起镜像到左边
                            IconButton(onClick = t.onClick) {
                                Icon(t.icon, t.description, Modifier.size(20.dp), tint = c.accent)
                            }
                        }
                    }
                }
            }
        }
    }
}
