package com.trail.app.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.trail.app.MainActivity
import org.junit.Rule
import org.junit.Test

class MapPickerTest {
    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun choicesSwitchWithoutRecordingAndUnsupportedSdkIsExplained() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("maps", 0)
        val original = prefs.getString("provider", null)
        val originalConsent = prefs.getBoolean("amapConsent", false)
        try {
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("选择地图").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("选择地图").performClick()
            compose.onNode(hasText("OpenStreetMap", substring = true) and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("选择地图").performClick()
            compose.onNode(hasText("空白", substring = true) and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("选择地图").performClick()
            compose.onNode(hasText("高德地图", substring = true) and hasClickAction()).performClick()
            if (Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" }) {
                // 模拟器可能有 ARM 转译能力；首次选择必须先展示隐私告知。
                compose.onNodeWithText("启用高德地图").assertExists()
                compose.onNodeWithText("同意并启用").performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText("高德地图 · 轨迹仍为 GPS 原始坐标").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("选择地图").performClick()
                compose.onNode(hasText("空白", substring = true) and hasClickAction()).performClick()
                compose.onNodeWithText("空白地图 · 轨迹离线可用").assertExists()
            } else compose.onNodeWithText("高德地图不可用").assertExists()
        } finally {
            prefs.edit().apply {
                if (original == null) remove("provider") else putString("provider", original)
                putBoolean("amapConsent", originalConsent)
            }.commit()
        }
    }
}
