package com.trail.app

import android.app.Application
import androidx.room.Room
import com.trail.app.data.*
import com.trail.app.exchange.GpxImportRepository
import com.trail.app.exchange.GpxExportRepository
import java.io.File
import com.trail.app.map.MapSource
import com.trail.app.recording.LiveLocationTracker
import com.trail.app.recording.AndroidLocationSource
import com.trail.app.recording.GpsSubscriptionHub
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class TrailApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var repository: RecordingRepository
        private set
    lateinit var ready: Deferred<Unit>
        private set
    lateinit var imports: GpxImportRepository
        private set
    lateinit var exports: GpxExportRepository
        private set
    lateinit var management: TrackManagementRepository
        private set
    lateinit var maps: MapSource
        private set
    lateinit var liveLocation: LiveLocationTracker
        private set
    lateinit var gps: GpsSubscriptionHub
        private set
    val recordingError = MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        org.maplibre.android.MapLibre.getInstance(this)
        maps = MapSource(this)
        gps = GpsSubscriptionHub(AndroidLocationSource(this))
        liveLocation = LiveLocationTracker(this, gps.handle())
        val db = Room.databaseBuilder(this, TrailDatabase::class.java, "trail.db").addMigrations(Migrations.FROM_1_TO_2).build()
        repository = RecordingRepository(db)
        imports = GpxImportRepository(db, File(filesDir, "gpx"))
        exports = GpxExportRepository(db, cacheDir)
        management = TrackManagementRepository(db, File(filesDir, "gpx/originals"))
        // 应用进程只执行一次恢复；Activity 旋转不能把仍在运行的 Service 判为中断。
        ready = applicationScope.async { repository.recoverInterrupted(System.currentTimeMillis()); imports.recover(); exports.cleanupOld() }
    }
}
