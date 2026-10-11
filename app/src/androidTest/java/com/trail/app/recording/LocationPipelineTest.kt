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
    @Test fun foregroundLocationAndBearingExistWithoutRecording(): Unit = runBlocking {
        Assume.assumeTrue("Mock GPS is restricted to the emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val app = ApplicationProvider.getApplicationContext<TrailApplication>()
        val manager = app.getSystemService(LocationManager::class.java)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("appops set ${app.packageName} android:mock_location allow")
        // 先替换模拟器 GPS，再启动 Activity；共享订阅会在首次前台启动时建立。
        manager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, 3, 1)
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            app.ready.await()
            assertNull(app.repository.dao.active())
            fun fix(lat: Double) {
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = lat; longitude = 103.8; accuracy = 5f; altitude = 35.0
                    speed = 2f; bearing = 90f
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
            }
            // 全套 UI 用例后模拟器可能延迟首个回调；重复邻近样本仍须经过两帧稳定判定。
            for (index in 0 until 6) {
                fix(1.30 + index * 0.00001)
                if (app.liveLocation.current.value != null) break
                delay(3_200)
            }
            await("foreground fix") { app.liveLocation.current.value != null }
            assertTrue(app.liveLocation.current.value!!.latitude in 1.30..1.3001)
            assertEquals(90f, app.liveLocation.current.value!!.bearingDeg!!, 0.1f)
            assertNull("Map preview must not create a recording", app.repository.dao.active())
        } finally {
            scenario.close()
            manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            shell("appops set ${app.packageName} android:mock_location default")
        }
    }

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
        manager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, 3, 1)
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            app.ready.await()
            assertNull("Previous test must finish its session", app.repository.dao.active())
            scenario.onActivity { it.startForegroundService(Intent(it, RecordingService::class.java).setAction(RecordingService.START)) }
            await("service start") { app.repository.dao.active() != null }
            fun fix(lat: Double) {
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = lat; longitude = 103.8; accuracy = 5f; altitude = 35.0
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
            }
            delay(500) // Service 请求更新在创建会话后才执行。
            for (index in 0 until 6) {
                fix(1.30 + index * 0.00001)
                if (app.repository.dao.active()?.state == RecordingState.RECORDING) break
                delay(3_200)
            }
            await("first stable pair") { app.repository.dao.active()?.state == RecordingState.RECORDING }
            for (index in 6 until 12) {
                fix(1.30 + index * 0.00001)
                if ((app.repository.dao.active()?.distanceM ?: 0.0) > 5) break
                delay(3_200)
            }
            await("distance") { (app.repository.dao.active()?.distanceM ?: 0.0) > 5 }
            app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.PAUSE))
            await("pause") { app.repository.dao.active()?.state == RecordingState.PAUSED }
            val before = app.repository.dao.active()!!
            scenario.onActivity { it.startForegroundService(Intent(it, RecordingService::class.java).setAction(RecordingService.RESUME)) }
            await("resume") { app.repository.dao.active()?.state == RecordingState.WAITING }
            delay(500)
            for (index in 0 until 6) {
                fix(1.32 + index * 0.00001)
                if (app.repository.dao.active()?.state == RecordingState.RECORDING) break
                delay(3_200)
            }
            await("second stable pair") { app.repository.dao.active()?.state == RecordingState.RECORDING }
            // 异步回调可能在状态可见前多提交一两个新段内样本；只要求不计入跨段的约 2km 跳跃。
            assertTrue(app.repository.dao.active()!!.distanceM - before.distanceM < 20.0)
            assertEquals(2, app.repository.dao.segmentCount(before.pathId))
            app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.FINISH))
            await("finish") { app.repository.dao.active() == null }
            val points = app.repository.dao.observePoints(before.pathId).first()
            assertTrue(points.size >= 5)
            assertEquals(35.0, points.first().elevationM!!, 0.01)
        } finally {
            app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.FINISH))
            await("cleanup") { app.repository.dao.active() == null }
            manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            shell("appops set ${app.packageName} android:mock_location default")
            scenario.close()
        }
    }
    private suspend fun await(label: String, predicate: suspend () -> Boolean) {
        try { withTimeout(10_000) { while (!predicate()) delay(100) } }
        catch (failure: TimeoutCancellationException) { throw AssertionError("Timed out: $label", failure) }
    }
}
