package com.trail.app.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trail.app.data.*
import com.trail.app.domain.*
import com.trail.app.map.TrailMap
import com.trail.app.recording.RecordingService
import com.trail.app.tracks.TrailViewModel
import com.trail.app.exchange.ImportViewModel
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailScreen(vm: TrailViewModel, command: (String, ActivityType) -> Unit,
    openPermissions: () -> Unit, openLocationSettings: () -> Unit, notificationsEnabled: Boolean,
    importVm: ImportViewModel, pickGpx: () -> Unit) {
    val ready by vm.ready.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val points by vm.points.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val selectedPath by vm.selectedPath.collectAsStateWithLifecycle()
    val detailSession by vm.detailSession.collectAsStateWithLifecycle()
    val detailStats by vm.detailStats.collectAsStateWithLifecycle()
    val detailDocument by vm.detailDocument.collectAsStateWithLifecycle()
    val detailWaypoints by vm.detailWaypoints.collectAsStateWithLifecycle()
    val waypointDocuments by vm.waypointDocuments.collectAsStateWithLifecycle()
    val referencePoints by vm.referencePoints.collectAsStateWithLifecycle()
    val referenceWaypoints by vm.referenceWaypoints.collectAsStateWithLifecycle()
    val referenceName by vm.referenceName.collectAsStateWithLifecycle()
    val selectedDocument by vm.selectedDocument.collectAsStateWithLifecycle()
    val onlineMap by vm.onlineMap.collectAsStateWithLifecycle()
    val mapStatus by vm.mapStatus.collectAsStateWithLifecycle()
    val importState by importVm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("map") }
    var activity by rememberSaveable { mutableStateOf(ActivityType.HIKE.name) }
    var finishDialog by rememberSaveable { mutableStateOf(false) }
    var fit by remember { mutableIntStateOf(0) }
    var savingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(page) {
        // 进程重建时只恢复页面名不够；详情 ID 不存在时回到列表，避免永久加载空详情。
        if (page == "detail" && vm.selectedPath.value == null && vm.selectedDocument.value == null) page = "list"
    }
    LaunchedEffect(importState.result) {
        importState.result?.let { result ->
            if (result.firstPathId != null) vm.selectPath(result.firstPathId) else vm.selectDocument(result.documentId)
            page = "detail"; importVm.consumeResult()
        }
    }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(1_000) } }
    LaunchedEffect(active, savingPath) {
        if (active == null && savingPath != null) {
            savingPath?.let(vm::selectPath); page = "detail"; savingPath = null
        }
    }
    fun showMap() { vm.clearSelection(); page = "map" }
    BackHandler(page != "map") { showMap() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(when (page) { "list" -> "我的轨迹"; "detail" -> "轨迹详情"; "settings" -> "设置"; else -> "山径" }) },
            navigationIcon = { if (page == "detail" || page == "settings") TextButton(onClick = { showMap() }) { Text("返回") } },
            actions = {
                if (page == "list") TextButton(onClick = pickGpx) { Text("导入 GPX") }
                if (page == "map") TextButton(onClick = { page = "settings" }) { Text("设置") }
            })
    }, bottomBar = {
        NavigationBar {
            NavigationBarItem(selected = page == "map", onClick = { showMap() }, icon = { Text("⌖") }, label = { Text("地图") })
            NavigationBarItem(selected = page == "list" || page == "detail", onClick = { vm.clearSelection(); page = "list" }, icon = { Text("≋") }, label = { Text("轨迹") })
        }
    }) { padding ->
        if (!ready) { Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text(error ?: "正在打开本地轨迹…") }; return@Scaffold }
        when (page) {
            "map", "detail" -> Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                if (error != null && page == "map") {
                    Notice(error!!)
                    Row { TextButton(onClick = openPermissions) { Text("权限设置") }; TextButton(onClick = openLocationSettings) { Text("定位设置") } }
                }
                if (!notificationsEnabled && active != null && page == "map") Notice("通知未开启，后台记录提示可能不可见；可在权限设置中开启。")
                key(if (page == "detail") selectedPath ?: selectedDocument else "live") {
                    TrailMap(points, fit, Modifier.fillMaxWidth().height(220.dp),
                        reference = if (page == "map") referencePoints else emptyList(),
                        waypoints = if (page == "map") referenceWaypoints else detailWaypoints, online = onlineMap)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (onlineMap) mapStatus else "离线轨迹视图 · 无底图", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { fit++ }) { Text("查看全程") }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val session = if (page == "detail") detailSession else active
                    val state = session?.state
                    val imported = page == "detail" && detailDocument?.source == "IMPORTED"
                    if (page == "detail") {
                        Text(detail?.name ?: detailDocument?.name ?: "正在加载…", style = MaterialTheme.typography.titleLarge)
                        Text("${detailStats?.pointCount ?: points.size} 个轨迹点 · ${detailStats?.segmentCount ?: points.map { it.segmentId }.distinct().size} 个分段 · ${detailWaypoints.size} 个标记", style = MaterialTheme.typography.bodySmall)
                        if (imported) Text(if (detail?.kind == "ROUTE") "GPX 计划路线" else if (detail == null) "GPX 标记文件" else "GPX 实测轨迹", style = MaterialTheme.typography.labelLarge)
                        if (detailStats?.warnings?.isNotEmpty() == true) Notice(detailStats!!.warnings)
                    } else {
                        if (referenceName != null) {
                            Text("参考：$referenceName · 紫色线", color = MaterialTheme.colorScheme.tertiary)
                            TextButton(onClick = vm::clearReference) { Text("移除参考路线") }
                        }
                        Text(stateLabel(session), color = MaterialTheme.colorScheme.primary)
                        if (active == null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                ActivityType.entries.forEach { type -> FilterChip(selected = activity == type.name,
                                    onClick = { activity = type.name }, label = { Text(type.label) }) }
                            }
                        }
                        if (state == RecordingState.INTERRUPTED && session != null) Notice("上次记录已中断，最后保存：${timeText(session.lastCommittedAt)}。继续将建立新分段。")
                    }
                    val distance = if (imported) detailStats?.distanceM else session?.distanceM ?: 0.0
                    Text("${distance?.let(::distanceText) ?: "—"} 公里", fontSize = 36.sp)
                    val displayedDuration = (session?.activeDurationMs ?: 0) +
                        if (page == "map" && state == RecordingState.RECORDING) {
                            val cappedNow = minOf(now, (session?.lastFixElapsedMs ?: now) + RecordingRules.SIGNAL_TIMEOUT_MS)
                            RecordingRules.durationDelta(session?.lastElapsedMs, cappedNow)
                        } else 0
                    val currentActivity = if (page == "detail") detail?.activity else tracks.firstOrNull { it.path.id == active?.pathId }?.path?.activity ?: ActivityType.valueOf(activity)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.weight(1f)) { Metric(if (imported) "总历时（含停留）" else "有效记录时长", if (imported) detailStats?.elapsedMs?.let(::durationText) ?: "—" else durationText(displayedDuration)) }
                        Box(Modifier.weight(1f)) { Metric(if (currentActivity == ActivityType.RUN) "配速 / 分每公里" else "均速 / km/h",
                            if (imported) detailStats?.elapsedMs?.let { speedText(distance ?: 0.0, it) } ?: "—"
                            else if (currentActivity == ActivityType.RUN) paceText(session?.distanceM ?: 0.0, displayedDuration) else speedText(session?.distanceM ?: 0.0, displayedDuration)) }
                    }
                    if (!imported) Text("GPS 高程（未校正）：" + (session?.elevationM?.let { "%.0f 米".format(it) } ?: "—"), style = MaterialTheme.typography.bodySmall)
                    else Text("累计爬升：—（尚未计算）", style = MaterialTheme.typography.bodySmall)
                    if (page == "map") {
                        if (active == null) Button(onClick = { command(RecordingService.START, ActivityType.valueOf(activity)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("开始记录") }
                        else {
                            Button(onClick = {
                                command(if (RecordingRules.canPause(active!!.state)) RecordingService.PAUSE else RecordingService.RESUME, ActivityType.valueOf(activity))
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                Text(if (RecordingRules.canPause(active!!.state)) "暂停记录" else "继续记录")
                            }
                            OutlinedButton(onClick = { finishDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("结束并保存") }
                        }
                        OutlinedButton(onClick = pickGpx, modifier = Modifier.fillMaxWidth()) { Text("导入 GPX") }
                    } else {
                        Button(onClick = { vm.loadReference(); showMap(); fit++ }, modifier = Modifier.fillMaxWidth()) { Text("加载到地图") }
                        Text("绿点：起点 · 红点：终点 · 橙点：标记。GPX 导出将在阶段 3 提供。", style = MaterialTheme.typography.bodySmall)
                        detailWaypoints.take(20).forEach { Text("◇ ${it.name ?: "未命名标记"} · ${it.latitude}, ${it.longitude}", style = MaterialTheme.typography.bodySmall) }
                        if (detailWaypoints.size > 20) Text("另有 ${detailWaypoints.size - 20} 个标记已显示在地图中")
                        if (session?.activeSlot == 1) Button(onClick = { showMap() }) { Text("返回当前记录") }
                    }
                }
            }
            "list" -> LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tracks.isEmpty() && waypointDocuments.isEmpty()) item { Text("第一条轨迹，从这里开始"); Button(onClick = { showMap() }) { Text("去记录") } }
                items(tracks, key = { it.path.id }) { track ->
                    Card(onClick = { vm.selectPath(track.path.id); page = "detail" }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(track.path.name, style = MaterialTheme.typography.titleMedium)
                            Text("${distanceText(track.distanceM)} 公里 · ${(track.activeDurationMs ?: track.elapsedMs)?.let(::durationText) ?: "—"}")
                            Text(if (track.source == "IMPORTED") "已导入 · GPX" else if (track.state == RecordingState.FINISHED) "已保存 · 本机" else "未结束 · ${track.state?.let(::stateLabel) ?: "—"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                items(waypointDocuments, key = { it.document.id }) { item ->
                    Card(onClick = { vm.selectDocument(item.document.id); page = "detail" }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) { Text(item.document.name); Text("${item.waypointCount} 个标记 · 已导入 GPX") }
                    }
                }
            }
            "settings" -> Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("记录精度：均衡（约 3 秒更新）")
                Text("单位：公里 / 米")
                Text("存储：本机私有数据库。卸载应用或清除数据会删除轨迹；GPX 导出尚未实现。")
                Row(verticalAlignment = Alignment.CenterVertically) { Text("OpenStreetMap 在线底图", Modifier.weight(1f)); Switch(checked = onlineMap, onCheckedChange = vm::setOnlineMap) }
                Text("开启后向 OpenStreetMap 请求当前区域瓦片，会暴露所查看的区域。仅在线浏览，不提供离线下载；网络不可达时仍能查看本地轨迹。")
                Text("暂停、失联与中断恢复均建立新分段。有效记录时长不包含暂停和等待定位时间。")
                OutlinedButton(onClick = openPermissions) { Text("系统权限设置") }
                OutlinedButton(onClick = openLocationSettings) { Text("系统定位设置") }
                Text("山径 0.2.0 · 阶段 2", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    ImportDialog(importState, importVm::select, importVm::waypoints, importVm::save, importVm::cancel, importVm::openDuplicate)
    if (finishDialog) AlertDialog(onDismissRequest = { finishDialog = false }, title = { Text("结束并保存轨迹？") },
        text = { Text("已提交的轨迹点会保存在本机。未获取位置也可以保存空记录。") },
        confirmButton = { TextButton(onClick = {
            finishDialog = false; savingPath = active?.pathId
            command(RecordingService.FINISH, ActivityType.valueOf(activity))
        }) { Text("保存轨迹") } }, dismissButton = { TextButton(onClick = { finishDialog = false }) { Text("取消") } })
}

@Composable private fun Notice(text: String) { Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) } }
@Composable private fun Metric(label: String, value: String) { Column { Text(label, style = MaterialTheme.typography.labelMedium); Text(value, style = MaterialTheme.typography.headlineSmall) } }
private fun timeText(time: Long) = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
private fun stateLabel(state: RecordingState) = when (state) {
    RecordingState.WAITING -> "等待定位 / 信号不足"
    RecordingState.RECORDING -> "正在记录"
    RecordingState.PAUSED -> "已暂停"
    RecordingState.INTERRUPTED -> "记录中断"
    RecordingState.FINISHED -> "已保存"
}
private fun stateLabel(session: SessionEntity?): String = if (session == null) "准备出发 · 开始后获取 GPS" else
    stateLabel(session.state) + (session.accuracyM?.let { " · 精度 ±${it.toInt()} 米" } ?: "")
