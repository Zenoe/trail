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
        compose.onNodeWithText("开始记录").performClick()
        waitFor("暂停")
        compose.onNodeWithText("暂停").performClick()
        waitFor("继续")
        screenshot("paused")
        compose.onNodeWithText("继续").performClick()
        waitFor("暂停")
        compose.onNodeWithText("结束").performClick()
        waitFor("保存")
        compose.onNodeWithText("放弃").assertExists()
        compose.onNodeWithText("保存").performClick()
        waitFor("加载到地图")
        screenshot("detail")
        compose.onNodeWithText("轨迹").performClick()
        waitFor("搜索轨迹或标记")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("已保存", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("已保存", substring = true).onFirst().assertExists()
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

    @Test fun endDiscardReturnsToIdle() {
        waitFor("开始记录")
        compose.onNodeWithText("开始记录").performClick()
        waitFor("结束")
        compose.onNodeWithText("结束").performClick()
        waitFor("放弃")
        compose.onNodeWithText("放弃").performClick()
        waitFor("开始记录")
    }
}
