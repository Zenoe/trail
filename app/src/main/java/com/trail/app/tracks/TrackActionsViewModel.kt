package com.trail.app.tracks

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trail.app.TrailApplication
import com.trail.app.exchange.PreparedGpx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class TrackActionState(val busy: Boolean = false, val message: String? = null,
    val ready: File? = null, val suggestedName: String = "", val share: Boolean = false)

class TrackActionsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TrailApplication
    val state = MutableStateFlow(TrackActionState())
    private var pending: PreparedGpx? = null
    fun prepare(documentId: String, selectedPathIds: Set<String>?, waypoints: Boolean, share: Boolean) {
        if (state.value.busy) return
        viewModelScope.launch {
            state.value = TrackActionState(busy = true, message = "正在生成并检查 GPX…")
            try {
                app.ready.await()
                pending?.let(app.exports::discard)
                pending = app.exports.prepare(documentId, selectedPathIds, waypoints)
                state.value = TrackActionState(ready = pending?.file, suggestedName = pending!!.suggestedName, share = share)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { state.value = TrackActionState(message = failure.message ?: "生成 GPX 失败") }
        }
    }
    fun prepareOriginal(documentId: String) {
        if (state.value.busy) return
        viewModelScope.launch {
            state.value = TrackActionState(busy = true)
            try {
                app.ready.await()
                val doc = app.repository.dao.exportDocument(documentId) ?: error("文件已不存在")
                val path = doc.originalFilePath ?: error("这条轨迹没有导入原文件")
                val file = File(path)
                require(file.parentFile?.canonicalFile == File(app.filesDir, "gpx/originals").canonicalFile && file.isFile) { "原文件已不可用" }
                pending?.let(app.exports::discard); pending = null
                state.value = TrackActionState(ready = file, suggestedName = doc.name, share = true)
            } catch (failure: Exception) { state.value = TrackActionState(message = failure.message ?: "无法分享原文件") }
        }
    }
    fun launched() { state.value = state.value.copy(ready = null) }
    fun failure(message: String) { state.value = TrackActionState(message = message) }
    fun shareDone() { pending = null; state.value = TrackActionState() }
    fun save(uri: Uri?) {
        val item = pending ?: return
        pending = null
        if (uri == null) { app.exports.discard(item); state.value = TrackActionState(); return }
        viewModelScope.launch {
            state.value = TrackActionState(busy = true, message = "正在保存 GPX…")
            try {
                app.exports.save(item, app.contentResolver, uri)
                state.value = TrackActionState(message = "GPX 已保存")
            } catch (failure: Exception) { state.value = TrackActionState(message = "保存失败：${failure.message ?: "请重试"}") }
        }
    }
    fun renamePath(id: String, name: String) = viewModelScope.launch {
        try { app.ready.await(); app.management.renamePath(id, name); state.value = TrackActionState(message = "已重命名") }
        catch (failure: Exception) { state.value = TrackActionState(message = failure.message ?: "重命名失败") }
    }
    fun renameDocument(id: String, name: String) = viewModelScope.launch {
        try { app.ready.await(); app.management.renameWaypointDocument(id, name); state.value = TrackActionState(message = "已重命名") }
        catch (failure: Exception) { state.value = TrackActionState(message = failure.message ?: "重命名失败") }
    }
    fun deletePath(id: String, done: () -> Unit) = viewModelScope.launch {
        try { app.ready.await(); app.management.deletePath(id); done(); state.value = TrackActionState(message = "轨迹已删除") }
        catch (failure: Exception) { state.value = TrackActionState(message = failure.message ?: "删除失败") }
    }
    fun deleteDocument(id: String, done: () -> Unit) = viewModelScope.launch {
        try { app.ready.await(); app.management.deleteWaypointDocument(id); done(); state.value = TrackActionState(message = "标记文件已删除") }
        catch (failure: Exception) { state.value = TrackActionState(message = failure.message ?: "删除失败") }
    }
    override fun onCleared() { pending?.let(app.exports::discard); super.onCleared() }
}
