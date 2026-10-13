
# crazyCall
自己用AI做的练手小项目，模拟打电话，给自己一个脱身理由。
=======
# 疯狂来电 (CrazyCall)

自己用的假来电 App：模拟真实的来电与通话界面，用于在不想待的场合给自己一个脱身的理由。

- 包名：`com.fengkuanglaidian.app`
- 版本：0.9.0
- 技术栈：Kotlin + 原生 XML 布局 + AndroidX/Material；**无 Compose**、无第三方 SDK
- 最低支持：Android 8.0

---

## 功能

| 功能 | 说明 |
|---|---|
| 来电全屏页 | 锁屏之上显示、点亮屏幕、无转场动画、屏蔽返回键 |
| 通话中页 | 计时、两行功能键、底部免提/挂断/键盘；布局按真机原生界面实测尺寸对齐 |
| 接听方式 | 滑动接听（iOS ）/ 点击按钮；底部按钮带轻微上下浮动 |
| 触发方式 | 立即来电；延迟 N 秒 |
| 号码解析 | 内置 51.6 万条离线号段库，解析归属地与运营商，**完全离线不联网** |
| 语音包 | 可自己录一段 5–15 秒语音，接通后播放；没录时用系统离线 TTS |
| 铃声与震动 | 可在设置里开关；铃声可自定义 |
| 快捷触发 | 下拉控制中心磁贴，点一下立刻来电 |
| 权限自检 | 应用内检测通知与全屏通知权限，引导跳转系统设置 |

界面视觉（背景色 `#547374`、号码 26sp、功能键 60dp、底部三键等）均按**真机原生拨号器截图逐像素测量**后对齐。

## 构建

工程内置 Gradle wrapper（`distributionUrl` 指向腾讯云镜像）。

```bat
set JAVA_HOME=D:\android-dev\jdk17
set ANDROID_HOME=D:\Android\Sdk
cd /d D:\AndroidProjects\CrazyCall
gradlew.bat assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

### 构建缓存坑

这个工程的 Gradle 构建缓存**损坏过**，症状有两种：

1. 编译显示 `BUILD SUCCESSFUL`，但装上去一启动就崩溃：
   `ClassNotFoundException: Didn't find class "...MainActivity"`
   —— 因为 `compileDebugKotlin` 从缓存恢复了**不含 class 文件的坏结果**，APK 成了空壳
2. 报一堆莫名其妙的 `unresolved reference`

**遇到就删掉 `app\build` 再构建**，或者命令行加 `--no-build-cache`：

```bat
gradlew.bat --no-build-cache clean assembleDebug
```

判断有没有中招：看 `app\build\tmp\kotlin-classes\debug` 下的 `.class` 文件数量，正常应该是 **20 多个**，如果是 **0** 就是中招了。

### IDEA 里构建

`File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM`
选 **`D:\android-dev\jdk17`**

## 安装到手机

```bat
D:\Android.Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

或把 APK 传到手机直接点安装（需允许未知来源）。

## 锁屏弹出的必要设置

代码侧已做：`USE_FULL_SCREEN_INTENT` 权限、带 full-screen intent 的通知、`showWhenLocked` + `turnScreenOn`、
延迟触发时启动前台服务。

**国产 ROM 仍需手动放行**，否则锁屏上不出界面：

1. 通知权限
2. 全屏通知权限
3. 自启动 / 后台启动（小米澎湃、vivo OriginOS、OPPO ColorOS 都有独立开关）
4. 电池 → 省电策略改「无限制」；vivo 还需加入「后台高耗电」白名单
5. 部分 ROM 有独立的「锁屏通知 / 锁屏显示」开关

## 工程结构

```
app/src/main/
├── AndroidManifest.xml
├── assets/
│   ├── phone_index.bin      号段索引（516,217 条）
│   └── phone_strings.bin    字符串池（省/市/运营商）
├── java/com/fengkuanglaidian/app/
│   ├── MainActivity.kt           设置页：号码/皮肤/延迟/铃声/语音包/权限自检
│   ├── IncomingCallActivity.kt   来电全屏页
│   ├── InCallActivity.kt         通话中页
│   ├── CallNotifier.kt           通知渠道 / 震动 / 铃声
│   ├── CallForegroundService.kt  延迟期间驻留前台，到点直接弹界面
│   ├── CallAlarmReceiver.kt      定时广播
│   ├── QuickCallTileService.kt   下拉快捷磁贴
│   ├── CallSettings.kt           配置持久化
│   ├── CallUiTheme.kt            皮肤定义
│   ├── PhoneNumberResolver.kt    离线号段查询
│   └── VoicePack.kt              语音包录制/播放 + TTS 
└── res/
    ├── layout/      来电页、通话页、设置页、功能键、录音对话框
    ├── drawable/    背景、圆底、图标
    └── anim/        按钮浮动动画
```

## 号段数据

来自 `dannyhu926/phone_location`（2026 年 4 月，516,217 条，覆盖 31 省 / 358 市 / 19 类运营商）。

**不联网、不上传号码。** 注意携号转网后运营商可能与号段登记不一致，这是所有号段库共有的限制。

## 已知限制

- 皮肤只做了颜色与交互差异，未逐一还原各厂商原生布局
- 定时用 `AlarmManager`，Android 12+ 未授予 `SCHEDULE_EXACT_ALARM` 时会降级为非精确
- 未做「通话语音包」之外的通话内容模拟