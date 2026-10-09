package com.trail.app.tracks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.trail.app.TrailApplication
import com.trail.app.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class TrailViewModel(application: Application, saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as TrailApplication
    private val dao = app.repository.dao
    val ready = MutableStateFlow(false)
    val error = app.recordingError
    val onlineMap = app.maps.online
    val mapStatus = app.maps.status
    fun setOnlineMap(enabled: Boolean) { app.maps.setOnline(enabled) }
    val selectedPath = saved.getMutableStateFlow<String?>("selectedPath", null)
    val selectedDocument = saved.getMutableStateFlow<String?>("selectedDocument", null)
    val referencePath = saved.getMutableStateFlow<String?>("referencePath", null)
    val referenceDocument = saved.getMutableStateFlow<String?>("referenceDocument", null)
    private fun <T> guarded(source: Flow<T>): Flow<T> = flow { app.ready.await(); emitAll(source) }
        .catch { error.value = "读取轨迹失败，请检查剩余存储空间后重新打开应用。" }
    val active = guarded(dao.observeActive())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val tracks = guarded(dao.observeTracks()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val mapPath = combine(active, selectedPath, selectedDocument) { session, selected, document -> if (document != null) null else selected ?: session?.pathId }
    val points = mapPath.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else guarded(dao.observeMapPoints(id)).onStart { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val detail = selectedPath.flatMapLatest { id -> if (id == null) flowOf(null) else guarded(dao.observePath(id)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val detailSession = selectedPath.flatMapLatest { id -> if (id == null) flowOf(null) else guarded(dao.observeSession(id)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val detailStats = selectedPath.flatMapLatest { id -> if (id == null) flowOf(null) else guarded(dao.observeImportStats(id)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val detailDocument = combine(detail, selectedDocument) { path, document -> document ?: path?.documentId }
        .flatMapLatest { id -> if (id == null) flowOf(null) else guarded(dao.observeDocument(id)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val detailWaypoints = detailDocument.flatMapLatest { document -> if (document == null) flowOf(emptyList()) else guarded(dao.observeWaypoints(document.id)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val waypointDocuments = guarded(dao.observeWaypointDocuments()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val referencePoints = referencePath.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else guarded(dao.observeMapPoints(id)).onStart { emit(emptyList()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val referencePathDetails = referencePath.flatMapLatest { id -> if (id == null) flowOf(null) else guarded(dao.observePath(id)) }
    val referenceName = combine(referencePathDetails, referenceDocument) { path, document -> path?.name ?: if (document != null) "参考标记" else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val referenceWaypoints = combine(referencePathDetails, referenceDocument) { path, document -> document ?: path?.documentId }
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else guarded(dao.observeWaypoints(id)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearSelection() { selectedPath.value = null; selectedDocument.value = null }
    fun selectPath(id: String) { selectedDocument.value = null; selectedPath.value = id }
    fun selectDocument(id: String) { selectedPath.value = null; selectedDocument.value = id }
    fun loadReference() { referencePath.value = selectedPath.value; referenceDocument.value = selectedDocument.value }
    fun clearReference() { referencePath.value = null; referenceDocument.value = null }
    fun clearReferenceIfDeleted(pathId: String?, documentId: String?) {
        if (pathId != null && referencePath.value == pathId || documentId != null && referenceDocument.value == documentId) clearReference()
    }

    init {
        viewModelScope.launch {
            runCatching { app.ready.await() }
                .onSuccess { ready.value = true }
                .onFailure { error.value = "无法打开本地轨迹数据库，请检查剩余存储空间。" }
        }
    }
}
