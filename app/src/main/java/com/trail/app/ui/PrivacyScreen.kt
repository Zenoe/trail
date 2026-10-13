package com.trail.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val AMAP_POLICY = "https://lbs.amap.com/pages/privacy/"

@Composable
fun PrivacyNoticeDialog(onChoice: (Boolean) -> Unit) {
    AlertDialog(onDismissRequest = { onChoice(false) }, title = { Text("当前位置与数据说明") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("允许定位后，地图会显示当前位置和手机指向；只有点击“开始记录”才会保存轨迹点。暂不允许也能查看和导入本地轨迹。")
            Text("轨迹保存在本机。选择在线地图时，地图服务需要接收底图请求；高德地图另需单独同意。你可在设置中查看完整说明。")
        } },
        confirmButton = { TextButton(onClick = { onChoice(true) }) { Text("了解并启用定位") } },
        dismissButton = { TextButton(onClick = { onChoice(false) }) { Text("暂不定位") } })
}

@Composable
fun PrivacyScreen(amapConsent: Boolean, onRequestLocation: () -> Unit,
    onOpenPermissions: () -> Unit, onRevokeAmap: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("隐私与数据", style = MaterialTheme.typography.titleLarge)
        Text("位置权限用于显示当前位置；开始记录后才将轨迹点、时间、海拔等写入本机数据库。记录时以定位前台服务持续工作，可能增加耗电。")
        Text("轨迹、导入的 GPX 原文件和设置保存在本机应用私有空间；不需要账号，也没有广告或统计 SDK。卸载应用或清除数据会删除本地数据，请自行导出 GPX 备份。")
        Text("空白地图不请求底图。选择 OpenStreetMap 或高德时需要联网，地图提供方可能收到底图请求及网络地址。高德仅在你单独同意并选择后启用；撤销后切回空白地图。")
        Text("导出或分享 GPX 时，你选择的目标应用或位置会收到轨迹、坐标和时间；分享前请确认接收方。应用不自动上传轨迹。")
        OutlinedButton(onClick = onRequestLocation, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("启用当前位置") }
        OutlinedButton(onClick = onOpenPermissions, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("管理系统权限") }
        OutlinedButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AMAP_POLICY)))
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看高德隐私政策") }
        if (amapConsent) OutlinedButton(onClick = onRevokeAmap,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("撤销高德地图同意") }
    }
}
