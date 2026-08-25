# mcef-android-fcl-port

> 将 MCEF & WebDisplays 移植到 Android FoldCraft Launcher (FCL) 的实验性项目。
> Fabric 1.20.1，目标架构 arm64-v8a。
>
> **本项目不保证任何功能可正常运行。** 所有实验性修改放在 `feature/dev-cef-android` 分支。

---

## 目录

- [两条路线](#两条路线)
- [Android 硬限制（无法绕过）](#android-硬限制无法绕过)
- [CEF 初始化入口点分析](#cef-初始化入口点分析)
- [构建步骤](#构建步骤)
- [真机测试步骤](#真机测试步骤)
- [已知崩溃点](#已知崩溃点)
- [项目结构](#项目结构)
- [CI 工作流说明](#ci-工作流说明)
- [相关文档](#相关文档)
- [上游依赖](#上游依赖)
- [许可证](#许可证)

---

## 两条路线

本项目提供两条互斥的技术路线：

### 路线 2：代理截图模组（推荐，可立即落地）

| 项目 | 说明 |
|------|------|
| 原理 | Fabric mod 通过 HTTP 调用 WebView 截图服务，将网页静态画面渲染到 MC 方块纹理 |
| CEF 依赖 | 无。不加载任何 native 库 |
| FCL 修改 | 需要修改 FCL 启动器源码，集成 `WebViewScreenshotService`（见 `fcl-patches/`） |
| 交互能力 | 简易坐标点击（需后端截图服务支持）。不支持滚动、表单输入、JS弹窗 |
| 刷新率 | 默认 120 秒一次截图请求（可在 GUI 中修改） |
| 降级模式 | 截图服务不可用时显示灰色占位纹理，mod 不崩溃 |
| CI 构建 | 已通过。免费 runner 可编译产出 jar |
| 可用性 | 临时可用替代方案 |

### 路线 1：原生 CEF 移植（理论方案，当前不可行）

| 项目 | 说明 |
|------|------|
| 原理 | 编译 Android arm64 版 libcef.so，通过 JNI 在 FCL 的 JVM 中加载 |
| CEF 依赖 | 完整 CEF runtime（libcef.so + libjcef.so + jcef_helper + locales） |
| Activity 依赖 | CEF 初始化需要 Android Activity，FCL 只能提供 Application Context |
| 子进程 | CEF 需要 jcef_helper 子进程，Android 不支持传统子进程模型 |
| CI 构建 | 免费 runner 必定 OOM。需 32+ core / 64GB+ RAM 付费 runner |
| 官方支持 | MCEF README 明确声明 "This mod will not work on Android" |
| JCEF Android | java-cef 项目无 Android 移植，API 不兼容 |
| 可用性 | 技术死胡同，见下方详细分析 |

### 路线取舍

| 维度 | 路线 2（代理截图） | 路线 1（原生 CEF） |
|------|-------------------|-------------------|
| 可立即使用 | 是 | 否 |
| 显示网页内容 | 静态截图 | 完整交互 |
| 点击/输入支持 | 否 | 理论上可以 |
| 视频播放 | 否（逐帧截图太慢） | 是 |
| 需要 FCL 修改 | 是（集成截图服务） | 是（内置 libcef.so） |
| CI 可构建 | 是 | 否（需付费 runner） |
| 系统级限制 | WebView Context 问题 | Activity + 子进程 + W^X 多重限制 |
| 推荐程度 | 优先采用 | 仅作技术调研 |

---

## Android 硬限制（无法绕过）

以下限制是 Android 操作系统级别的安全策略，**不可能通过 Fabric mod 代码绕过**：

### 1. W^X 内存策略 (Android 10+)

SELinux 和动态链接器禁止从应用缓存/文件目录 `dlopen()` native 库。
`libcef.so` 必须内置进 FCL APK 的 `jniLibs/` 目录才能加载。
纯 Fabric mod 无法携带并加载 `.so` 文件。

**影响**：路线 1 无法仅通过 Fabric mod 分发 CEF runtime，必须重新打包 FCL APK。

### 2. Activity 依赖

FCL 启动器在运行 Minecraft 时，Java 线程持有的是 `Application Context`，不是 `Activity`。
CEF 初始化链路中的多个 JNI 调用需要 Android `Activity` 引用（窗口管理、输入事件分发、生命周期回调）。

**影响**：
- 路线 1 的 CEF 初始化会在 `CefApp.getInstance()` 阶段崩溃
- 路线 2 的 WebView 创建也可能因 Application Context 而失败（Android 版本相关）

### 3. 子进程模型

CEF 的渲染器和 GPU 进程通过 `jcef_helper` 可执行文件启动。
Android 应用不能自由 fork 子进程执行二进制文件。

**影响**：路线 1 的 CEF 多进程架构在 Android 上根本无法工作。
单进程模式（`--single-process`）可能绕过此限制，但极不稳定。

### 4. OpenGL 差异

MCEF 使用桌面 OpenGL（`GL_BGRA`、`GL_UNSIGNED_INT_8_8_8_8_REV`）。
Android 仅支持 OpenGL ES，不提供这些格式。

**影响**：路线 1 需要重写 MCEF 的 `CefRenderer.java`，改用 `GL_RGBA` + `GL_UNSIGNED_BYTE`。
路线 2 的 `ProxyRenderer.java` 已经使用 OpenGL ES 兼容格式。

### 5. GitHub Actions 资源限制

Chromium 源码编译需要：
- 磁盘：100GB+（免费 runner 仅 14GB SSD）
- 内存：32GB+（免费 runner 仅 16GB）
- 时间：6+ 小时（超过 Actions 6 小时超时）

**影响**：路线 1 的 CEF 编译在免费 CI 上必定失败。需付费 larger runner 或自建编译环境。

### 6. 官方 CEF 不支持 Android

CEF 官方不提供 Android 预编译包。
必须从 Chromium for Android 源码自行编译，API 与桌面 CEF 不兼容。
java-cef 项目（JCEF）也没有 Android 移植。

---

## CEF 初始化入口点分析

基于对 [CinemaMod/mcef](https://github.com/CinemaMod/mcef) 上游源码的完整分析。

### 初始化调用链

```
Minecraft 启动
  → MCEF.onPreInit()
      → 加载 mcef.cfg 配置
      → 导入 Let's Encrypt SSL 证书
      → ClientProxy.onPreInit()
  → MCEF.onInit()
      → ClientProxy.onInit()
          → RemoteConfig.downloadMissing()     ← 下载 native 资源
          → 修改 ClassLoader.usr_paths          ← 注入 native 库搜索路径
          → System.load("libcef.so")           ← 加载 native 库
          → CefApp.startup()                    ← CEF 全局初始化
          → CefApp.getInstance(settings)        ← 创建 CEF 实例
              → N_PreInitialize()               ← JNI 调用
              → N_Initialize()                  ← JNI 调用（需要窗口系统）
          → cefApp.createClient()               ← 创建 CefClient
  → 每帧渲染 tick
      → cefApp.N_DoMessageLoopWork()           ← CEF 消息循环
      → browser.mcefUpdate()                   ← 上传 BGRA 帧到 OpenGL 纹理
  → 关闭
      → ClientProxy.onShutdown()
      → cefClient.dispose()
      → CefApp.N_Shutdown()
```

### 必须修改的关键文件

| 文件 | 职责 | Android 问题 |
|------|------|-------------|
| `ClientProxy.java` | CEF 初始化中心 | native 库加载路径、子进程路径 |
| `CefApp.java` | CEF 生命周期管理 | `N_Initialize()` 需要窗口系统 |
| `CefBrowserOsr.java` | 离屏渲染浏览器 | OSR 模式可能依赖桌面 GL |
| `CefRenderer.java` | OpenGL 纹理上传 | 使用 `GL_BGRA`，Android 不支持 |
| `RemoteConfig.java` | 下载 native 资源 | 下载 Windows/Linux/macOS 库，无 Android |
| `OS.java` | 平台判断 | 不识别 Android |

### Activity 依赖分析

MCEF 本身不直接引用 Android `Activity`，但 CEF/JCEF 的 JNI 层在以下位置隐式依赖窗口系统：

1. `CefApp.N_Initialize()` — 初始化 CEF 运行时，内部需要操作系统窗口管理器
2. `CefClient.createBrowser()` — 创建浏览器实例，需要窗口句柄或渲染上下文
3. `CefApp.N_DoMessageLoopWork()` — 消息循环处理，依赖平台事件系统
4. `jcef_helper` 子进程 — CEF 多进程架构需要可执行子进程

在 Android 上，这些 JNI 调用会因缺少 `Activity`、窗口句柄、子进程支持而失败。
这是 CEF/JCEF 架构层面的不兼容，**不是简单的 API 包装可以解决的**。

完整分析详见 [docs/cef-init-analysis.md](docs/cef-init-analysis.md)。

---

## 构建步骤

### 路线 2：代理截图模组（推荐）

#### 前置条件
- JDK 17+
- Gradle 8.5+（或使用项目内 `gradlew`）
- 网络连接（下载 Minecraft mappings 和 Fabric API）

#### 构建命令

```bash
cd proxy-web-mod
gradle wrapper --gradle-version 8.8 --distribution-type bin
./gradlew build
# 产出: build/libs/proxy-web-mod-2.0.0.jar
```

#### CI 自动构建

GitHub Actions 工作流 `build-proxy-web-mod.yml` 会在以下情况自动触发：
- push 到 `proxy-web-mod/` 目录
- 手动触发（workflow_dispatch）

构建产物上传为 Artifact（保留 30 天）。

### 路线 1：MCEF 模组构建（仅验证编译）

#### 前置条件
- JDK 21+
- Gradle 8.12+

#### 构建命令

CI 会自动克隆上游 CinemaMod/mcef 并编译。本地构建需手动克隆：

```bash
git clone --depth 1 https://github.com/CinemaMod/mcef.git mcef-android
cd mcef-android
git submodule update --init --recursive --depth 1
gradle wrapper --gradle-version 8.12 --distribution-type bin
./gradlew build
```

**注意**：编译成功不等于可在 Android FCL 上运行。MCEF 上游明确不支持 Android。

### 路线 1：CEF 编译（需付费 runner）

**免费 GitHub Actions runner 无法完成此构建。**

可行方案：
1. 使用 GitHub 付费 larger runner（32+ cores, 64GB+ RAM）
2. 自建 Linux 编译服务器（64GB RAM, 200GB SSD）
3. 使用 Google Cloud Build 或 AWS CodeBuild

手动触发 `build-cef-android.yml` 工作流，选择 runner 类型。
预计编译时间：4-8 小时（32-core runner）。

### FCL APK 构建

前提条件：
1. 已 Fork [FoldCraftLauncher](https://github.com/FCL-Team/FoldCraftLauncher)
2. Fork 仓库有 `feature/dev-cef-android` 分支
3. 已产出 `libcef.so` artifact（来自 CEF 编译工作流）
4. FCL 源码已集成 `WebViewScreenshotService`

手动触发 `build-fcl-apk.yml` 工作流。

---

## 真机测试步骤

### 路线 2 测试

#### 1. 构建 mod jar

通过 CI 或本地构建获取 `proxy-web-mod-2.0.0.jar`。

#### 2. 安装 FCL 启动器

从 [FCL Releases](https://github.com/FCL-Team/FoldCraftLauncher/releases) 安装最新版 FCL。

#### 3. 放置 mod

将 `proxy-web-mod-1.0.0.jar` 放入 FCL 的 mods 目录：
```
/storage/emulated/0/Android/data/com.fcl.launcher/files/games/com.mojang/mods/
```

#### 4. （可选）集成 WebView 截图服务

如果已修改 FCL 源码集成了 `WebViewScreenshotService`：
- FCL 启动时会自动在 localhost:28085 启动截图服务
- mod 会通过 HTTP 获取网页截图

如果未修改 FCL：
- mod 以降级模式运行，显示灰色占位纹理
- 可通过环境变量 `PROXY_WEB_SERVICE_URL` 指向远程截图服务

#### 5. 启动 Minecraft

在 FCL 中启动 Minecraft 1.20.1 with Fabric。
查看日志确认 mod 加载成功：
```
[proxy-web-mod] Proxy Web Mod 初始化中...
[proxy-web-mod] 此模组不依赖 CEF native 库，使用 WebView 截图代理方案
[proxy-web-mod] Proxy API 初始化成功/失败
```

#### 6. 预期结果

- 截图服务可用：方块上显示网页截图内容（按配置间隔刷新）
- 截图服务不可用：方块上显示深灰色占位纹理
- mod 不会导致游戏崩溃

### GUI 操作说明

1. 放置 Web Screen 方块（创造模式物品栏 Building Blocks 分类）
2. 右键方块打开 GUI 设置界面
3. 在 URL 输入框输入网址（如 `https://www.baidu.com`）
4. 调整截图尺寸（宽/高，建议 512x512，安卓 GL4ES 超过 1024 可能崩溃）
5. 设置刷新间隔（秒，最小 10 秒）
6. 点击「刷新截图」按钮立即请求一次截图
7. 点击「清除网址」按钮清空 URL 和纹理
8. 「模拟点击」开关：开启后左键方块面可模拟点击网页
9. 图片预览区域实时显示当前截图状态
10. 点击「保存」按钮保存设置并关闭 GUI

### 模拟点击交互

#### 原理
玩家左键点击 Web Screen 方块正面，模组将 MC 方块面上的 2D 点击坐标换算成网页图片像素坐标，发送给后端截图服务。截图服务使用 Playwright 在指定坐标模拟鼠标点击，然后返回新截图。

#### 操作步骤
1. 右键方块打开 GUI，开启「模拟点击」开关
2. 保存设置并关闭 GUI
3. 对准方块正面，左键点击
4. 游戏内 actionbar 显示点击状态（发送中/成功/失败）
5. 点击成功后方块表面自动更新为新截图

#### 坐标映射
- 方块正面 1x1 单位映射到截图尺寸（如 512x512）
- 根据 FACING 方向（南北东西）自动换算 UV 坐标
- 只处理正面点击，侧面和背面点击被忽略

### 截图服务部署

模组依赖外部 HTTP 截图服务。仓库提供 Python Playwright 实现：

```bash
# 安装依赖
pip install aiohttp playwright
playwright install chromium
playwright install-deps chromium

# 启动服务
python3 tools/screenshot_service.py --port 28085 --host 0.0.0.0 --concurrency 4
```

API 端点：
- `GET /health` — 健康检查
- `GET /screenshot?url=...&width=512&height=512` — 网页截图
- `GET /click?url=...&x=0&y=0&width=512&height=512` — 模拟点击后截图
- `GET /stats` — 服务统计

配置文件 `config/proxy-web-mod.json`：
```json
{
  "serviceUrl": "http://10.0.2.2:28085",
  "refreshInterval": 60,
  "maxTextureSize": 512,
  "requestTimeout": 15
}
```

### 模拟点击局限性（重要）

- 不支持网页滚动（无鼠标滚轮事件）
- 不支持表单输入（无键盘事件）
- 不支持 JS 弹窗处理
- 必须依赖外部 HTTP 截图后端服务，断网无法工作
- 只是图片 + 坐标模拟，不等同 WebDisplays 原生浏览器
- 每次点击有 2-3 秒延迟（HTTP 往返 + Playwright 执行 + 截图）
- 体验类似于每点一次等 3 秒的远程桌面

### 路线 1 测试

路线 1 当前不可行，无法进行真机测试。
需要先解决 CEF Android 编译、Activity 封装、子进程模型等全部障碍。

---

## 已知崩溃点

### 路线 2 已知问题

| 崩溃点 | 原因 | 状态 |
|--------|------|------|
| `WebView` 创建失败 | Application Context 无 Activity，部分 Android 版本拒绝创建 WebView | 已加 try-catch，降级为占位纹理 |
| WebView `draw()` 返回空白 | 后台无 Surface 时渲染管线不输出画面 | 已知限制，无法修复 |
| 截图超时（10秒） | WebView 加载慢或卡死 | 已加超时处理，返回占位纹理 |
| HTTP 连接失败 | 截图服务未启动或端口被占用 | 已加降级模式 |

### 路线 1 已知死胡同

| 死胡同 | 原因 | 可否绕过 |
|--------|------|---------|
| CEF 无 Android 构建 | 官方不提供 Android 预编译包，需从 Chromium 源码编译 | 需付费编译环境 |
| JCEF 无 Android 移植 | java-cef 项目不支持 Android | 需自行移植 JCEF Java 层 |
| Activity 初始化 | CEF JNI 初始化需要窗口系统 | 需实现虚拟 Activity（理论上可行但极其复杂） |
| 子进程模型 | jcef_helper 无法在 Android 上运行 | 可尝试 `--single-process` 模式（极不稳定） |
| W^X 禁止 dlopen | libcef.so 无法从 mod 目录加载 | 必须重新打包 FCL APK |
| OpenGL 格式 | MCEF 使用 GL_BGRA，Android 仅支持 GL_RGBA | 需修改 CefRenderer |
| 消息循环 | CEF 消息循环与 Android Looper 不兼容 | 需自定义桥接层 |

---

## 项目结构

```
mcef-android-fcl-port/
├── README.md                          # 本文件
├── LICENSE                            # LGPL-2.1
├── EXPERIENCE-shturl.md               # 开发踩坑日志（完整记录）
├── .github/workflows/                 # CI 工作流
│   ├── build-proxy-web-mod.yml        # 路线2: 代理截图 mod 构建（推荐）
│   ├── build-cef-android.yml          # 路线1: CEF 编译（已禁用，免费 runner OOM）
│   ├── build-mcef-mod.yml             # 路线1: MCEF 模组构建（上游克隆）
│   └── build-fcl-apk.yml              # 路线1: FCL APK 构建（需 Fork）
├── proxy-web-mod/                     # 路线2: 代理截图 mod 源码
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradle.properties
│   └── src/main/
│       ├── java/com/cinemamod/mcef/proxy/
│       │   ├── ProxyWebMod.java           # mod 入口（注册方块/物品/方块实体）
│       │   ├── ProxyWebModClient.java     # 客户端入口（注册渲染器/右键交互）
│       │   ├── ProxyConfig.java           # JSON 配置管理
│       │   ├── ProxyAPI.java             # HTTP 截图请求（CompletableFuture）
│       │   ├── WebScreenBlock.java        # 自定义方块（水平朝向）
│       │   ├── WebScreenBlockEntity.java  # 方块实体（截图请求/纹理管理）
│       │   ├── WebScreenBlockEntityRenderer.java # 渲染器（OpenGL ES 纹理绘制）
│       │   └── WebScreenGUI.java          # 游戏内 GUI（URL输入/尺寸/图片预览/模拟点击开关）
│       └── resources/
│           ├── fabric.mod.json             # Fabric mod 元数据（依赖 Fabric-API）
│           └── assets/proxy-web-mod/       # 方块模型/语言文件/战利品表
├── fcl-patches/                       # FCL 启动器补丁源码
│   └── src/main/java/com/cinemamod/mcef/proxy/
│       └── WebViewScreenshotService.java  # WebView 截图 HTTP 服务
├── tools/                             # 工具脚本
│   └── screenshot_service.py          # 网页截图 HTTP 服务（Playwright 异步并发版）
├── mcef-android/                      # 路线1: MCEF 源码（CI 自动克隆）
│   └── .gitkeep
├── webdisplays-android/               # WebDisplays 适配（预留）
│   └── .gitkeep
└── docs/                              # 文档
    ├── CEF-PORT-FEASIBILITY.md        # CEF 移植可行性评估
    ├── FCL-TROUBLESHOOTING.md         # FCL 安卓平台故障排查指南
    ├── architecture.html              # 架构方案
    └── cef-init-analysis.md            # CEF 初始化入口点详细分析
```

---

## CI 工作流说明

| 工作流 | 触发方式 | runner | 状态 |
|--------|---------|--------|------|
| `build-proxy-web-mod.yml` | push / 手动 | 免费 | 已通过 |
| `build-mcef-mod.yml` | push / 手动 | 免费 | 已通过（编译验证） |
| `build-cef-android.yml` | 仅手动 | 免费/付费 | 免费 runner 必定 OOM |
| `build-fcl-apk.yml` | 仅手动 | 免费 | 需先 Fork FCL |

## 相关文档

| 文档 | 说明 |
|------|------|
| [CEF-PORT-FEASIBILITY.md](docs/CEF-PORT-FEASIBILITY.md) | CEF 移植可行性评估：7 个不可绕过的技术障碍、编译硬件要求、现实结论 |
| [FCL-TROUBLESHOOTING.md](docs/FCL-TROUBLESHOOTING.md) | FCL 安卓平台故障排查指南：依赖缺失、网络问题、GL4ES 贴图限制、内存溢出、Mixin 异常 |
| [EXPERIENCE-shturl.md](EXPERIENCE-shturl.md) | 开发踩坑日志：GitHub Actions OOM、Activity 依赖、GL4ES 限制、代理 Mod 能力边界 |
| [cef-init-analysis.md](docs/cef-init-analysis.md) | CEF 初始化入口点详细分析：调用链、必须修改的文件 |
| [architecture.html](docs/architecture.html) | 架构方案可视化 |

---

## 上游依赖

- [MCEF](https://github.com/CinemaMod/mcef) (LGPL-2.1) — Java 模组 + JNI 绑定
- [WebDisplays](https://github.com/CinemaMod/webdisplays) — Minecraft 浏览器屏幕方块
- [FoldCraftLauncher](https://github.com/FCL-Team/FoldCraftLauncher) — Android Minecraft Java 启动器
- [Chromium](https://www.chromium.org/) / [CEF](https://bitbucket.org/chromiumembedded/cef/) — 底层浏览器引擎

---

## 许可证

LGPL-2.1-or-later（沿用上游 MCEF 许可证）

## 开源规则

1. 遵守 MCEF 的 LGPL-2.1 许可证；修改后的源码必须公开
2. 不直接修改各上游仓库 main 主分支；所有修改放在独立 Fork 的 feature 分支
3. 不使用预编译的 libcef.so；Android-CEF 必须由 CI 从源码编译
4. 禁止自动发布 Release；仅生成源码、CI 脚本，人工确认后再发布
