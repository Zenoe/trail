package com.trail.app.recording

import android.Manifest
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.trail.app.MainActivity
import com.trail.app.TrailApplication
import com.trail.app.domain.RecordingState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** 只在模拟器运行；不替换真实手机 GPS。验证完整平台回调 → Service → Room 链路。 */
class LocationPipelineTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))

    @Suppress("DEPRECATION")
    @Test fun emulatorLocationsPersistAcrossPauseAndResume() = runBlocking {
        Assume.assumeTrue("Mock GPS is restricted to the emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val app = ApplicationProvider.getApplicationContext<TrailApplication>()
        val manager = app.getSystemService(LocationManager::class.java)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("appops set ${app.packageName} android:mock_location allow")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            manager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, 3, 1)
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            app.ready.await()
            assertNull("Previous test must finish its session", app.repository.dao.active())
            scenario.onActivity { it.startForegroundService(Intent(it, RecordingService::class.java).setAction(RecordingService.START)) }
            await { app.repository.dao.active() != null }
            fun fix(lat: Double) {
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = lat; longitude = 103.8; accuracy = 5f; altitude = 35.0
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
            }
            delay(500) // Service 请求更新在创建会话后才执行。
            fix(1.30)
            await { app.repository.dao.active()?.state == RecordingState.RECORDING }
            delay(3_200)
            fix(1.3001)
            await { (app.repository.dao.active()?.distanceM ?: 0.0) > 5 }
            app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.PAUSE))
            await { app.repository.dao.active()?.state == RecordingState.PAUSED }
            val before = app.repository.dao.active()!!
            scenario.onActivity { it.startForegroundService(Intent(it, RecordingService::class.java).setAction(RecordingService.RESUME)) }
            await { app.repository.dao.active()?.state == RecordingState.WAITING }
            delay(500)
            fix(1.32)
            await { app.repository.dao.active()?.state == RecordingState.RECORDING }
            assertEquals(before.distanceM, app.repository.dao.active()!!.distanceM, 0.01)
            assertEquals(2, app.repository.dao.segmentCount(before.pathId))
            app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.FINISH))
            await { app.repository.dao.active() == null }
            val points = app.repository.dao.observePoints(before.pathId).first()
            assertEquals(3, points.size)
            assertEquals(35.0, points.first().elevationM!!, 0.01)
        } finally {
            app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.FINISH))
            await { app.repository.dao.active() == null }
            manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            shell("appops set ${app.packageName} android:mock_location default")
            scenario.close()
        }
    }
    private suspend fun await(predicate: suspend () -> Boolean) = withTimeout(10_000) {
        while (!predicate()) delay(100)
    }
}
