package com.trail.app.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.trail.app.exchange.ImportUiState

@Composable
fun ImportDialog(state: ImportUiState, select: (Int) -> Unit, waypoints: (Boolean) -> Unit,
    save: () -> Unit, cancel: () -> Unit, duplicate: () -> Unit) {
    if (!state.visible) return
    val draft = state.draft
    AlertDialog(onDismissRequest = cancel, title = { Text("导入 GPX") }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.message.isNotEmpty()) item { Text(state.message) }
            if (draft != null) {
                item { Text(draft.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("GPX ${draft.preview.version} · ${draft.preview.paths.size} 条路线 · ${draft.preview.waypointCount} 个标记") }
                if (draft.duplicateDocumentId != null) item { Text("此原文件已经导入。可打开已有内容，或另存所选内容。")
                    TextButton(onClick = duplicate, enabled = !state.busy) { Text("打开已有") } }
                items(draft.preview.paths, key = { it.index }) { path ->
                    Column(Modifier.fillMaxWidth().toggleable(value = path.index in state.selected, enabled = !state.busy,
                        role = Role.Checkbox, onValueChange = { select(path.index) })) {
                        Row { Checkbox(checked = path.index in state.selected, onCheckedChange = null, enabled = !state.busy)
                            Column { Text(path.name); Text("${if (path.kind == "TRACK") "实测轨迹" else "计划路线"} · ${path.points} 点 / ${path.segments} 段") } }
                        Text("${distanceText(path.distanceM)} 公里 · 历时 ${path.elapsedMs?.let(::durationText) ?: "—"}")
                        path.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (draft.preview.waypointCount > 0) item {
                    Row(Modifier.fillMaxWidth().toggleable(value = state.includeWaypoints, enabled = !state.busy,
                        role = Role.Checkbox, onValueChange = waypoints)) {
                        Checkbox(checked = state.includeWaypoints, onCheckedChange = null, enabled = !state.busy)
                        Text("包含全部 ${draft.preview.waypointCount} 个标记")
                    }
                }
                items(draft.preview.warnings) { Text(it, style = MaterialTheme.typography.bodySmall) }
                item { Text("数据保存在本机；加载参考路线不会修改当前记录。", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }, confirmButton = {
        if (draft != null) TextButton(onClick = save, enabled = !state.busy && (state.selected.isNotEmpty() || state.includeWaypoints && draft.preview.waypointCount > 0)) {
            Text(if (draft.duplicateDocumentId != null) "另存一份" else "保存所选")
        }
    }, dismissButton = { TextButton(onClick = cancel) { Text(if (state.busy) "取消导入" else "关闭") } })
}
