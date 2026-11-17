package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
internal fun ReportExportMenu(runId: String, report: TaskReport?) {
    val context = LocalContext.current
    val scope = LocalLifecycleOwner.current.lifecycleScope
    var open by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    fun save(uri: Uri?, format: TaskReportExport.Format) {
        if (uri == null || report == null) return
        saving = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No destination")).use {
                        TaskReportExport.write(report, format, it)
                    }
                    true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            }
            saving = false
            Toast.makeText(context, if (ok) R.string.report_saved else R.string.report_save_failed, Toast.LENGTH_LONG).show()
        }
    }
    // 复制和分享都直接用报告正文，省去先落盘再打开复制的步骤。
    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.report_title), text))
        Toast.makeText(context, R.string.report_copied, Toast.LENGTH_SHORT).show()
    }
    // 分享用纯文本：粘到微信、邮件里不会露出 Markdown 标记。
    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.report_title))) }
    }
    val md = rememberLauncherForActivityResult(CreateDocument(TaskReportExport.Format.Markdown.mime)) { save(it, TaskReportExport.Format.Markdown) }
    val word = rememberLauncherForActivityResult(CreateDocument(TaskReportExport.Format.Word.mime)) { save(it, TaskReportExport.Format.Word) }
    val txt = rememberLauncherForActivityResult(CreateDocument(TaskReportExport.Format.Text.mime)) { save(it, TaskReportExport.Format.Text) }
    Box {
        TextButton({ open = true }, enabled = report != null && !saving) { Text(stringResource(R.string.report_export), style = MikaType.Caption) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.report_copy_markdown)) }, onClick = {
                open = false
                report?.let { copy(TaskReportExport.markdown(it)) }
            }, modifier = Modifier.testTag("report-copy-markdown"))
            DropdownMenuItem(text = { Text(stringResource(R.string.report_copy_text)) }, onClick = {
                open = false
                report?.let { copy(TaskReportExport.text(it)) }
            }, modifier = Modifier.testTag("report-copy-text"))
            DropdownMenuItem(text = { Text(stringResource(R.string.report_share)) }, onClick = {
                open = false
                report?.let { share(TaskReportExport.text(it)) }
            }, modifier = Modifier.testTag("report-share"))
            HorizontalDivider()
            listOf("Markdown (.md)", "Word (.docx)", "TXT (.txt)").forEachIndexed { index, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    val format = TaskReportExport.Format.entries[index]
                    val name = "Mika-${runId}.${format.extension}"
                    when (index) { 0 -> md.launch(name); 1 -> word.launch(name); else -> txt.launch(name) }
                })
            }
        }
    }
}
