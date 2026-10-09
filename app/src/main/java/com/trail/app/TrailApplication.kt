package com.trail.app

import android.app.Application
import androidx.room.Room
import com.trail.app.data.*
import com.trail.app.exchange.GpxImportRepository
import java.io.File
import com.trail.app.map.MapSource
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
    lateinit var maps: MapSource
        private set
    val recordingError = MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        org.maplibre.android.MapLibre.getInstance(this)
        maps = MapSource(this)
        val db = Room.databaseBuilder(this, TrailDatabase::class.java, "trail.db").addMigrations(Migrations.FROM_1_TO_2).build()
        repository = RecordingRepository(db)
        imports = GpxImportRepository(db, File(filesDir, "gpx"))
        // 应用进程只执行一次恢复；Activity 旋转不能把仍在运行的 Service 判为中断。
        ready = applicationScope.async { repository.recoverInterrupted(System.currentTimeMillis()); imports.recover() }
    }
}
