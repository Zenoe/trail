package com.trail.app.ui

import android.os.SystemClock
import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trail.app.data.*
import com.trail.app.domain.*
import com.trail.app.map.TrailMap
import com.trail.app.recording.RecordingService
import com.trail.app.tracks.TrailViewModel
import com.trail.app.tracks.TrackActionsViewModel
import com.trail.app.exchange.ImportViewModel
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailScreen(vm: TrailViewModel, command: (String, ActivityType) -> Unit,
    openPermissions: () -> Unit, openLocationSettings: () -> Unit, notificationsEnabled: Boolean,
    importVm: ImportViewModel, pickGpx: () -> Unit, actions: TrackActionsViewModel) {
    val context = LocalContext.current
    val actionState by actions.state.collectAsStateWithLifecycle()
    val createGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri -> actions.save(uri) }
    LaunchedEffect(actionState.ready?.absolutePath) {
        val file = actionState.ready ?: return@LaunchedEffect
        actions.launched()
        if (actionState.share) {
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND).setType("application/gpx+xml")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newUri(context.contentResolver, "GPX", uri)
                context.startActivity(Intent.createChooser(send, "分享 GPX"))
                actions.shareDone()
            } catch (failure: Exception) { actions.failure("分享失败：${failure.message ?: "无可用应用"}") }
        } else createGpx.launch(actionState.suggestedName)
    }
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
    val liveLocation by vm.currentLocation.collectAsStateWithLifecycle()
    val liveHeading by vm.currentHeading.collectAsStateWithLifecycle()
    val liveStatus by vm.currentLocationStatus.collectAsStateWithLifecycle()
    val importState by importVm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("map") }
    var activity by rememberSaveable { mutableStateOf(ActivityType.HIKE.name) }
    var finishDialog by rememberSaveable { mutableStateOf(false) }
    var fit by remember { mutableIntStateOf(0) }
    var zoomRequest by remember { mutableIntStateOf(0) }
    var centerLocationRequest by remember { mutableIntStateOf(0) }
    var zoomIn by remember { mutableStateOf(true) }
    var fullMap by rememberSaveable { mutableStateOf(false) }
    var savingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var listFilter by rememberSaveable { mutableStateOf("ALL") }
    var renameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // id、是否为纯标记文件
    var exportDialog by remember { mutableStateOf<String?>(null) }
    var exportWhole by remember { mutableStateOf(false) }
    var exportWaypoints by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // GPS 回调发生在每秒计时器两次刷新之间；不能拿旧的 now 判定新样本来自“未来”，否则每次更新都会隐藏蓝点。
    val locationNow = maxOf(now, SystemClock.elapsedRealtime())
    val currentLocation = liveLocation?.takeIf { it.elapsedMs <= locationNow && locationNow - it.elapsedMs < PreviewLocationRules.MAX_AGE_MS }
    // 用户看到的是手机指向，罗盘优先；无罗盘设备才使用移动航向。
    val orientedLocation = currentLocation?.copy(bearingDeg = liveHeading ?: currentLocation.bearingDeg)
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
    fun showList() { vm.clearSelection(); page = "list" }
    fun deleteFromList(id: String, document: Boolean) {
        if (active?.pathId == id) {
            if (active?.state == RecordingState.PAUSED || active?.state == RecordingState.INTERRUPTED) {
                command(RecordingService.DISCARD, ActivityType.valueOf(activity))
                vm.clearReferenceIfDeleted(id, null)
            } else actions.failure("请先暂停记录，再删除轨迹")
        } else if (document) actions.deleteDocument(id) { vm.clearReferenceIfDeleted(null, id) }
        else actions.deletePath(id) { vm.clearReferenceIfDeleted(id, null) }
    }
    BackHandler(fullMap || page != "map") { if (fullMap) fullMap = false else showMap() }
    Scaffold(topBar = {
        if (!fullMap && !(page == "map" && active != null)) TopAppBar(title = { Text(when (page) { "list" -> "我的轨迹"; "detail" -> "轨迹详情"; "settings" -> "设置"; else -> "山径" }) },
            navigationIcon = { if (page == "detail" || page == "settings") TextButton(onClick = { showMap() }) { Text("返回") } },
            actions = {
                if (page == "list") TextButton(onClick = pickGpx) { Text("导入 GPX") }
                if (page == "map") TextButton(onClick = { page = "settings" }) { Text("设置") }
            })
    }, bottomBar = {
        if (!fullMap && page == "map" && active != null) Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            // 录制中把常驻导航让给核心操作，地图因此可占据剩余全部高度。
            TextButton(onClick = { vm.clearSelection(); page = "list" }, modifier = Modifier.weight(1f)) { Text("轨迹") }
            TextButton(onClick = { command(if (RecordingRules.canPause(active!!.state)) RecordingService.PAUSE else RecordingService.RESUME, ActivityType.valueOf(activity)) },
                modifier = Modifier.weight(1f)) { Text(if (RecordingRules.canPause(active!!.state)) "暂停" else "继续") }
            TextButton(onClick = { finishDialog = true }, modifier = Modifier.weight(1f)) { Text("结束") }
        } else if (!fullMap) Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showMap() }, modifier = Modifier.weight(1f)) { Text("地图", color = if (page == "map") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = { vm.clearSelection(); page = "list" }, modifier = Modifier.weight(1f)) { Text("轨迹", color = if (page == "list" || page == "detail") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }) { padding ->
        if (!ready) { Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text(error ?: "正在打开本地轨迹…") }; return@Scaffold }
        if (page == "map" && active != null && !fullMap) {
            val session = active!!
            val cappedNow = minOf(now, (session.lastFixElapsedMs ?: now) + RecordingRules.SIGNAL_TIMEOUT_MS)
            val duration = session.activeDurationMs + if (session.state == RecordingState.RECORDING) RecordingRules.durationDelta(session.lastElapsedMs, cappedNow) else 0
            Column(Modifier.padding(padding).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(distanceText(session.distanceM), style = MaterialTheme.typography.headlineSmall); Text("距离 · 公里", style = MaterialTheme.typography.labelSmall) }
                    Column(Modifier.weight(1f)) { Text(durationText(duration), style = MaterialTheme.typography.headlineSmall); Text("有效时间 · ${stateLabel(session.state)}", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                }
                Text(mapPositionText(currentLocation), Modifier.fillMaxWidth().background(Color(0xDDF7F8F4))
                    .padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 12.sp, maxLines = 1)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    TrailMap(points, fit, zoomRequest, zoomIn, centerLocationRequest, Modifier.fillMaxSize(),
                        reference = referencePoints, waypoints = referenceWaypoints, online = onlineMap, current = orientedLocation)
                    MapControl("查看全程", "fit", Modifier.align(Alignment.TopEnd).padding(12.dp)) { fit++ }
                    Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MapControl("放大", "plus") { zoomIn = true; zoomRequest++ }
                        MapControl("缩小", "minus") { zoomIn = false; zoomRequest++ }
                    }
                    if (currentLocation != null) MapControl("回到当前位置", "locate",
                        Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 16.dp)) { centerLocationRequest++ }
                    if (onlineMap) Text("© OpenStreetMap contributors", Modifier.align(Alignment.BottomCenter).padding(6.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            return@Scaffold
        }
        if (fullMap && (page == "map" || page == "detail")) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                TrailMap(points, fit, zoomRequest, zoomIn, centerLocationRequest, Modifier.fillMaxSize(),
                    reference = if (page == "map") referencePoints else emptyList(),
                    waypoints = if (page == "map") referenceWaypoints else detailWaypoints, online = onlineMap,
                    current = orientedLocation)
                Text(mapPositionText(currentLocation), Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Color(0xDDF7F8F4)).padding(horizontal = 12.dp, vertical = 5.dp),
                    fontSize = 12.sp, color = Color(0xFF25392F), maxLines = 1)
                MapControl("退出全屏", "close", Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 42.dp)) { fullMap = false }
                MapControl("查看全程", "fit", Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 42.dp)) { fit++ }
                Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapControl("放大", "plus") { zoomIn = true; zoomRequest++ }
                    MapControl("缩小", "minus") { zoomIn = false; zoomRequest++ }
                }
                if (currentLocation != null) MapControl("回到当前位置", "locate",
                    Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 40.dp)) { centerLocationRequest++ }
                if (onlineMap) Text("© OpenStreetMap contributors", modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    style = MaterialTheme.typography.labelSmall)
            }
            return@Scaffold
        }
        when (page) {
            "map", "detail" -> Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                if (error != null && page == "map") {
                    Notice(error!!)
                    Row { TextButton(onClick = openPermissions) { Text("权限设置") }; TextButton(onClick = openLocationSettings) { Text("定位设置") } }
                }
                if (!notificationsEnabled && active != null && page == "map") Notice("通知未开启，后台记录提示可能不可见；可在权限设置中开启。")
                key(if (page == "detail") selectedPath ?: selectedDocument else "live") {
                    Box(Modifier.fillMaxWidth().height(240.dp)) {
                    TrailMap(points, fit, zoomRequest, zoomIn, centerLocationRequest, Modifier.fillMaxSize(),
                        reference = if (page == "map") referencePoints else emptyList(),
                        waypoints = if (page == "map") referenceWaypoints else detailWaypoints, online = onlineMap,
                        current = orientedLocation)
                    if (currentLocation != null) MapControl("回到当前位置", "locate",
                        Modifier.align(Alignment.BottomStart).padding(8.dp)) { centerLocationRequest++ }
                    }
                }
                if (page == "map" && currentLocation == null) Text(liveStatus ?: "正在定位当前位置…",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (onlineMap) "$mapStatus · © OpenStreetMap contributors" else "离线轨迹视图 · 无底图", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { fullMap = true }) { Text("全屏") }
                    TextButton(onClick = { fit++ }) { Text("查看全程") }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val session = if (page == "detail") detailSession else active
                    val state = session?.state
                    val imported = page == "detail" && detailDocument?.source == "IMPORTED"
                    if (page == "detail") {
                        Text(detail?.name ?: detailDocument?.name ?: "正在加载…", style = MaterialTheme.typography.titleLarge)
                        actionState.message?.let { Notice(it) }
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
                        if (state == RecordingState.INTERRUPTED) Notice("上次记录已中断，最后保存：${timeText(session!!.lastCommittedAt)}。继续将建立新分段。")
                    }
                    val distance = if (imported) detailStats?.distanceM else session?.distanceM ?: 0.0
                    Text("${distance?.let(::distanceText) ?: "—"} 公里", fontSize = 28.sp)
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
                            OutlinedButton(onClick = { finishDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("结束") }
                            if (state == RecordingState.PAUSED || state == RecordingState.INTERRUPTED) {
                                TextButton(onClick = { deleteTarget = active!!.pathId to false }) { Text("丢弃暂停记录") }
                            }
                        }
                        OutlinedButton(onClick = pickGpx, modifier = Modifier.fillMaxWidth()) { Text("导入 GPX") }
                    } else {
                        Button(onClick = { vm.loadReference(); showMap(); fit++ }, modifier = Modifier.fillMaxWidth()) { Text("加载到地图") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { exportWhole = false; exportWaypoints = selectedPath == null; exportDialog = "save" }, enabled = !actionState.busy) { Text("导出 GPX") }
                            OutlinedButton(onClick = { exportWhole = false; exportWaypoints = selectedPath == null; exportDialog = "share" }, enabled = !actionState.busy) { Text("分享") }
                        }
                        if (imported && detailDocument?.originalFilePath != null) TextButton(onClick = { detailDocument?.id?.let(actions::prepareOriginal) }) { Text("分享导入原文件") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { renameText = detail?.name ?: detailDocument?.name.orEmpty(); renameDialog = true }) { Text("重命名") }
                            TextButton(onClick = { selectedPath?.let { deleteTarget = it to false } ?: selectedDocument?.let { deleteTarget = it to true } }) { Text("删除") }
                        }
                        Text("绿点：起点 · 红点：终点 · 橙点：标记。", style = MaterialTheme.typography.bodySmall)
                        detailWaypoints.take(20).forEach { Text("◇ ${it.name ?: "未命名标记"} · ${it.latitude}, ${it.longitude}", style = MaterialTheme.typography.bodySmall) }
                        if (detailWaypoints.size > 20) Text("另有 ${detailWaypoints.size - 20} 个标记已显示在地图中")
                        if (session?.activeSlot == 1) Button(onClick = { showMap() }) { Text("返回当前记录") }
                    }
                }
            }
            "list" -> LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索轨迹或标记") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("ALL" to "全部", "RECORDED" to "记录", "IMPORTED" to "导入", "ROUTE" to "路线").forEach { (key, label) ->
                            FilterChip(selected = listFilter == key, onClick = { listFilter = key }, label = { Text(label) })
                        }
                    }
                    actionState.message?.let { Notice(it) }
                }
                if (tracks.isEmpty() && waypointDocuments.isEmpty()) item { Text("第一条轨迹，从这里开始"); Button(onClick = { showMap() }) { Text("去记录") } }
                items(tracks.filter { track ->
                    track.path.name.contains(query.trim(), ignoreCase = true) && when (listFilter) {
                        "RECORDED" -> track.source == "RECORDED"
                        "IMPORTED" -> track.source == "IMPORTED"
                        "ROUTE" -> track.path.kind == "ROUTE"
                        else -> true
                    }
                }, key = { it.path.id }) { track ->
                    SwipeDeleteRow(onDelete = { deleteFromList(track.path.id, false) }) {
                        Card(onClick = { vm.selectPath(track.path.id); page = "detail" }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(track.path.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text("${distanceText(track.distanceM)} 公里 · ${(track.activeDurationMs ?: track.elapsedMs)?.let(::durationText) ?: "—"} · " +
                                    if (track.source == "IMPORTED") "导入 GPX" else if (track.state == RecordingState.FINISHED) "已保存" else "${track.state?.let(::stateLabel) ?: "未结束"}",
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                    }
                }
                items(waypointDocuments.filter { it.document.name.contains(query.trim(), ignoreCase = true) && listFilter in listOf("ALL", "IMPORTED") }, key = { it.document.id }) { item ->
                    SwipeDeleteRow(onDelete = { deleteFromList(item.document.id, true) }) {
                        Card(onClick = { vm.selectDocument(item.document.id); page = "detail" }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(item.document.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text("${item.waypointCount} 个标记 · 导入 GPX", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            "settings" -> Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("记录精度：均衡（约 3 秒更新）")
                Text("单位：公里 / 米")
                Text("存储：本机私有数据库。卸载应用或清除数据会删除轨迹；请定期导出 GPX 备份。")
                Row(verticalAlignment = Alignment.CenterVertically) { Text("OpenStreetMap 在线底图", Modifier.weight(1f)); Switch(checked = onlineMap, onCheckedChange = vm::setOnlineMap) }
                Text("开启后向 OpenStreetMap 请求当前区域瓦片，会暴露所查看的区域。仅在线浏览，不提供离线下载；网络不可达时仍能查看本地轨迹。")
                Text("暂停、失联与中断恢复均建立新分段。有效记录时长不包含暂停和等待定位时间。")
                OutlinedButton(onClick = openPermissions) { Text("系统权限设置") }
                OutlinedButton(onClick = openLocationSettings) { Text("系统定位设置") }
                Text("山径 0.4.1 · 室内位置预览", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    ImportDialog(importState, importVm::select, importVm::waypoints, importVm::save, importVm::cancel, importVm::openDuplicate)
    if (exportDialog != null && detailDocument != null) AlertDialog(onDismissRequest = { exportDialog = null },
        title = { Text(if (exportDialog == "share") "分享 GPX" else "导出 GPX") },
        text = { Column {
            if (selectedPath != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = exportWhole, onCheckedChange = { exportWhole = it }); Text("导出此文件的全部路线")
            }
            if (detailWaypoints.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = exportWaypoints, onCheckedChange = { exportWaypoints = it }); Text("包含全部 ${detailWaypoints.size} 个标记")
            }
            Text("导出为 GPX 1.1，保留路线类型、分段及原始点。", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = {
            val purpose = exportDialog
            val documentId = detailDocument!!.id
            val selection = if (exportWhole || selectedPath == null) null else setOf(selectedPath!!)
            exportDialog = null
            actions.prepare(documentId, selection, exportWaypoints, purpose == "share")
        }) { Text("继续") } },
        dismissButton = { TextButton(onClick = { exportDialog = null }) { Text("取消") } })
    if (renameDialog) AlertDialog(onDismissRequest = { renameDialog = false }, title = { Text("重命名") },
        text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true, label = { Text("名称") }) },
        confirmButton = { TextButton(onClick = {
            selectedPath?.let { actions.renamePath(it, renameText) } ?: selectedDocument?.let { actions.renameDocument(it, renameText) }
            renameDialog = false
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("取消") } })
    if (deleteTarget != null) AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除这条轨迹？") },
        text = { Text(if (active?.pathId == deleteTarget?.first) "这条暂停的记录会被丢弃，无法恢复。" else "会删除本机保存的路线与轨迹点，无法撤销。若文件还有其他路线或标记，将继续保留。") },
        confirmButton = { TextButton(onClick = {
            val target = deleteTarget!!
            if (active?.pathId == target.first && active?.state in listOf(RecordingState.PAUSED, RecordingState.INTERRUPTED)) {
                command(RecordingService.DISCARD, ActivityType.valueOf(activity)); vm.clearReferenceIfDeleted(target.first, null); showList()
            } else if (target.second) actions.deleteDocument(target.first) { vm.clearReferenceIfDeleted(null, target.first); if (page == "detail") showList() }
            else actions.deletePath(target.first) { vm.clearReferenceIfDeleted(target.first, null); if (page == "detail") showList() }
            deleteTarget = null
        }, enabled = active?.pathId != deleteTarget?.first || active?.state in listOf(RecordingState.PAUSED, RecordingState.INTERRUPTED)) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } })
    if (finishDialog) ModalBottomSheet(onDismissRequest = { finishDialog = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            TextButton(onClick = {
                finishDialog = false; savingPath = active?.pathId
                command(RecordingService.FINISH, ActivityType.valueOf(activity))
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("保存") }
            TextButton(onClick = {
                val pathId = active?.pathId
                finishDialog = false; savingPath = null
                command(RecordingService.END_DISCARD, ActivityType.valueOf(activity))
                vm.clearReferenceIfDeleted(pathId, null)
                showMap()
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("放弃", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable private fun Notice(text: String) { Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) } }
@Composable private fun Metric(label: String, value: String) { Column { Text(label, style = MaterialTheme.typography.labelMedium); Text(value, style = MaterialTheme.typography.headlineSmall) } }
private fun mapPositionText(point: LocationSample?): String = if (point == null) "纬度 —   经度 —   海拔 —" else
    (if (SystemClock.elapsedRealtime() - point.elapsedMs > 30_000) "最近位置 · " else if ((point.accuracyM ?: 0f) > 50f) "大致位置 · " else "") +
    "纬度 ${String.format(Locale.US, "%.5f", point.latitude)}°   经度 ${String.format(Locale.US, "%.5f", point.longitude)}°   海拔 ${point.altitudeM?.let { String.format(Locale.US, "%.0f m", it) } ?: "—"}"

@Composable private fun MapControl(label: String, kind: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(52.dp).clip(RoundedCornerShape(11.dp)).background(Color(0x990F1C19))
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(14.dp)) {
            val stroke = 2.8.dp.toPx()
            val mid = size.width / 2
            if (kind == "plus" || kind == "minus") drawLine(Color.White, Offset(2f, mid), Offset(size.width - 2f, mid), stroke)
            when (kind) {
                "plus" -> drawLine(Color.White, Offset(mid, 2f), Offset(mid, size.height - 2f), stroke)
                "fit" -> {
                    drawLine(Color.White, Offset(2f, 2f), Offset(2f, mid - 4f), stroke)
                    drawLine(Color.White, Offset(2f, 2f), Offset(mid - 4f, 2f), stroke)
                    drawLine(Color.White, Offset(size.width - 2f, 2f), Offset(size.width - 2f, mid - 4f), stroke)
                    drawLine(Color.White, Offset(size.width - 2f, 2f), Offset(mid + 4f, 2f), stroke)
                    drawLine(Color.White, Offset(2f, size.height - 2f), Offset(2f, mid + 4f), stroke)
                    drawLine(Color.White, Offset(2f, size.height - 2f), Offset(mid - 4f, size.height - 2f), stroke)
                    drawLine(Color.White, Offset(size.width - 2f, size.height - 2f), Offset(size.width - 2f, mid + 4f), stroke)
                    drawLine(Color.White, Offset(size.width - 2f, size.height - 2f), Offset(mid + 4f, size.height - 2f), stroke)
                }
                "close" -> {
                    drawLine(Color.White, Offset(2f, 2f), Offset(size.width - 2f, size.height - 2f), stroke)
                    drawLine(Color.White, Offset(size.width - 2f, 2f), Offset(2f, size.height - 2f), stroke)
                }
                "locate" -> {
                    drawCircle(Color.White, 8.dp.toPx(), Offset(mid, mid), style = Stroke(width = 2.dp.toPx()))
                    drawCircle(Color.White, 2.dp.toPx(), Offset(mid, mid))
                }
            }
        }
        // 绘制与命中区域分离：透明叠层保留 52dp 触控面积，不让小图标变得难点。
        TextButton(onClick = onClick, modifier = Modifier.fillMaxSize()) { }
    }
}

@Composable private fun SwipeDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val buttonWidth = 76.dp
    val maxPx = with(density) { buttonWidth.toPx() }
    var revealed by remember { mutableStateOf(false) }
    var offsetPx by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxWidth()) {
        if (offsetPx > 0f) Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC9433C)),
            contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(12.dp),
            modifier = Modifier.align(Alignment.CenterEnd).width(buttonWidth).height(64.dp)
                .semantics { contentDescription = "删除轨迹" }) { Text("删除", color = Color.White) }
        Box(Modifier.fillMaxWidth().offset { IntOffset(-offsetPx.roundToInt(), 0) }
            .pointerInput(maxPx) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, drag ->
                        // 仅在横向拖动时接管手势；向右可合上，纵向仍由列表滚动处理。
                        offsetPx = (offsetPx - drag).coerceIn(0f, maxPx)
                        change.consume()
                    },
                    onDragEnd = {
                        revealed = offsetPx > maxPx * 0.38f
                        offsetPx = if (revealed) maxPx else 0f
                        if (revealed) focus.clearFocus() // 搜索后滑卡片时收起输入法，避免遮住删除按钮。
                    },
                    onDragCancel = { offsetPx = if (revealed) maxPx else 0f }
                )
            }) { content() }
    }
}
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
