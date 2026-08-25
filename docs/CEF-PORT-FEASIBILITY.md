# CEF 移植可行性评估

> CinemaMod/mcef + WebDisplays → Android FoldCraft Launcher (FCL) 移植可行性分析
>
> 最后更新：2026-08-25

---

## 结论先行

**普通手机 FCL 几乎无法实现完整浏览器交互。**

路线 1（原生 CEF）存在 7 个不可绕过的技术障碍。路线 2（代理截图）是唯一可落地的方案，但只能显示静态截图，无交互能力。

---

## 1. MCEF 全部初始化流程

### 1.1 调用链

```
Minecraft 启动
│
├── MCEF.onPreInit()
│   ├── 加载 mcef.cfg
│   ├── 导入 SSL 证书
│   └── ClientProxy.onPreInit()
│
├── MCEF.onInit()
│   └── ClientProxy.onInit()
│       ├── RemoteConfig.downloadMissing()     ← 下载 native 资源
│       ├── 修改 ClassLoader.usr_paths          ← 注入 native 库路径
│       ├── System.load("libcef.so")           ← 加载 native 库
│       ├── CefApp.startup()                    ← CEF 全局初始化
│       ├── CefApp.getInstance(settings)        ← 创建 CEF 实例
│       │   └── N_PreInitialize()              ← JNI 调用
│       │   └── N_Initialize()                ← JNI 调用 ★需要窗口系统
│       ├── cefApp.createClient()               ← 创建 CefClient
│       └── 注册渲染 tick 事件
│
├── 每帧渲染
│   ├── cefApp.N_DoMessageLoopWork()           ← ★需要平台事件系统
│   └── CefRenderer: BGRA → OpenGL 纹理       ← ★使用 GL_BGRA (Android 不支持)
│
└── 关闭
    └── CefApp.N_Shutdown()
```

### 1.2 必须修改的关键文件

| 文件 | 职责 | Android 问题 |
|------|------|-------------|
| `ClientProxy.java` | CEF 初始化中心 | native 库路径、子进程路径 |
| `CefApp.java` | CEF 生命周期 | `N_Initialize()` 需要窗口系统 |
| `CefBrowserOsr.java` | 离屏渲染浏览器 | OSR 可能依赖桌面 GL |
| `CefRenderer.java` | OpenGL 纹理上传 | 使用 `GL_BGRA`，Android 不支持 |
| `RemoteConfig.java` | 下载 native 资源 | 下载 Win/Linux/macOS 库，无 Android |
| `OS.java` | 平台判断 | 不识别 Android |

---

## 2. FCL Application Context 限制分析

### 2.1 问题

FCL 运行 Minecraft 时，Java 线程持有的是 `Application Context`，不是 `Activity`。

CEF 初始化链路中的 JNI 调用隐式依赖窗口系统：
- `CefApp.N_Initialize()` — 需要操作系统窗口管理器
- `CefClient.createBrowser()` — 需要窗口句柄
- `CefApp.N_DoMessageLoopWork()` — 需要平台事件队列

### 2.2 可行的绕过方案

| 方案 | 可行性 | 说明 |
|------|--------|------|
| 虚拟 Activity 封装 | 极低 | 需要修改 CEF C++ 层才能使用 Android Surface/EGL |
| 反射获取 FCL Activity | 不确定 | FCL 内部 Activity 引用可能无法通过反射获取 |
| 修改 FCL 源码传递 Activity | 理论可行 | 需 Fork FCL，修改 JVMActivity 传递 Activity 给 mod |
| 使用 `--single-process` 模式 | 理论可行 | 极不稳定，CEF 官方不推荐 |
| 使用 Android WebView 替代 CEF | 可行（路线2） | 放弃 CEF，用 WebView 截图代理 |

### 2.3 根本不可能的方案

| 方案 | 为什么不行 |
|------|-----------|
| 纯 Fabric mod 加载 libcef.so | W^X 禁止从 mod 目录 dlopen |
| 通过反射创建假 Activity | CEF JNI 需要真实窗口句柄，不是 Java 对象 |
| 仅通过 API 包装绕过 | CEF 的窗口依赖在 C++ 层，不在 Java 层 |

---

## 3. 预编译 arm64-v8a libcef.so 来源

### 3.1 官方来源

| 来源 | 可用性 | 说明 |
|------|--------|------|
| CEF 官方 | 无 Android 构建 | 不提供 Android 预编译包 |
| Chromium for Android | 需自行编译 | 可从 Chromium 源码提取，但不是 CEF |
| java-cef (JCEF) | 无 Android 移植 | JCEF 项目不支持 Android |

### 3.2 编译 CEF 所需硬件配置

| 资源 | 最低要求 | GitHub 免费 runner | 付费 larger runner |
|------|---------|-------------------|-------------------|
| RAM | 32GB+ | 16GB | 64GB (32-core) |
| 磁盘 | 100GB+ | 14GB SSD | 150GB |
| 编译时间 | 4-8 小时 | 6 小时超时 | 无限制 |
| CPU | 16+ cores | 2 cores | 32 cores |
| 费用 | — | 免费 | ~$40-50/次 |

### 3.3 验证结果

2026-08-25 在 GitHub 免费 runner 上验证：
- 失败步骤：`gclient sync` (Fetch Chromium source)
- 错误：`OSError: [Errno 28] No space left on device`
- 运行时间：1小时15分钟后失败
- 原因：14GB SSD 不足以容纳 Chromium 源码（需 100GB+）

---

## 4. 完整风险清单

### 4.1 Android 系统硬限制（无法绕过）

| # | 限制 | 影响 | 能否绕过 |
|---|------|------|---------|
| 1 | W^X 内存策略 (Android 10+) | SELinux 禁止从缓存目录 dlopen .so | 否 — 必须打包进 FCL APK |
| 2 | Activity 依赖 | CEF JNI 初始化需要窗口系统 | 否 — C++ 层依赖 |
| 3 | 子进程模型 | jcef_helper 无法 fork 子进程 | 否 — Android 不允许 |
| 4 | OpenGL 差异 | GL_BGRA 不支持 | 可修复 — 改为 GL_RGBA |
| 5 | 消息循环 | CEF 消息循环与 Android Looper 不兼容 | 需自定义桥接层 |
| 6 | CEF 无 Android 构建 | 官方不提供 Android 预编译包 | 需自建编译环境 |
| 7 | JCEF 无 Android 移植 | Java CEF 绑定不支持 Android | 需自行移植 |

### 4.2 Android 14+ 额外限制

| 限制 | 影响 |
|------|------|
| 前台服务限制 | 后台 WebView 渲染可能被杀 |
| 更严格的 SELinux 策略 | W^X 执行更严格 |
| 电池优化 | 长时间截图服务可能被系统限制 |
| 网络权限 | 明文 HTTP 需显式声明 |

---

## 5. 什么条件才有可能跑通 MCEF/WebDisplays

需要同时满足以下全部条件：

1. **获得 Android arm64 版 libcef.so** — 需自建 64GB+ RAM 的 Linux 编译服务器
2. **移植 JCEF Java 层到 Android** — 无现成方案，工作量巨大
3. **修改 CEF C++ 层支持 Android Surface/EGL** — 需修改 Chromium 源码
4. **实现 jcef_helper 替代方案** — 使用 `--single-process` 或 Android Service
5. **重新打包 FCL APK 内置 libcef.so** — 绕过 W^X 限制
6. **修改 MCEF 的 OpenGL 调用适配 OpenGL ES** — GL_BGRA → GL_RGBA
7. **实现消息循环桥接** — CEF ↔ Android Looper
8. **修改 FCL 传递 Activity 给 mod** — 需 Fork FCL 修改 JVMActivity

**实际评估：以上 8 个条件全部满足的概率极低。**

---

## 6. 现实结论

### 路线 1（原生 CEF）：不可行

- 存在 7 个不可绕过的技术障碍
- 官方 MCEF 明确声明不支持 Android
- 即使花 $40-50 编译出 libcef.so，也无法在 FCL 上正常初始化

### 路线 2（代理截图）：可立即落地

- 已完成 Fabric mod 实现（自定义方块 + GUI + PNG 截图 + 纹理渲染）
- CI 构建成功，可直接下载 jar
- 限制：仅静态截图，无交互能力
- WebView 在 Application Context 下可能创建失败

### 最终建议

普通手机 FCL 环境下，**放弃完整浏览器交互**，使用路线 2 的代理截图方案作为临时可用替代。这是当前技术条件下的最优解。

---

## 参考资料

- [CinemaMod/mcef](https://github.com/CinemaMod/mcef) — 上游 MCEF
- [java-cef](https://github.com/chromiumembedded/java-cef) — Java CEF 绑定
- [Chromium Android 编译指南](https://chromium.googlesource.com/chromium/src/+/HEAD/docs/android_build_instructions.md)
- [FoldCraftLauncher](https://github.com/FCL-Team/FoldCraftLauncher) — FCL 启动器
- [GitHub Actions runner 规格](https://docs.github.com/en/actions/using-github-hosted-runners/about-github-hosted-runners)
