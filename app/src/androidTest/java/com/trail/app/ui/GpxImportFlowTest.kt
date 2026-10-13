package com.trail.app.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.trail.app.MainActivity
import org.junit.Rule
import org.junit.Test
import java.io.File

class GpxImportFlowTest {
    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(text: String) { compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun viewIntentPreviewSaveLoadAndDuplicate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "stage2-ui.gpx")
        val fixture = instrumentation.context.assets.open("stage2-sample.gpx").bufferedReader().use { it.readText() }
        file.writeText(fixture.replace("Trail test fixture", "Trail test ${java.util.UUID.randomUUID()}"))
        fun open() { compose.activityRule.scenario.onActivity { activity ->
            activity.onNewIntent(Intent(Intent.ACTION_VIEW, Uri.fromFile(file)))
        } }
        val launchIntent = compose.activity.intent
        try {
        open()
        waitFor("保存所选")
        compose.onNodeWithText("保存所选").performClick()
        waitFor("轨迹详情")
        Thread.sleep(1_000)
        screenshot("detail")
        compose.onNodeWithText("加载到地图").performScrollTo().performClick()
        waitFor("移除参考路线")
        // 自绘画布没有原生地图层；参考路线仍由 UI 状态和截图复核。
        compose.onNodeWithContentDescription("无底图轨迹视图").assertExists()
        screenshot("reference")
        open()
        waitFor("打开已有")
        screenshot("duplicate")
        compose.onNodeWithText("打开已有").performClick()
        waitFor("轨迹详情")
        compose.onNodeWithText("GPX 实测轨迹").assertExists()
        } finally {
            // ActivityScenario 用最初 Intent 匹配生命周期；接收文件会替换 Intent，结束前还原测试标识。
            compose.activityRule.scenario.onActivity { it.intent = launchIntent }
            file.delete()
        }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null), "stage2-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
