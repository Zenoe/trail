package com.trail.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import com.trail.app.domain.ActivityType
import com.trail.app.recording.RecordingService
import com.trail.app.tracks.TrailViewModel
import com.trail.app.ui.*
import com.trail.app.exchange.ImportViewModel
import com.trail.app.tracks.TrackActionsViewModel

class MainActivity : ComponentActivity() {
    private val vm by viewModels<TrailViewModel>()
    private val importVm by viewModels<ImportViewModel>()
    private val actions by viewModels<TrackActionsViewModel>()
    private val pickGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(importVm::open) }
    private var pending: Pair<String, ActivityType>? = null
    private val notificationsEnabled = mutableStateOf(true)
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pending; pending = null
        notificationsEnabled.value = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            action?.let { send(it.first, it.second) }
        } else vm.error.value = "需要精确定位才能记录轨迹。可在系统权限中开启；已有轨迹仍可查看。"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receiveFile(intent)
        setContent {
            TrailTheme { TrailScreen(vm, ::command,
                { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }, notificationsEnabled.value,
                importVm, { pickGpx.launch(arrayOf("*/*")) }, actions) }
        }
    }
    public override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveFile(intent) }
    private fun receiveFile(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let(importVm::open)
    }
    override fun onResume() {
        super.onResume()
        notificationsEnabled.value = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }
    private fun command(action: String, activity: ActivityType) {
        // 暂停/保存不依赖定位权限，权限被撤销后仍应能封存已有记录。
        if (action == RecordingService.START || action == RecordingService.RESUME) {
            val permissions = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions += Manifest.permission.ACCESS_FINE_LOCATION
                permissions += Manifest.permission.ACCESS_COARSE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
                permissions += Manifest.permission.POST_NOTIFICATIONS
                getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
            }
            if (permissions.isNotEmpty()) { pending = action to activity; permissionRequest.launch(permissions.toTypedArray()); return }
        }
        send(action, activity)
    }
    private fun send(action: String, activity: ActivityType) {
        try {
            val intent = Intent(this, RecordingService::class.java).setAction(action).putExtra(RecordingService.EXTRA_ACTIVITY, activity.name)
            if (action == RecordingService.START || action == RecordingService.RESUME) startForegroundService(intent)
            else startService(intent)
        } catch (_: Exception) { vm.error.value = "系统暂时不允许启动定位服务，请回到应用后重试。" }
    }
}
