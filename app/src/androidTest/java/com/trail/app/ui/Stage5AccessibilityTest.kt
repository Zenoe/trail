package com.trail.app.ui

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import com.trail.app.MainActivity
import org.junit.Rule
import org.junit.Test

class Stage5AccessibilityTest {
    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mapControlsAreActionsAndPrivacyIsReachable() {
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("选择地图").fetchSemanticsNodes().isNotEmpty() }
        listOf("全屏", "选择地图", "查看全程").forEach { label ->
            compose.onNodeWithContentDescription(label).assertHasClickAction()
        }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("隐私与数据").performScrollTo().performClick()
        // 200% 字体下说明页需要滚动，操作仍应能被语义树定位并滚动到屏幕内。
        compose.onNodeWithText("启用当前位置").performScrollTo().assertHasClickAction()
        compose.onNodeWithText("管理系统权限").performScrollTo().assertHasClickAction()
    }
}
