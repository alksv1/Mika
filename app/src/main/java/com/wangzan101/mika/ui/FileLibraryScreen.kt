package com.wangzan101.mika.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.FileLibrary
import com.wangzan101.mika.chat.FileLibrarySnapshot
import com.wangzan101.mika.chat.LibraryFile
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private data class LibraryRow(
    val key: String,
    val name: String,
    val size: String,
    val location: String,
    val at: Long?,
    val conversations: List<String>,
    val input: Boolean,
    val handle: LibraryFileHandle,
)

private data class LibraryLoaded(val rows: List<LibraryRow>, val titles: Map<String, String>, val partial: Boolean, val failed: Boolean = false)

private data class LibraryBatchResult(val outcome: LibraryBatchOutcome, val targetId: String)

internal data class LibraryLoadResult(val snapshot: FileLibrarySnapshot?, val titles: Map<String, String>, val partial: Boolean)

/** 索引和会话标题分别读取，一边出错不能阻止另一边显示。取消仍交给协程处理。 */
internal fun loadLibraryData(
    snapshot: () -> FileLibrarySnapshot,
    titles: () -> Map<String, String>,
    draftTitles: Map<String, String>,
    fallbackTitle: (String) -> String?,
): LibraryLoadResult {
    var partial = false
    fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        partial = true
        null
    }
    val files = attempt(snapshot)
    val names = attempt(titles)?.toMutableMap() ?: draftTitles.toMutableMap()
    draftTitles.forEach { (id, title) -> names.putIfAbsent(id, title) }
    files?.files?.forEach { file ->
        val ids = when (file) {
            is LibraryFile.Input -> file.sources.map { it.source.conversationId }
            is LibraryFile.Output -> file.sources.map { it.source.conversationId }
        }
        ids.distinct().forEach { id ->
            if (id !in names) names[id] = attempt { fallbackTitle(id) } ?: id
        }
    }
    return LibraryLoadResult(files, names, partial || files?.hasReadFailures == true)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileLibraryScreen(
    vm: ChatViewModel,
    initialConversation: String?,
    fromDrawer: Boolean,
    onBack: () -> Unit,
    onAdded: (String) -> Boolean,
    conversationStore: ConversationStore? = null,
    addBatch: suspend (Context, ChatViewModel, String, List<LibraryFileHandle>, (Int, Int) -> Unit) -> LibraryBatchOutcome =
        { context, viewModel, target, sources, progress -> LibraryFileActions.addAllToDraft(context, viewModel, target, sources, progress) },
    shareBatch: suspend (Context, List<LibraryFileHandle>, (Int, Int) -> Unit) -> LibraryShareOutcome =
        { context, sources, progress -> LibraryFileActions.shareAll(context, sources, progress) },
) {
    val context = LocalContext.current
    val store = remember(conversationStore) { conversationStore ?: ConversationStore.shared(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(store) { store.changes.collect { revision++ } }
    val drafts by vm.draftConversations.collectAsState()
    val loaded by produceState<LibraryLoaded?>(null, revision, drafts) {
        value = try { withContext(Dispatchers.IO) {
            val result = loadLibraryData(
                snapshot = { FileLibrary(store).snapshot() },
                titles = { vm.withDrafts(store.list()).associate { it.id to ChatCopy.plainTitle(it.title) } },
                draftTitles = drafts.associate { it.id to ChatCopy.plainTitle(it.title) },
                fallbackTitle = { id -> store.title(id)?.let(ChatCopy::plainTitle) },
            )
            val rows = result.snapshot?.files.orEmpty().mapNotNull { file ->
                when (file) {
                    is LibraryFile.Input -> file.sources.maxByOrNull { it.source.atMs ?: Long.MIN_VALUE }?.let { latest ->
                        LibraryRow(
                            LibrarySelection.inputKey(latest.source.conversationId, file.path),
                            file.name, latest.sizeLabel, file.path, file.latestAtMs,
                            file.sources.map { it.source.conversationId }.distinct(), true,
                            LibraryFileHandle.Workspace(latest.source.conversationId, file.path),
                        )
                    }
                    is LibraryFile.Output -> file.sources.maxByOrNull { it.source.atMs ?: Long.MIN_VALUE }?.let { latest ->
                        LibraryRow(
                            LibrarySelection.outputKey(latest.file, latest.source),
                            latest.file.name, ChatViewModel.sizeLabel(latest.file.size), latest.file.savedAs, file.latestAtMs,
                            file.sources.map { it.source.conversationId }.distinct(), false, LibraryFileHandle.Exported(latest.file),
                        )
                    }
                }
            }.sortedByDescending { it.at ?: Long.MIN_VALUE }
            LibraryLoaded(rows, result.titles, result.partial, failed = result.snapshot == null)
        } } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LibraryLoaded(emptyList(), emptyMap(), partial = false, failed = true)
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var conversation by rememberSaveable { mutableStateOf(if (fromDrawer) null else initialConversation ?: "") }
    var source by rememberSaveable { mutableIntStateOf(0) }
    var conversationMenu by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<LibraryRow?>(null) }
    var choosingBatch by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var shareWorking by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var error by remember { mutableStateOf<Int?>(null) }
    var savedElsewhere by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedList by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var batchResult by remember { mutableStateOf<LibraryBatchResult?>(null) }
    var shareResult by remember { mutableStateOf<LibraryShareOutcome?>(null) }
    val selected = selectedList.toSet()
    val allRows = loaded?.rows.orEmpty()
    val rows = allRows.filter {
        it.name.contains(query.trim(), ignoreCase = true) &&
            (conversation == null || conversation in it.conversations) &&
            (source == 0 || (source == 1) == it.input)
    }
    val titles = loaded?.titles.orEmpty()
    val rowKeys = allRows.map { it.key }
    LaunchedEffect(rowKeys) {
        if (selectedList.any { it !in rowKeys }) selectedList = selectedList.filter { it in rowKeys }
        if (selecting && rowKeys.isEmpty()) {
            selecting = false
            selectedList = emptyList()
        }
    }
    fun exitSelection() {
        selecting = false
        selectedList = emptyList()
    }
    BackHandler { if (selecting && !working) exitSelection() else onBack() }
    fun add(item: LibraryRow, id: String) {
        scope.launch {
            working = true
            val result = LibraryFileActions.addToDraft(context, vm, id, item.handle)
            working = false
            if (result == LibraryAttachmentResult.Added) savedElsewhere = !onAdded(id) else error = R.string.file_library_error
        }
    }
    fun addSelected(targetId: String) {
        if (working) return
        val items = allRows.filter { it.key in selected }
        if (items.isEmpty()) return
        scope.launch {
            working = true
            shareWorking = false
            progress = 0 to items.size
            val outcome = addBatch(context, vm, targetId, items.map { it.handle }) { done, total -> progress = done to total }
            working = false
            if (outcome.unavailable) {
                error = R.string.file_library_target_unavailable
                return@launch
            }
            if (outcome.failed.isEmpty() && outcome.overLimit == 0 && outcome.accepted > 0) {
                exitSelection()
                if (!onAdded(targetId)) savedElsewhere = true
            } else {
                batchResult = LibraryBatchResult(outcome, targetId)
            }
        }
    }
    fun shareSelected() {
        if (working) return
        val items = allRows.filter { it.key in selected }
        if (items.isEmpty()) return
        scope.launch {
            working = true
            shareWorking = true
            progress = 0 to items.size
            val outcome = shareBatch(context, items.map { it.handle }) { done, total -> progress = done to total }
            working = false
            shareWorking = false
            if (outcome.failed.isNotEmpty()) shareResult = outcome
        }
    }
    fun chooseTarget() {
        if (working) return
        when {
            fromDrawer -> choosingBatch = true
            initialConversation != null -> addSelected(initialConversation)
            else -> addSelected(vm.libraryTargetConversation())
        }
    }
    PageBackground {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            TopBar(
                stringResource(R.string.file_library_title),
                MikaIcons.Back,
                onLeft = { if (selecting && !working) exitSelection() else onBack() },
                leftLabel = stringResource(R.string.back),
                right = {
                    when {
                        working -> Unit
                        selecting -> TextButton(onClick = { exitSelection() }, modifier = Modifier.testTag("file-library-select-cancel")) {
                            Text(stringResource(R.string.cancel), style = MikaType.Label, color = MikaTheme.colors.ink2)
                        }
                        rows.isNotEmpty() -> TextButton(onClick = { selecting = true }, modifier = Modifier.testTag("file-library-select")) {
                            Text(stringResource(R.string.file_library_select), style = MikaType.Label, color = MikaTheme.colors.accent)
                        }
                    }
                },
            )
            Column(Modifier.padding(horizontal = 16.dp)) {
                SearchField(query, { query = it }, stringResource(R.string.file_library_search), Modifier.testTag("file-library-search"))
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        SelectChip(conversation?.let { titles[it] ?: stringResource(R.string.file_library_current) } ?: stringResource(R.string.file_library_all), conversation != null, { conversationMenu = true })
                        DropdownMenu(expanded = conversationMenu, onDismissRequest = { conversationMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.file_library_all)) }, onClick = { conversation = null; conversationMenu = false })
                            titles.forEach { (id, title) -> DropdownMenuItem(text = { Text(title, maxLines = 1) }, onClick = { conversation = id; conversationMenu = false }) }
                            if (initialConversation != null && initialConversation !in titles) DropdownMenuItem(text = { Text(stringResource(R.string.file_library_current)) }, onClick = { conversation = initialConversation; conversationMenu = false })
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        val label = when (source) { 1 -> R.string.file_library_input; 2 -> R.string.file_library_output; else -> R.string.file_library_sources }
                        SelectChip(stringResource(label), source != 0, { sourceMenu = true })
                        DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                            listOf(R.string.file_library_sources, R.string.file_library_input, R.string.file_library_output).forEachIndexed { index, text ->
                                DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = { source = index; sourceMenu = false })
                            }
                        }
                    }
                }
            }
            if (loaded?.partial == true && loaded?.failed == false) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.file_library_partial), Modifier.weight(1f).padding(vertical = 12.dp), style = MikaType.Hint, color = MikaTheme.colors.warn)
                    TextButton(onClick = { revision++ }) { Text(stringResource(R.string.file_library_retry)) }
                }
            }
            when {
                loaded == null -> EmptyNote(stringResource(R.string.file_library_loading))
                loaded?.failed == true -> {
                    Column {
                        EmptyNote(stringResource(R.string.file_library_load_failed))
                        TextButton(onClick = { revision++ }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.file_library_retry)) }
                    }
                }
                rows.isEmpty() -> EmptyNote(stringResource(R.string.file_library_empty))
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("file-library-list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(rows, key = { _, item -> item.key }) { _, item ->
                        val available by produceState<Boolean?>(null, item.handle, revision) {
                            value = null
                            value = try {
                                LibraryFileActions.isAvailable(context, item.handle)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                false
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth()
                                .background(
                                    if (selecting && item.key in selected) MikaTheme.colors.accent.copy(alpha = 0.08f) else MikaTheme.colors.fill,
                                    RoundedCornerShape(14.dp),
                                )
                                .then(
                                    if (selecting) Modifier.toggleable(
                                        value = item.key in selected,
                                        enabled = !working,
                                        role = Role.Checkbox,
                                        onValueChange = { selectedList = LibrarySelection.toggle(selectedList.toSet(), item.key).toList() },
                                    ) else Modifier
                                )
                                .padding(14.dp)
                                .testTag("file-library-row"),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {                            if (selecting) SelectMark(item.key in selected, Modifier.padding(top = 2.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MikaType.Body, color = MikaTheme.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val date = item.at?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: stringResource(R.string.file_library_unknown_time)
                                Text(listOfNotNull(item.size.takeIf { it.isNotBlank() }, date).joinToString(" · "), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                                val location = item.location.takeIf { it.isNotBlank() && it != item.name }
                                    ?: (item.handle as? LibraryFileHandle.Exported)?.file?.uri?.takeIf { uri -> rows.count { it.name == item.name } > 1 && uri.isNotBlank() }
                                if (location != null) Text(location, style = MikaType.Hint, color = MikaTheme.colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(item.conversations.joinToString(" · ") { titles[it] ?: it }, style = MikaType.Hint, color = MikaTheme.colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(if (item.input) R.string.file_library_input else R.string.file_library_output), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                                Text(stringResource(when (available) { true -> R.string.file_library_available; false -> R.string.file_library_missing; null -> R.string.file_library_loading }), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                                if (!selecting) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (available == false) TextButton(onClick = { revision++ }) { Text(stringResource(R.string.file_library_retry)) }
                                        TextButton(enabled = !working && available == true, onClick = { scope.launch { working = true; error = if (!LibraryFileActions.preview(context, item.handle)) R.string.file_library_error else null; working = false } }) { Text(stringResource(R.string.file_library_open)) }
                                        TextButton(enabled = !working && available == true, onClick = { scope.launch { working = true; error = if (!LibraryFileActions.share(context, item.handle)) R.string.file_library_error else null; working = false } }) { Text(stringResource(R.string.file_library_share)) }
                                        TextButton(enabled = !working && available == true, onClick = {
                                            if (fromDrawer) choosing = item
                                            else if (initialConversation != null) add(item, initialConversation)
                                            else if (vm.ui.value.conversationId == null) add(item, vm.libraryTargetConversation())
                                            else error = R.string.file_library_error
                                        }) { Text(stringResource(R.string.file_library_add)) }
                                    }
                                } else if (available == false) {
                                    TextButton(enabled = !working, onClick = { revision++ }) { Text(stringResource(R.string.file_library_retry)) }
                                }
                            }
                        }
                    }
                }
            }
            if (selecting) {
                SelectBar(
                    count = selectedList.size,
                    allSelected = rows.isNotEmpty() && rows.all { it.key in selected },
                    working = working,
                    sharing = shareWorking,
                    done = progress.first,
                    total = progress.second,
                    onSelectAll = { selectedList = LibrarySelection.toggleAll(selectedList.toSet(), rows.map { it.key }).toList() },
                    onShare = { shareSelected() },
                    onAdd = { chooseTarget() },
                )
            }
        }
    }
    choosing?.let { item ->
        AlertDialog(onDismissRequest = { choosing = null }, title = { Text(stringResource(R.string.file_library_choose)) }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (vm.canSwitch()) Text(stringResource(R.string.file_library_new), Modifier.fillMaxWidth().clickable {
                    choosing = null
                    add(item, store.newConversation())
                }.padding(vertical = 12.dp))
                titles.filter { (id, _) -> vm.canSwitch() || id == vm.ui.value.conversationId }.forEach { (id, title) -> Text(title, Modifier.fillMaxWidth().clickable {
                    choosing = null
                    add(item, id)
                }.padding(vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (vm.ui.value.conversationId == null) Text(stringResource(R.string.file_library_current), Modifier.fillMaxWidth().clickable {
                    choosing = null
                    add(item, vm.libraryTargetConversation())
                }.padding(vertical = 12.dp))
            }
        }, confirmButton = { TextButton(onClick = { choosing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (choosingBatch) {
        AlertDialog(onDismissRequest = { choosingBatch = false }, title = { Text(stringResource(R.string.file_library_choose)) }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (vm.canSwitch()) Text(stringResource(R.string.file_library_new), Modifier.fillMaxWidth().clickable {
                    choosingBatch = false
                    addSelected(store.newConversation())
                }.padding(vertical = 12.dp))
                titles.filter { (id, _) -> vm.canSwitch() || id == vm.ui.value.conversationId }.forEach { (id, title) -> Text(title, Modifier.fillMaxWidth().clickable {
                    choosingBatch = false
                    addSelected(id)
                }.padding(vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (initialConversation != null && initialConversation !in titles) Text(stringResource(R.string.file_library_current), Modifier.fillMaxWidth().clickable {
                    choosingBatch = false
                    addSelected(initialConversation)
                }.padding(vertical = 12.dp))
                if (vm.ui.value.conversationId == null) Text(stringResource(R.string.file_library_current), Modifier.fillMaxWidth().clickable {
                    choosingBatch = false
                    addSelected(vm.libraryTargetConversation())
                }.padding(vertical = 12.dp))
            }
        }, confirmButton = { TextButton(onClick = { choosingBatch = false }) { Text(stringResource(R.string.cancel)) } })
    }
    batchResult?.let { result ->
        val outcome = result.outcome
        AlertDialog(
            onDismissRequest = { batchResult = null },
            title = { Text(stringResource(R.string.file_library_batch_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.file_library_batch_summary, outcome.added, outcome.duplicates, outcome.overLimit + outcome.failed.size))
                    if (outcome.failed.isNotEmpty()) {
                        val shown = outcome.failed.take(5).joinToString(stringResource(R.string.list_sep))
                        Text(
                            stringResource(R.string.file_library_batch_failed_names, if (outcome.failed.size > 5) "$shown…" else shown),
                            Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    batchResult = null
                    exitSelection()
                    if (outcome.accepted > 0 && !onAdded(result.targetId)) savedElsewhere = true
                }, modifier = Modifier.testTag("file-library-batch-done")) {
                    Text(stringResource(if (outcome.accepted > 0) R.string.file_library_view_draft else R.string.done))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    batchResult = null
                    selectedList = emptyList()
                    selecting = true
                }, modifier = Modifier.testTag("file-library-batch-continue")) {
                    Text(stringResource(R.string.file_library_keep_selecting))
                }
            },
        )
    }
    shareResult?.let { outcome ->
        AlertDialog(
            onDismissRequest = { shareResult = null },
            title = { Text(stringResource(R.string.file_library_share)) },
            text = {
                Column {
                    Text(stringResource(R.string.file_library_share_summary, outcome.shared, outcome.failed.size))
                    val shown = outcome.failed.take(5).joinToString(stringResource(R.string.list_sep))
                    Text(
                        stringResource(R.string.file_library_share_failed_names, if (outcome.failed.size > 5) "$shown…" else shown),
                        Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { shareResult = null }, modifier = Modifier.testTag("file-library-share-done")) {
                    Text(stringResource(R.string.done))
                }
            },
        )
    }
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, text = { Text(stringResource(message)) }, confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.done)) } })
    }
    if (savedElsewhere) AlertDialog(onDismissRequest = { savedElsewhere = false }, text = { Text(stringResource(R.string.file_library_draft_saved)) }, confirmButton = { TextButton(onClick = { savedElsewhere = false }) { Text(stringResource(R.string.done)) } })
}

/** 多选时每行左边的勾选框:没选是空心圆,选了是实心圆加一个白勾。 */
@Composable
private fun SelectMark(selected: Boolean, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Box(
        modifier.size(20.dp).clip(CircleShape)
            .then(if (selected) Modifier.background(c.accent) else Modifier)
            .border(1.5.dp, if (selected) c.accent else c.edge, CircleShape)
            .testTag("file-library-check"),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(MikaIcons.Check, null, Modifier.size(13.dp), tint = c.onAccent)
    }
}

/** 多选时的底部操作条:已选几个、全选 / 取消全选、加入待发栏;加入时显示进度。 */
@Composable
private fun SelectBar(
    count: Int,
    allSelected: Boolean,
    working: Boolean,
    sharing: Boolean,
    done: Int,
    total: Int,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onAdd: () -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
            .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
            .testTag("file-library-select-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (working) {
            Text(
                stringResource(if (sharing) R.string.file_library_sharing else R.string.file_library_adding, done, total),
                Modifier.weight(1f),
                style = MikaType.Label, color = c.ink2, maxLines = 1, softWrap = false,
            )
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
        } else {
            Text(
                stringResource(R.string.file_library_selected_count, count), Modifier.weight(1f).testTag("file-library-selected-count"),
                style = MikaType.Label, color = c.ink2, maxLines = 1, softWrap = false,
            )
            TextButton(onClick = onSelectAll, modifier = Modifier.testTag("file-library-select-all")) {
                Text(stringResource(if (allSelected) R.string.file_library_select_none else R.string.file_library_select_all), style = MikaType.Label, color = c.accent)
            }
            TextButton(onClick = onShare, enabled = count > 0, modifier = Modifier.testTag("file-library-share-selected")) {
                Text(
                    stringResource(R.string.file_library_share), style = MikaType.Label,
                    color = if (count > 0) c.accent else c.ink3,
                )
            }
            TextButton(onClick = onAdd, enabled = count > 0, modifier = Modifier.testTag("file-library-add-selected")) {
                Text(
                    stringResource(R.string.file_library_add_selected, count), style = MikaType.Label,
                    color = if (count > 0) c.accent else c.ink3,
                )
            }
        }
    }
}
