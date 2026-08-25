# EXPERIENCE-shturl 开发踩坑日志

> mcef-android-fcl-port 项目完整开发记录
>
> 记录所有遇到的现实问题、技术死胡同、可行方案与能力边界
>
> 日期：2026-08-25

---

## 项目背景

目标：将 MCEF + WebDisplays 移植到 Android FoldCraft Launcher (FCL) 启动器。
Minecraft 1.20.1 Fabric，目标架构 arm64-v8a。

约束：
1. FCL 环境只能拿到 Application Context，拿不到 Activity
2. 不能修改 FCL 启动器 APK 本身（只能 Fabric mod 层面）
3. GitHub 免费 Actions 运行机资源有限
4. 不引入任何 native so 库

---

## 踩坑 1: GitHub 免费 Actions 编译 CEF OOM 超时

### 问题

在 GitHub Actions 免费 runner 上尝试编译完整 Chromium/CEF：
- runner 规格：2 CPU / 16GB RAM / 14GB SSD
- Chromium 要求：16+ CPU / 32GB+ RAM / 100GB+ 磁盘

### 实际失败过程

| 时间点 | 状态 | 详情 |
|--------|------|------|
| 0:00 | 开始 | `fetch --nohooks android` |
| ~30min | 运行中 | `gclient sync --no-history --shallow` 正在下载源码 |
| ~1h15min | 失败 | `OSError: [Errno 28] No space left on device` |

### 原因

Chromium 源码压缩包约 30GB，解压后 80GB+。免费 runner 14GB SSD 在解压阶段就耗尽。

### 结论

GitHub 免费 runner **无法**编译 CEF。必须自建大内存 Linux 服务器（64GB RAM, 200GB SSD）或使用付费 larger runner（$40-50/次）。

但即使花这笔钱编译出 libcef.so，后续仍面临 Activity 依赖等不可绕过的技术死胡同。

---

## 踩坑 2: FCL Mod 拿不到 Android Activity

### 问题

FCL 运行 Minecraft 时，Java 线程持有 `Application Context`，不是 `Activity`。
CEF 初始化链路中的 JNI 调用隐式依赖窗口系统：

| JNI 调用 | 需要的 OS 能力 | Android 替代 |
|---------|---------------|-------------|
| `N_Initialize()` | 窗口管理器 | 无直接替代 |
| `N_CreateBrowser()` | 窗口句柄 | Android Surface |
| `N_DoMessageLoopWork()` | 平台事件队列 | Android Looper |
| `browser_subprocess_path` | fork/exec 子进程 | Android 不支持 |

### 尝试的绕过方案

| 方案 | 结果 |
|------|------|
| 虚拟 Activity 包装 | 不可行 — CEF 的窗口依赖在 C++ 层 |
| 反射获取 FCL Activity | 不确定 — 内部引用可能无法通过反射获取 |
| `--single-process` 模式 | 极不稳定 — CEF 官方不推荐 |
| 修改 FCL 源码传递 Activity | 理论可行但需 Fork FCL |

### 结论

**CEF 初始化需要 Android Activity 是 Android 系统级限制，无法通过 Fabric mod 代码绕过。**
CEF 的窗口依赖在 C++ 层，不是 Java 层 API 包装能解决的。

---

## 踩坑 3: 安卓 GL4ES 贴图限制

### 问题

FCL 使用 GL4ES（OpenGL→OpenGL ES 转换层），存在多种贴图限制：

| 限制 | 详情 |
|------|------|
| `GL_BGRA` 不支持 | MCEF 使用 `GL_BGRA + GL_UNSIGNED_INT_8_8_8_8_REV`，GL4ES 仅支持 `GL_RGBA` |
| 最大纹理尺寸 | 通常 4096，但实际 2048 以上就不稳定 |
| 大纹理段错误 | 超过 1024 的纹理在 GL4ES 上容易段错误 |
| 混合模式 bug | GL4ES 的 alpha blending 有已知的 bug |
| 纹理泄漏 | GL4ES 不自动回收纹理，需手动 `glDeleteTextures` |

### 实际遇到的崩溃

1. **ByteBuffer.wrap() 段错误** — LWJGL 要求直接缓冲区传递给 OpenGL，使用堆缓冲区会段错误
   - 修复：使用 `MemoryUtil.memAlloc()` 分配直接缓冲区

2. **大纹理上传崩溃** — 1280x720 的 RGBA 纹理上传到 GL4ES 可能段错误
   - 修复：限制 `maxTextureSize` 为 512，自动缩放

### 结论

在 GL4ES 环境下，纹理尺寸必须限制在 512x512 以内。所有 OpenGL 调用必须使用 GL_RGBA 格式和 GL_UNSIGNED_BYTE 类型。

---

## 踩坑 4: FCL 网络请求行为特点

### 问题

FCL 环境下的 Java HTTP 请求行为与桌面 Minecraft 不同：

| 行为 | 桌面 Minecraft | FCL Android |
|------|--------------|-------------|
| localhost (127.0.0.1) | 正常 | 正常 |
| 远程 HTTP | 正常 | 受 Android 网络策略限制 |
| HTTPS 自签名证书 | 可配置信任 | 使用 Android 系统 CA 证书 |
| 后台网络 | 正常 | 可能被 Android 电池优化限制 |
| DNS 解析 | 系统 DNS | Android 系统 DNS |

### 实际遇到的问题

1. **截图服务连接被拒** — 默认服务在 localhost:28085，如果 FCL 未集成截图服务，连接被拒
   - 修复：mod 自动降级为占位纹理

2. **HTTP 超时** — WebView 加载网页可能需要 5-10 秒，截图服务超时设为 10 秒
   - 修复：增加超时时间，加错误处理

### 结论

FCL 的 localhost 通信正常工作。远程 HTTP 请求受 Android 系统策略限制。建议截图服务运行在 localhost。

---

## 踩坑 5: 代理 Mod 实现思路与能力边界

### 实现思路

```
玩家放置 WebScreenBlock 方块
  → 右键方块打开 GUI
  → 输入网页 URL（如 https://www.baidu.com）
  → 保存设置
  → 方块实体按刷新间隔发送 HTTP GET 请求
  → 截图服务（localhost:28085）使用 WebView 加载网页
  → WebView.draw() 截取 Bitmap
  → Bitmap.compress(PNG) 转为 PNG
  → 返回 PNG 字节流给 mod
  → mod 使用 NativeImage.read() 解码 PNG
  → NativeImageBackedTexture 上传到 OpenGL ES 纹理
  → BlockEntityRenderer 在方块正面绘制带纹理四边形
```

### 能力边界

| 能力 | 是否实现 | 说明 |
|------|---------|------|
| 显示网页静态画面 | 是 | 按刷新间隔更新截图 |
| 游戏内输入网址 | 是 | GUI 界面 + TextField |
| 尺寸缩放 | 是 | 限制最大纹理尺寸，防止 GL4ES 崩溃 |
| 失败容错 | 是 | 网络超时/截图失败显示错误色块 |
| 图片缓存 | 是 | 按刷新间隔避免频繁请求 |
| 鼠标点击 | **否** | 无法将点击坐标传回 WebView |
| 滚动页面 | **否** | 无法控制 WebView 滚动 |
| 键盘输入 | **否** | 无法将键盘事件传回 WebView |
| 视频播放 | **否** | 逐帧截图太慢，无法实现 |
| 实时交互 | **否** | 截图代理架构的根本限制 |

### 为什么不能交互

交互需要：
1. 在 Minecraft 内捕获鼠标/键盘事件
2. 将事件坐标转换到 WebView 坐标
3. 通过 HTTP 发送给截图服务
4. 截图服务在 WebView 上模拟事件
5. 等待 WebView 重新渲染
6. 重新截图

这个流程的延迟至少 2-5 秒（HTTP 往返 + WebView 渲染 + 截图），无法实现实时交互。

---

## 踩坑 6: Fabric 1.20.1 API 细节

### 遇到的 API 问题

1. **BlockEntityRenderer 注册** — Fabric API 的 `BlockEntityRendererRegistry.INSTANCE.register()` 在不同版本有不同签名
   - 最终使用：`BlockEntityRendererRegistry.INSTANCE.register(type, ctx -> new Renderer())`

2. **NativeImage 方法名** — `setPixelRGBA` vs `setColor` 在不同 Yarn 版本不同
   - 最终使用：`setPixelRGBA` / `getPixelRGBA`

3. **UseBlockCallback** — `net.fabricmc.fabric.api.event.player.UseBlockCallback` 签名
   - 返回 `ActionResult`，在 `world.isClient` 时打开 GUI

4. **HorizontalFacingBlock** — 需要手动实现 `getPlacementState()` 设置朝向
   - `ctx.getHorizontalPlayerFacing().getOpposite()`

5. **fabric.mod.json entrypoints** — 需要 `main` 和 `client` 两个入口
   - `main`: ModInitializer（注册方块/物品/方块实体）
   - `client`: ClientModInitializer（注册渲染器/GUI）

---

## 踩坑 7: GitHub CLI 认证

### 问题

不同 session 之间 GitHub CLI 认证状态丢失。`gh` 二进制可能不在 PATH 中。

### 修复

1. 通过代理下载 gh 二进制：`curl --proxy http://127.0.0.1:18080`
2. 设置 `HTTPS_PROXY` 环境变量
3. 使用 `gh auth login --web` 设备码认证
4. 执行 `gh auth setup-git` 配置 git 凭证

### 注意事项

- GitHub CLI token 需要 `repo` 和 `workflow` scope
- `workflow` scope 缺失会导致推送 `.github/workflows/` 文件被拒绝
- 设备码 15 分钟过期

---

## 总结：哪些能实现，哪些做不到

### 能实现

- 纯 Fabric Java mod，FCL 直接加载
- 自定义方块，右键打开 GUI 输入网址
- HTTP 请求截图服务获取网页 PNG 图片
- 图片贴图渲染到方块表面（OpenGL ES 兼容）
- 尺寸缩放防止 GL4ES 崩溃
- 失败容错：网络超时显示错误色块
- 图片缓存避免频繁请求
- CI 自动构建产出 mod jar

### 做不到（系统/硬件限制）

- 完整 CEF 浏览器在 Android 上运行（7 个技术死胡同）
- 鼠标点击/键盘输入交互（截图代理架构限制）
- 视频播放（逐帧截图太慢）
- GitHub 免费 runner 编译 CEF（磁盘/内存不足）
- 从 mod 目录加载 libcef.so（W^X 内存保护）
- jcef_helper 子进程（Android 不允许 fork）

### 客观结论

本项目证明了：在当前 Android 系统限制下，普通手机 FCL 环境无法运行完整 CEF 浏览器。代理截图方案是唯一可落地的替代方案，功能限于静态网页画面显示，无交互能力。这不是代码能力问题，而是 Android 操作系统安全策略的根本限制。
