package com.trail.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.trail.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File
import java.util.UUID
import android.graphics.Bitmap

class TrackManagementFlowTest {
    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun filterExportOptionsAndDeleteSelectedPath() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalIntent = compose.activity.intent
        val id = UUID.randomUUID().toString().take(8)
        val title = "阶段三测试-$id"
        val file = File(instrumentation.targetContext.cacheDir, "stage3-ui-$id.gpx")
        val fixture = instrumentation.context.assets.open("stage2-sample.gpx").bufferedReader().use { it.readText() }
        file.writeText(fixture.replace("Trail test fixture", "Stage3 $id").replace("林间环线 · 测试", title))
        try {
            compose.activityRule.scenario.onActivity { it.onNewIntent(Intent(Intent.ACTION_VIEW, Uri.fromFile(file))) }
            waitFor("保存所选")
            compose.onNodeWithText("保存所选").performClick()
            waitFor("加载到地图")
            compose.onNodeWithContentDescription("全屏").performClick()
            compose.onNodeWithText("纬度", substring = true).assertExists()
            screenshot("full-map")
            compose.onNodeWithContentDescription("放大").performClick()
            compose.onNodeWithContentDescription("缩小").performClick()
            compose.onNodeWithContentDescription("查看全程").performClick()
            compose.onNodeWithContentDescription("退出全屏").performClick()
            compose.onAllNodesWithText("重命名").assertCountEquals(0)
            waitFor(title)
            compose.onNodeWithText("导出 GPX").performScrollTo().performClick()
            waitFor("导出此文件的全部路线")
            screenshot("export-options")
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("轨迹").performClick()
            waitFor("全部")
            compose.onNode(hasSetTextAction()).performTextInput(title)
            waitFor(title)
            screenshot("filtered")
            assertTrue(compose.onAllNodesWithText("返程路线 · 测试").fetchSemanticsNodes().isEmpty())
            compose.onAllNodesWithText(title).onLast().performClick()
            waitFor("加载到地图")
            compose.onNodeWithText("删除").performScrollTo().performClick()
            compose.onAllNodesWithText("删除").onLast().performClick()
            waitFor("全部")
            // 搜索框仍保留查询词；删除后只应剩输入框，不应再有结果卡片。
            compose.waitUntil(15_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().size == 1 }
            compose.onNode(hasSetTextAction()).performTextClearance()
            waitFor("返程路线 · 测试") // 同一 GPX 的另一条路线没有被级联误删。
            val beforeSwipeDelete = compose.onAllNodesWithText("返程路线 · 测试").fetchSemanticsNodes().size
            compose.onAllNodesWithText("返程路线 · 测试").onLast().performTouchInput { swipeLeft() }
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("删除轨迹").fetchSemanticsNodes().isNotEmpty() }
            screenshot("swipe-delete")
            compose.onAllNodesWithContentDescription("删除轨迹").onLast().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("返程路线 · 测试").fetchSemanticsNodes().size == beforeSwipeDelete - 1 }
        } finally {
            compose.activityRule.scenario.onActivity { it.intent = originalIntent }
            file.delete()
        }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Compose 空闲不代表系统弹窗/输入法的窗口动画结束，截图前稍等一帧完整合成。
        Thread.sleep(800)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null), "stage3-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
