package com.trail.app.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import com.trail.app.MainActivity
import org.junit.Rule
import org.junit.Test

/** 真机 UI 流程不依赖能否在室内获得 GPS；定位几何和计时由独立数据库用例覆盖。 */
class RecordingFlowTest {
    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun startPauseResumeFinishAndBrowse() {
        waitFor("开始记录")
        screenshot("home")
        compose.onNodeWithText("开始记录").performScrollTo().performClick()
        waitFor("暂停记录")
        compose.onNodeWithText("暂停记录").performScrollTo().performClick()
        waitFor("继续记录")
        screenshot("paused")
        compose.onNodeWithText("继续记录").performScrollTo().performClick()
        waitFor("暂停记录")
        compose.onNodeWithText("结束并保存").performScrollTo().performClick()
        compose.onNodeWithText("保存轨迹").performClick()
        waitFor("轨迹详情")
        screenshot("detail")
        compose.onNodeWithText("轨迹").performClick()
        waitFor("我的轨迹")
        waitFor("已保存 · 本机")
        compose.onAllNodesWithText("已保存 · 本机").onFirst().assertExists()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File(instrumentation.targetContext.getExternalFilesDir(null), "stage1-$name.png")
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
