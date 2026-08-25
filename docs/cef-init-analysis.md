# CEF 初始化入口点分析

> 基于 [CinemaMod/mcef](https://github.com/CinemaMod/mcef) 上游源码的完整逆向分析。
> 目标：找出所有 CEF 初始化入口点，标记所有必须 Android Activity 的调用位置。

---

## 1. 仓库基本信息

- 仓库：`https://github.com/CinemaMod/mcef`
- 默认分支：`1.21.4`（非 master）
- 当前 Chromium 版本：`116.0.5845.190`
- 平台支持：Windows 10/11、macOS 11+、Linux glibc 2.31+
- **Android 支持状态**：README 明确声明 "This mod will not work on Android"

---

## 2. CEF 初始化完整调用链

```
Minecraft 客户端启动
│
├── MCEF.onPreInit(FMLPreInitializationEvent)
│   ├── 加载 mcef.cfg 配置
│   ├── 导入 Let's Encrypt SSL 证书
│   └── ClientProxy.onPreInit()
│       └── ExampleMod.onPreInit()
│
├── MCEF.onInit(FMLInitializationEvent)
│   └── ClientProxy.onInit()
│       ├── 1. 计算 ROOT = mc.mcDataDir
│       ├── 2. RemoteConfig cfg = new RemoteConfig()
│       │   └── cfg.load() → 下载 native 资源列表
│       │   └── cfg.downloadMissing(ipl)
│       │       └── 下载 Windows/Linux/macOS 平台的 .dll/.so 文件
│       │
│       ├── 3. 修改 java.library.path
│       │   └── 反射修改 ClassLoader.usr_paths
│       │       注入 ROOT 目录到 native 库搜索路径
│       │
│       ├── 4. 加载 native 库
│       │   ├── Linux: libcef.so, libjcef.so
│       │   ├── Windows: libcef.dll, jcef.dll, d3dcompiler_47.dll,
│       │   │           libGLESv2.dll, libEGL.dll, chrome_elf.dll
│       │   └── macOS: libcef.dylib, jcef.dylib
│       │   └── System.load(new File(ROOT, lib).getAbsolutePath())
│       │
│       ├── 5. CefApp.startup()
│       │   └── CEF 全局初始化（singleton 状态机）
│       │       NONE → NEW → INITIALIZING → INITIALIZED
│       │
│       ├── 6. CefSettings 配置
│       │   ├── windowless_rendering_enabled = true
│       │   ├── locales_dir_path = ROOT/MCEFLocales
│       │   ├── cache_path = ROOT/MCEFCache
│       │   └── browser_subprocess_path = ROOT/jcef_helper
│       │
│       ├── 7. CefApp.getInstance(settings)
│       │   └── N_PreInitialize() ← JNI 调用
│       │   └── N_Initialize()    ← JNI 调用（需要窗口系统）
│       │
│       ├── 8. cefApp.createClient()
│       │   └── 创建 CefClient 实例
│       │
│       ├── 9. CefMessageRouter.create()
│       │   └── 注册消息路由器
│       │
│       └── 10. MinecraftForge.EVENT_BUS.register(this)
│           └── 注册渲染 tick 事件监听
│
├── 每帧渲染 tick (TickEvent.RenderTickEvent)
│   ├── cefApp.N_DoMessageLoopWork()
│   │   └── CEF 消息循环处理（需要平台事件系统）
│   ├── browser.mcefUpdate()
│   │   └── CefRenderer: BGRA → OpenGL 纹理上传
│   └── displayHandler.update()
│
├── 浏览器创建
│   └── ClientProxy.createBrowser(url, transp)
│       └── cefClient.createBrowser(url, true, transp)
│           └── CefBrowserOsr 创建
│               └── createImmediately()
│                   └── N_CreateBrowser() ← JNI 调用（需要窗口句柄）
│
└── 关闭
    └── ClientProxy.onShutdown()
        ├── 关闭所有浏览器
        ├── cefClient.dispose()
        └── CefApp.N_Shutdown()
```

---

## 3. 关键 Java 文件清单

### 3.1 MCEF 核心层

| 文件路径 | 职责 | Android 问题 |
|---------|------|-------------|
| `net/montoyo/mcef/MCEF.java` | Mod 入口，生命周期管理 | Forge @Mod 注解，Fabric 需改写 |
| `net/montoyo/mcef/BaseProxy.java` | 代理基类 | 无直接问题 |
| `net/montoyo/mcef/client/ClientProxy.java` | **CEF 初始化中心** | native 库路径、子进程路径 |
| `net/montoyo/mcef/client/VirtualBrowser.java` | 虚拟浏览器（降级） | 可参考 |

### 3.2 API 层

| 文件路径 | 职责 |
|---------|------|
| `net/montoyo/mcef/api/API.java` | 对外 API 接口 |
| `net/montoyo/mcef/api/IBrowser.java` | 浏览器接口 |
| `net/montoyo/mcef/api/IDisplayHandler.java` | 显示处理器接口 |
| `net/montoyo/mcef/api/IJSQueryHandler.java` | JS 查询处理器接口 |
| `net/montoyo/mcef/api/IScheme.java` | 自定义协议接口 |

### 3.3 JCEF 封装层

| 文件路径 | 职责 | Android 问题 |
|---------|------|-------------|
| `org/cef/CefApp.java` | CEF 生命周期管理 | `N_Initialize()` 需要窗口系统 |
| `org/cef/CefClient.java` | CEF 客户端 | `createBrowser()` 需要窗口句柄 |
| `org/cef/CefSettings.java` | CEF 配置 | `browser_subprocess_path` 无 Android 等价物 |
| `org/cef/browser/CefBrowserOsr.java` | 离屏渲染浏览器 | OSR 可能依赖桌面 GL |
| `org/cef/browser/CefRenderer.java` | OpenGL 纹理上传 | 使用 `GL_BGRA`，Android 不支持 |
| `org/cef/handler/CefAppHandlerAdapter.java` | 应用处理器 | 生命周期回调 |
| `org/cef/handler/CefLifeSpanHandlerAdapter.java` | 生命周期处理器 | 浏览器创建/关闭回调 |
| `org/cef/util/OS.java` | 平台判断 | 不识别 Android |

### 3.4 资源下载层

| 文件路径 | 职责 | Android 问题 |
|---------|------|-------------|
| `net/montoyo/mcef/remote/RemoteConfig.java` | 下载 native 资源 | 下载 Win/Linux/macOS 库，无 Android |
| `net/montoyo/mcef/remote/MirrorManager.java` | 镜像管理 | 无 Android 镜像 |
| `net/montoyo/mcef/utilities/Util.java` | 工具类 | 平台相关逻辑 |

---

## 4. Native 库加载方式

MCEF 不使用 `System.loadLibrary()`，而是手动 `System.load()` 并修改 library path。

### 4.1 修改 ClassLoader.usr_paths

```java
Field pathsField = ClassLoader.class.getDeclaredField("usr_paths");
pathsField.setAccessible(true);
String[] paths = (String[]) pathsField.get(null);
String[] newList = new String[paths.length + 1];
System.arraycopy(paths, 0, newList, 1, paths.length);
newList[0] = ROOT.replace('/', File.separatorChar);
pathsField.set(null, newList);
```

### 4.2 平台相关加载

**Linux：**
```java
libs.add("libcef.so");
libs.add("libjcef.so");
// 还需要 jcef_helper 有执行权限
```

**Windows：**
```java
libs.add("d3dcompiler_47.dll");
libs.add("libGLESv2.dll");
libs.add("libEGL.dll");
libs.add("chrome_elf.dll");
libs.add("libcef.dll");
libs.add("jcef.dll");
```

### 4.3 Android 对应关系

Android 不需要修改 `ClassLoader.usr_paths`。
Android 原生库通过 `System.loadLibrary()` 或 `System.load(绝对路径)` 加载。
`.so` 文件必须位于 APK 的 `lib/arm64-v8a/` 或 `jniLibs/` 目录。

---

## 5. CefApp 生命周期状态机

```
NONE → NEW → INITIALIZING → INITIALIZED
```

- `CefApp.startup()` — 全局初始化
- `CefApp.getInstance(settings)` — 创建实例，触发 `N_Initialize()`
- `CefApp.createClient()` — 创建客户端
- `CefApp.N_DoMessageLoopWork()` — 消息循环（每帧调用）
- `CefApp.N_Shutdown()` — 关闭

### 关键 JNI 调用（C 层）

| Java 方法 | C 层功能 | Android 兼容性 |
|-----------|---------|---------------|
| `N_PreInitialize()` | CEF 预初始化 | 可能兼容 |
| `N_Initialize()` | CEF 主初始化 | **不兼容**（需要窗口系统） |
| `N_DoMessageLoopWork()` | 消息循环 | **不兼容**（需要平台事件系统） |
| `N_Shutdown()` | 关闭 | 可能兼容 |
| `N_CreateBrowser()` | 创建浏览器 | **不兼容**（需要窗口句柄） |

---

## 6. 渲染管线分析

### 6.1 MCEF 的 OSR (Off-Screen Rendering) 流程

```
CEF 渲染线程
  → 生成 BGRA 像素数据 (ByteBuffer)
  → CefRenderHandler.onPaint() 回调
  → CefBrowserOsr.onPaint(buffer, width, height, dirtyRects)
  → CefRenderer:
      → glBindTexture(GL_TEXTURE_2D, textureID)
      → glTexSubImage2D(..., GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer)
  → Minecraft 渲染线程绘制方块
```

### 6.2 Android OpenGL ES 差异

| MCEF 使用 | Android OpenGL ES | 兼容性 |
|-----------|-------------------|--------|
| `GL_BGRA` | 不支持 | 需改为 `GL_RGBA` |
| `GL_UNSIGNED_INT_8_8_8_8_REV` | 不支持 | 需改为 `GL_UNSIGNED_BYTE` |
| `glTexImage2D` | 支持 | 兼容 |
| `glTexSubImage2D` | 支持 | 兼容 |
| `glGenTextures` | 支持 | 兼容 |
| `glDeleteTextures` | 支持 | 兼容 |

### 6.3 代理方案 (ProxyRenderer) 的改进

`ProxyRenderer.java` 已使用 `GL_RGBA` + `GL_UNSIGNED_BYTE`，与 OpenGL ES 兼容。
使用 `MemoryUtil.memAlloc()` 分配直接缓冲区，满足 LWJGL 对 `glTexImage2D` 的要求。

---

## 7. 子进程模型分析

### 7.1 桌面 CEF 多进程架构

```
主进程 (Minecraft JVM)
  ├── CEF Browser Process (主)
  ├── Renderer Process (jcef_helper)
  ├── GPU Process (jcef_helper)
  └── Utility Process (jcef_helper)
```

`CefSettings.browser_subprocess_path` 指向 `jcef_helper` 可执行文件。

### 7.2 Android 限制

Android 不允许应用自由 fork 子进程执行外部二进制。
`jcef_helper` 无法作为独立进程运行。

### 7.3 可能的绕过方案

| 方案 | 可行性 | 风险 |
|------|--------|------|
| `--single-process` 模式 | 理论可行 | 极不稳定，CEF 官方不推荐 |
| 将 jcef_helper 逻辑嵌入主进程 | 需大量 C++ 修改 | 工作量巨大 |
| 使用 Android Service 替代子进程 | 需重写 CEF 进程模型 | 不保证兼容 |

---

## 8. Activity 依赖总结

MCEF Java 层不直接引用 `android.app.Activity`。
但 CEF/JCEF 的 JNI 层在以下位置隐式依赖操作系统窗口系统：

| 调用点 | JNI 方法 | 依赖的 OS 能力 | Android 替代方案 |
|--------|---------|---------------|-----------------|
| CEF 初始化 | `N_Initialize()` | 窗口管理器 | 无直接替代 |
| 浏览器创建 | `N_CreateBrowser()` | 窗口句柄 (HWND/Window) | Android Surface |
| 消息循环 | `N_DoMessageLoopWork()` | 平台事件队列 | Android Looper |
| 子进程 | `browser_subprocess_path` | fork/exec | Android 不支持 |
| GPU 渲染 | 内部 GPU 上下文 | 桌面 GL/EGL | OpenGL ES |

### 虚拟 Activity 封装方案（理论）

```java
// 理论上的虚拟 Activity 包装
public class VirtualActivity {
    private Surface fakeSurface;
    private EGLContext eglContext;

    // 模拟 Activity 生命周期
    public void onCreate() {
        // 创建假 Surface
        // 初始化 EGL 上下文
        // 传递给 CEF JNI
    }
}
```

**可行性评估**：极低。
- CEF 的 JNI 层直接调用操作系统 API（X11/Wayland/Win32），不是 Java 接口
- 需要修改 CEF 的 C++ 源码才能使用 Android 的 Surface/EGL
- 这相当于移植整个 CEF 到 Android，工作量与 Chromium for Android 相当

---

## 9. 结论

### 路线 1 (原生 CEF) 技术可行性

**结论：当前不可行。**

需要同时解决以下全部问题：
1. 从 Chromium for Android 源码编译 CEF（需付费编译环境）
2. 移植 JCEF Java 层到 Android（无现成方案）
3. 修改 CEF C++ 层支持 Android Surface/EGL（工作量巨大）
4. 实现 jcef_helper 替代方案或单进程模式（极不稳定）
5. 重新打包 FCL APK 内置 libcef.so（W^X 限制）
6. 修改 MCEF 的 OpenGL 调用适配 OpenGL ES
7. 实现消息循环桥接（CEF ↔ Android Looper）

### 路线 2 (代理截图) 技术可行性

**结论：可立即落地，但功能受限。**

已实现的部分：
- 完整的 Fabric mod 代码（ProxyWebMod、ProxyAPI、ProxyBrowser、ProxyRenderer）
- WebView 截图 HTTP 服务（WebViewScreenshotService）
- 降级模式（截图服务不可用时显示占位纹理）
- OpenGL ES 兼容的纹理上传
- CI 自动构建（已通过）

未解决的限制：
- WebView 在 Application Context 下可能创建失败（Android 版本相关）
- WebView 后台渲染可能返回空白（渲染管线限制）
- 无交互能力（仅静态截图）
- 需要修改 FCL 源码集成截图服务

---

## 参考资料

- [CinemaMod/mcef](https://github.com/CinemaMod/mcef) — 上游 MCEF 仓库
- [java-cef](https://github.com/chromiumembedded/java-cef) — Java CEF 绑定
- [CEF](https://bitbucket.org/chromiumembedded/cef/) — Chromium Embedded Framework
- [Chromium for Android](https://chromium.googlesource.com/chromium/src/+/HEAD/docs/android_build_instructions.md) — Android 编译指南
- [FoldCraftLauncher](https://github.com/FCL-Team/FoldCraftLauncher) — FCL 启动器
