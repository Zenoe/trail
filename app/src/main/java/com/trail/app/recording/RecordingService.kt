package com.trail.app.recording

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.trail.app.MainActivity
import com.trail.app.R
import com.trail.app.TrailApplication
import com.trail.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** 单一消费者串行处理按钮、通知、定位和心跳，暂停完成后不会写入排队中的点。 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private lateinit var source: LocationSource
    private val app get() = application as TrailApplication
    private val repo get() = app.repository
    private var sampling = false
    private var terminal = false
    private var notificationState: RecordingState? = null
    private var foreground = false

    private data class Command(val action: String, val activity: ActivityType = ActivityType.HIKE,
        val sample: LocationSample? = null, val message: String? = null, val pathId: String? = null)

    override fun onCreate() {
        super.onCreate()
        source = app.gps.handle()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "轨迹记录", NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            try {
                app.ready.await()
                for (command in commands) {
                    if (terminal) break
                    process(command)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // 存储失败不能继续显示“正常记录”。已提交点留在数据库，下次启动可恢复。
                app.recordingError.value = if (failure is SecurityException) "定位权限已撤销，记录已停止。请重新授权后继续。"
                    else "记录已停止：无法保存或访问定位。已保存的数据仍保留，请检查存储后重试。"
                source.stop(); sampling = false
                withContext(Dispatchers.IO) { runCatching { repo.recoverInterrupted(System.currentTimeMillis()) } }
                terminal = true
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        scope.launch {
            while (isActive) { delay(5_000); commands.send(Command(TICK)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // 在任何异步数据库工作之前晋升前台，满足系统的启动时限。
            if (intent?.action == START || intent?.action == RESUME) {
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                else startForeground(NOTIFICATION, notification(null))
                foreground = true
                notificationState = null
            }
            val activity = intent?.getStringExtra(EXTRA_ACTIVITY)?.let { runCatching { ActivityType.valueOf(it) }.getOrNull() } ?: ActivityType.HIKE
            commands.trySend(Command(intent?.action ?: RECOVER, activity, pathId = intent?.getStringExtra(EXTRA_PATH_ID)))
        } catch (failure: Exception) {
            app.recordingError.value = "无法开启定位服务，请允许精确定位并从应用内开始记录。"
            stopSelf()
        }
        // 不在用户未知的后台自动重启定位；下次进入应用明确提示中断，再由用户继续。
        return START_NOT_STICKY
    }

    private suspend fun process(command: Command) {
        val wall = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) {
            when (command.action) {
                START -> repo.start(command.activity, wall, elapsed)
                PAUSE -> repo.pause(wall, elapsed)
                RESUME -> repo.resume(wall, elapsed)
                FINISH -> repo.finish(wall, elapsed)
                DISCARD -> command.pathId?.let { repo.discardPaused(it) }
                END_DISCARD -> command.pathId?.let { repo.discardCurrent(it) }
                FIX -> command.sample?.let { repo.sample(it, wall, elapsed) }
                TICK -> repo.heartbeat(wall, elapsed)
                RECOVER -> repo.recoverInterrupted(wall)
            }
        }
        if (command.action == START || command.action == RESUME || command.action == FIX) app.recordingError.value = null
        if (command.action == UNAVAILABLE) app.recordingError.value = command.message
        val active = withContext(Dispatchers.IO) { repo.dao.active() }
        if (active == null) {
            terminal = true
            source.stop(); sampling = false
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return
        }
        val shouldSample = RecordingRules.canPause(active.state)
        if (shouldSample && !sampling) {
            sampling = true
            source.start({ commands.trySend(Command(FIX, sample = it)) },
                { commands.trySend(Command(UNAVAILABLE, message = it)) })
        } else if (!shouldSample && sampling) { source.stop(); sampling = false }
        if (foreground && notificationState != active.state) {
            notificationState = active.state
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(active.state))
        }
    }

    private fun notification(state: RecordingState?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when (state) {
            RecordingState.RECORDING -> "正在记录 · 点击查看轨迹"
            RecordingState.PAUSED -> "已暂停 · 打开山径继续或保存"
            RecordingState.INTERRUPTED -> "记录中断 · 打开山径处理"
            else -> "等待 GPS 定位 · 可在山径中暂停或结束"
        }
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_recording)
            .setContentTitle("山径 · 轨迹记录").setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).apply {
                if (state == RecordingState.RECORDING || state == RecordingState.WAITING) {
                    val pause = PendingIntent.getService(this@RecordingService, 1,
                        Intent(this@RecordingService, RecordingService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE)
                    addAction(Notification.Action.Builder(null, "暂停", pause).build())
                }
            }.build()
    }

    override fun onDestroy() {
        source.stop()
        scope.cancel(); commands.close()
        if (!terminal) app.applicationScope.launch { runCatching { repo.recoverInterrupted(System.currentTimeMillis()) } }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val START = "com.trail.START"
        const val PAUSE = "com.trail.PAUSE"
        const val RESUME = "com.trail.RESUME"
        const val FINISH = "com.trail.FINISH"
        const val DISCARD = "com.trail.DISCARD"
        const val END_DISCARD = "com.trail.END_DISCARD"
        const val EXTRA_ACTIVITY = "activity"
        const val EXTRA_PATH_ID = "pathId"
        private const val FIX = "FIX"
        private const val TICK = "TICK"
        private const val RECOVER = "RECOVER"
        private const val UNAVAILABLE = "UNAVAILABLE"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION = 10
    }
}
