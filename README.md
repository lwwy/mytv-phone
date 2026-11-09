# 口袋电视（MyTvPhone）

一个**自用、不上架应用商店**的安卓手机直播播放器。参考 [my-tv-0](https://github.com/vrichv/my-tv-0) 的播放内核与解析实现，但整体按「手机优先」重新设计：竖屏选台、横屏播放、全触屏手势、系统文件选择器导入。

> **重要**：本应用**不内置、不分发、不验证任何直播源**。它是一个纯粹的播放器 + 播放列表解析器，频道列表完全由使用者自己导入。示例文件 `samples/` 里的地址均为 `example.com` 保留域名，不可播放，仅用于测试解析功能。

## 功能

| 模块 | 说明 |
|---|---|
| 导入直播源 | ① 本地文件（SAF）② 粘贴文本 ③ 网络订阅 URL ④ 从其他 App「分享 / 用其他应用打开」 |
| 支持格式 | M3U / M3U8、TXT(`#genre#`)、JSON（兼容 my-tv-0 的 `List<TV>`）、Gua64 六十四卦编码（兼容其 `channels.txt`）、无头纯文本 |
| 解析增强 | `tvg-name` / `tvg-id` / `tvg-logo` / `group-title` / `x-tvg-url`；`#EXTVLCOPT` / `#KODIPROP` / `#EXTHTTP` 与 `url|Referer=...` 提取 UA/Referer；UTF-8 ↔ GBK 自动识别；同名频道多源合并且保持顺序 |
| 播放 | AndroidX Media3 (ExoPlayer) 1.4.0，HLS / DASH / RTSP / Progressive；Live 目标延迟 12 秒；失败时自动按解码方式重试 → 自动切换备用源 → 中文错误提示 |
| 手机交互 | 竖屏搜索 / 分组 / 收藏；横屏单击显隐控件、双击暂停、上下滑换台、左半屏调亮度、右半屏调音量；返回键与全面屏手势统一处理 |
| 订阅管理 | 多份订阅并存、切换、重命名、删除、网络订阅一键刷新（失败保留上次结果）、配置备份导出/恢复 |
| 其他 | 不锁死方向、跟随系统语言、沉浸式仅作用于横屏播放页、无需任何存储权限、`usesCleartextTraffic` 支持 http 源 |

## 编译

### 方式 A：本地 Android Studio

- Android Studio Ladybug 或更新版本，JDK 17
- 打开本目录 → 等待 Gradle Sync → `Build > Build APK(s)`
- 产物：`app/build/outputs/apk/debug/app-debug.apk`

命令行等价：

```bash
./gradlew assembleDebug
```

### 方式 B：GitHub Actions（推荐，手机直接下载）

推送到 `main` 或手动触发工作流后，Actions 会自动编译并把 APK 作为构建产物上传；在 Actions 页面点开该次运行，下载 `app-debug-apk` 即可。推送 `v*` 标签时还会自动发布到 Releases。

Debug APK 使用自动生成的 debug 签名，可直接安装。

## 安装

```bash
adb install -r app-debug.apk
```

或把 APK 传到手机上，用文件管理器点击安装（需允许「安装未知应用」）。**不需要 Play 商店**，Manifest 里没有 `leanback` 等电视特性声明。

## 使用

1. 首次打开是空列表 → 点右下角 **+** → 选择导入方式。`samples/example.m3u` 可以先用来验证解析流程。
2. 导入时会先显示**预览**（格式 / 频道数 / 分组数 / 前 5 条），确认后才写入。
3. 竖屏列表点频道 → 自动转横屏全屏播放。
4. 横屏手势：单击显示控件、双击暂停/播放、**全屏任意位置**上下滑换台、左右滑调亮度/音量、返回键先收控件再退回列表。
5. 设置页可切换 / 刷新 / 删除订阅，导出配置备份。

### 支持格式速览

M3U：

```
#EXTM3U x-tvg-url="https://example.com/epg.xml"
#EXTINF:-1 tvg-name="浙江卫视" group-title="卫视频道",浙江卫视
#EXTVLCOPT:http-user-agent=Mozilla/5.0
https://example.com/live/zj.m3u8
```

TXT：

```
卫视频道,#genre#
浙江卫视,https://example.com/live/zj.m3u8,https://example.com/live/zj2.m3u8
```

## 与 my-tv-0 的关系

| 复用 | 来源 |
|---|---|
| Media3 参数、LiveConfiguration、源类型探测与失败轮换 | `models/TVModel.kt`、`PlayerFragment.kt` |
| M3U / TXT / JSON 解析思路、同名频道多源合并 | `MainViewModel.str2List()` |
| 数据模型字段（group / name / logo / uris / headers） | `models/TV.kt` |

| 重新设计 / 修正 | 原因 |
|---|---|
| Jetpack Compose 单 Activity + 竖屏列表 ↔ 横屏播放 | 原项目是 1920×1080 等比缩放 + Leanback 焦点导航的电视架构 |
| SAF `content://` 导入 | 原项目 `parseUri()` 只认 `file://`，Android 10+ 分区存储下基本读不到 |
| 解析阶段提取 UA / Referer | 原项目有 headers 字段但解析器没从 M3U 里读，带校验的源会 403 |
| `OnBackPressedDispatcher`（Compose `BackHandler`） | 原项目只覆写 `onKeyDown(KEYCODE_BACK)`，全面屏手势返回会直接退出 |
| 全屏任意位置上下滑换台 / 启用左右滑 | 原项目 fling 仅限屏幕中间 1/3，`onScroll` 整段被注释 |
| 不写死繁体、不强制全局横屏、不声明 leanback、加 arm64 支持 | 手机适配 |

## 工程结构

```
app/src/main/java/com/mytvphone/app/
├── MainActivity.kt           # 入口 + 分享/打开方式 Intent 处理
├── MainViewModel.kt          # 全局状态（订阅、频道、设置、播放）
├── data/
│   ├── Models.kt             # Channel / Subscription / ParseResult
│   ├── PlaylistParser.kt     # ★ 四种格式解析 + header + 编码识别
│   ├── Prefs.kt              # DataStore 设置
│   └── PlaylistRepository.kt # 订阅持久化 + 网络刷新
├── player/
│   └── PlayerController.kt   # ★ Media3 封装 + 失败自动切源
└── ui/
    ├── Theme.kt / AppNav.kt
    ├── ChannelListScreen.kt  # 竖屏列表
    ├── ImportSheet.kt        # ★ 四种导入入口 + 预览确认
    ├── PlayerScreen.kt       # 横屏播放 + 手势
    └── SettingsScreen.kt     # 设置与订阅管理
```

## 已知限制（P2 可选）

- 未内置 ffmpeg 软解扩展，极少数冷门编码依赖设备硬解
- 未实现 EPG 节目单（解析时会提取 `x-tvg-url` 但目前仅展示）、画中画、投屏
- 未实现订阅定时自动刷新，需手动点「刷新」
- 未做真机兼容性验证；本项目为自用工具，按需自行调整

## 合规说明

仅供个人学习与自用。应用不包含任何频道列表或流地址，不请求、不验证、不分发第三方直播流。导入的播放列表及其内容由使用者自行负责，频道版权归各自电视台 / 内容提供方所有。
