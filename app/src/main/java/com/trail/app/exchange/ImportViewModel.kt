package com.trail.app.exchange

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trail.app.TrailApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class ImportUiState(val visible: Boolean = false, val busy: Boolean = false, val message: String = "",
    val draft: ImportDraft? = null, val selected: Set<Int> = emptySet(), val includeWaypoints: Boolean = true,
    val result: ImportResult? = null)

class ImportViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TrailApplication
    val state = MutableStateFlow(ImportUiState())
    private var work: Job? = null

    fun open(uri: Uri) {
        if (state.value.busy) return
        work = viewModelScope.launch {
            state.value.draft?.let { app.imports.discard(it) }
            state.value = ImportUiState(visible = true, busy = true, message = "正在读取并检查 GPX…")
            try {
                app.ready.await()
                require(uri.scheme in setOf("content", "file")) { "请从本机文件管理器选择 GPX" }
                val name = withContext(Dispatchers.IO) {
                    runCatching { app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } }.getOrNull() ?: uri.lastPathSegment ?: "导入轨迹.gpx"
                }
                val draft = app.imports.prepare(name, { app.contentResolver.openInputStream(uri) ?: throw GpxException("无法读取该文件，请重新选择") })
                state.value = ImportUiState(true, draft = draft, selected = draft.preview.paths.map { it.index }.toSet())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { state.value = ImportUiState(visible = true, message = failure.message ?: "导入失败，请检查文件与存储空间") }
        }
    }

    fun select(index: Int) {
        if (!state.value.busy) state.value = state.value.copy(selected = if (index in state.value.selected) state.value.selected - index else state.value.selected + index)
    }
    fun waypoints(enabled: Boolean) { if (!state.value.busy) state.value = state.value.copy(includeWaypoints = enabled) }
    fun save() {
        val current = state.value; val draft = current.draft ?: return
        if (current.busy) return
        work = viewModelScope.launch {
            state.value = current.copy(busy = true, message = "正在保存…")
            try {
                app.imports.publish(draft, current.selected, current.includeWaypoints, progress = { count ->
                    state.value = state.value.copy(message = "正在保存 · 已处理 $count 个点")
                }, committed = { result -> state.value = ImportUiState(result = result) })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { state.value = current.copy(message = failure.message ?: "保存失败，已撤销本次未完成导入") }
        }
    }
    fun openDuplicate() {
        val draft = state.value.draft ?: return
        val id = draft.duplicateDocumentId ?: return
        if (state.value.busy) return
        work = viewModelScope.launch {
            app.imports.discard(draft)
            state.value = ImportUiState(result = ImportResult(id, app.repository.dao.firstPath(id)))
        }
    }
    fun cancel() {
        val draft = state.value.draft
        val previous = work
        previous?.cancel()
        // 等待流式任务退出和数据库回滚后再销毁暂存文件；不与输入流抢同一个文件。
        work = viewModelScope.launch {
            previous?.join()
            draft?.let { app.imports.discard(it) }
            state.value = ImportUiState(result = state.value.result)
        }
    }
    fun consumeResult() { state.value = state.value.copy(result = null) }
}
