# 阶段 1：构建与验收

## 构建

Android Studio 打开 `trail` 目录。使用 JDK 17 或 21，Android SDK 36；不要使用当前 Studio 自带的 JDK 25 运行 Gradle 8.13。工具链固定为 AGP 8.13.2 / Gradle 8.13 / Kotlin 2.2.21。最低 Android 8，target/compile API 36。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-17.0.20.101-hotspot'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

`local.properties` 设置本机 `sdk.dir`，不提交。若需要代理，给 Gradle 传 `-Dhttps.proxyHost=... -Dhttps.proxyPort=...` 等本机参数，不把个人代理配置写入项目。Gradle Wrapper 从已有工程复用，保留原签名校验配置。

APK：`app/build/outputs/apk/debug/app-debug.apk`。这是开发测试包，不是商店发布包。应用 ID `com.trail.app`，与“拾记”独立。

## 已实现

- 地图/轨迹两入口，活动类型选择、记录状态、公里/有效时长/速度或配速、GPS 未校正高程。
- 精确定位按需请求；通知权限拒绝有提示；系统定位/应用权限设置入口。
- location 前台服务与通知暂停，锁屏后继续请求定位；Room 逐点事务持久化。
- 开始、等待 GPS、记录、暂停、继续、结束确认、保存详情、历史列表。
- 进程死亡/强制停止/设备重启后，重新打开显示中断及最后保存时间；用户继续产生新段。
- MapLibre 本地样式展示 WGS84 真实轨迹，无网可用，无在线底图和远端定位请求。

与阶段 0 的采样参数调整：仍约每 3 秒请求，但距离门槛从 5 米改为 0，避免静止时没有回调而被误判失联。有效计时在首次合格定位后开始，暂停/等待定位不计时；失联宽限上限 30 秒。精度超过 50 米的点保留但不参与距离。

## 自动测试覆盖

- JVM：球面距离（包括跨日期变更线）、无效坐标/精度、单调时间、状态转移守卫。
- 设备 Room：暂停恢复不连跳跃距离；拒绝迟到/乱序定位；低精度保留但不计距离；GPS 失联停止累计；重启时间基准变化；关闭并重开数据库保留轨迹。
- 设备 Compose：开始 → 暂停 → 继续 → 结束确认 → 详情 → 轨迹列表。室内没有 GPS 也可完成，几何数据由独立用例注入验证。

实际运行结果在本文件末尾记录。自动测试不是户外持续定位和耗电实测。

## 真机手动验收清单

1. 首次点开始，拒绝定位：仍可查看历史，出现说明；只允许大概位置时不宣称精确记录。授权精确位置后重试。
2. 户外定位成功后步行；确认距离增长和轨迹线出现。暂停后移动一段再继续，不连接暂停期间的移动。
3. 关闭系统定位，确认提示；重新开启后恢复为新段。
4. 锁屏和切换其他应用 15 分钟；确认通知存在、轨迹更新。再按阶段 4 扩展为 2–4 小时路测。
5. 记录时强制停止应用再打开：显示中断；继续后新段；结束后详情中旧点仍在。设备重启同样测试。
6. 结束确认先取消，再保存；反复快速点击开始/暂停不能产生多条活跃记录。
7. 字体放大、横竖屏切换，底部操作可滚动到达；页面切换不能停止记录。

## 数据说明

没有账号、广告 SDK、全盘存储权限或自动上传。轨迹只在应用私有数据库中；卸载/清除应用数据会丢失轨迹，阶段 3 前不要把该测试版本作为唯一轨迹存档。GPX、离线底图和可靠爬升不属于阶段 1 已实现范围。

## 本次验证结果（2026-10-08）

阶段 1 实现与自动验证已完成；真机/户外验收仍待完成。

| 验证 | 结果 |
| --- | --- |
| assembleDebug / assembleDebugAndroidTest | 通过，生成应用和测试 APK |
| testDebugUnitTest | 4 项通过 |
| lintDebug | 通过：0 errors，15 warnings；主要为依赖/target 版本更新提示、KAPT/KTX 建议与低版本备份配置提示（allowBackup 已关闭） |
| API 37 模拟器 Room 测试 | 5 项通过，含数据库关闭重开、分段、计时、恢复 |
| API 37 模拟器定位集成 | 1 项通过，真实平台 LocationManager 测试提供器 → 前台服务 → Room；暂停恢复不连接跳跃位置，保留 3 点/2 段 |
| API 37 模拟器 Compose 流程 | 1 项通过：开始/暂停/继续/结束/详情/列表 |
| Redmi Note 8 Pro / API 28 | 安装被设备拒绝：INSTALL_FAILED_USER_RESTRICTED / Install canceled by user；0 项用例执行，不视为通过 |
| 户外 GPS、长时间锁屏、耗电 | 未执行 |

模拟器设备测试总计 `OK (7 tests)`。最初使用 Compose 传递的 Espresso 3.5 时不兼容 API 37 的 InputManager，已将测试依赖明确固定为 Espresso 3.7.0 后通过；不影响正式应用运行依赖。

设备测试可用明确序列号执行，避免误选手机：

```powershell
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w com.trail.app.test/androidx.test.runner.AndroidJUnitRunner
```

LocationPipelineTest 仅在模拟器运行，临时设置本应用 mock-location app-op，结束时恢复默认并移除测试提供器。真实手机跳过此项；不改真实 GPS 配置。测试会在测试应用数据库中留下流程测试记录，不清除已有用户数据。

调试 APK 为多 ABI、未压缩优化构建，大小 56,175,351 字节（约 53.6 MiB）。这不是阶段 0 的单 ABI release <35 MiB 目标验收结果；发布优化在后续阶段处理。

界面截图（模拟器默认 UTC 时区，记录名称按设备本地时区生成）：[首页](screenshots/home.png)、[暂停](screenshots/paused.png)、[保存详情](screenshots/detail.png)。三张截图已目视检查；示例流程没有真实户外位置，空地图为预期结果。

本地报告：`app/build/reports/tests/testDebugUnitTest/index.html`、`app/build/reports/lint-results-debug.html`。Gradle connected 测试报告保留手机安装被拒记录；后续 7 项模拟器测试通过直接 instrumentation 命令执行，不能将旧 connected 报告当作模拟器结果。
