# FCL 安卓平台 Fabric Mod 故障排查指南

> 在 FoldCraft Launcher (FCL) 环境下运行 Fabric Mod 的常见问题分析指南
>
> 适用于 proxy-web-mod 及其他 Fabric mod
>
> 最后更新：2026-08-25

---

## 日志位置

FCL 的 Minecraft 运行日志通常位于：
```
/storage/emulated/0/Android/data/com.fcl.launcher/files/games/com.mojang/
└── minecraftedirtories/<版本>/
    └── logs/
        ├── latest.log
        └── crash-reports/
```

也可在 FCL 启动器内查看实时日志。

---

## 问题分类

### 1. 依赖缺失

#### 1.1 缺少 Fabric-API

**症状：**
```
java.lang.NoClassDefFoundError: net/fabricmc/fabric/api/...
```

**根本报错行：**
```
Caused by: java.lang.ClassNotFoundException: net.fabricmc.fabric.api.event.player.UseBlockCallback
```

**修复方案：**
1. 下载 Fabric API（对应 MC 1.20.1 版本：`fabric-api-0.92.2+1.20.1.jar`）
2. 放入 FCL 的 mods 目录
3. 重启游戏

**FCL 特殊坑：**
FCL 可能使用自带的 Fabric Loader 版本。确保 Loader 版本 >= 0.15.7。如果 FCL 自带的版本较低，需要更新 FCL 本身。

---

### 2. 网络问题

#### 2.1 HTTP 请求失败

**症状：**
```
[proxy-web-mod] 截图请求失败: http://127.0.0.1:28085/screenshot - Connection refused
```

**根本原因：**
截图服务未启动（FCL 未集成 `WebViewScreenshotService`）或端口被占用。

**修复方案：**
1. 确认 FCL 已集成截图服务（需修改 FCL 源码）
2. 或配置远程截图服务地址：修改 `config/proxy-web-mod.json` 中 `serviceUrl`
3. 无截图服务时 mod 自动降级为占位纹理，不会崩溃

#### 2.2 SSL/HTTPS 报错

**症状：**
```
javax.net.ssl.SSLHandshakeException: PKIX path building failed
```

**根本原因：**
Android 的 CA 证书库可能与 JVM 不同。FCL 使用 Android 系统的证书。

**修复方案：**
- 截图服务使用 `http://127.0.0.1:28085`（本地 HTTP，无 SSL）
- 如需远程 HTTPS 服务，确保服务端证书被 Android 系统信任

#### 2.3 Android 网络权限

**症状：**
```
java.net.SocketException: Permission denied
```

**根本原因：**
FCL 的 Android 进程缺少 `INTERNET` 权限。

**修复方案：**
- FCL 启动器的 AndroidManifest.xml 已声明 INTERNET 权限
- localhost 通信不需要额外权限
- 远程服务需要确保 FCL 有网络访问权限

**FCL 网络行为特点：**
- localhost (127.0.0.1) 通信正常，不需要特殊权限
- 远程 HTTP 请求受 Android 网络策略限制
- 后台网络访问可能被 Android 电池优化限制

---

### 3. 贴图/纹理加载

#### 3.1 OpenGL 纹理上传失败

**症状：**
```
java.lang.IllegalStateException: glGetError() returned 1281 (GL_INVALID_VALUE)
```

**根本原因：**
纹理尺寸超过 OpenGL ES 最大纹理尺寸限制。FCL 使用 GL4ES（OpenGL→OpenGL ES 转换层），最大纹理尺寸通常为 4096 或更小。

**修复方案：**
1. 在 GUI 中设置较小的截图尺寸（如 512x512 或 256x256）
2. 修改 `config/proxy-web-mod.json` 中 `maxTextureSize` 为 512 或更小
3. proxy-web-mod v2.0 已内置 `scaleDown()` 自动缩放

#### 3.2 GL4ES 特殊坑

**已知问题：**
| 问题 | 原因 | 修复 |
|------|------|------|
| `GL_BGRA` 不支持 | GL4ES 仅支持 `GL_RGBA` | proxy-web-mod 已使用 GL_RGBA |
| 大纹理段错误 | GL4ES 对超大纹理不稳定 | maxTextureSize 限制为 512 |
| 混合模式异常 | GL4ES 的 alpha blending 有 bug | 避免半透明纹理，使用不透明 |
| 纹理泄漏 | GL4ES 不自动回收纹理 | 确保调用 `texture.close()` |

#### 3.3 NativeImage 加载 PNG 失败

**症状：**
```
java.io.IOException: Failed to load image
```

**根本原因：**
截图服务返回的数据不是有效的 PNG 格式。

**修复方案：**
1. 确认截图服务返回 `image/png` 格式
2. 检查 WebView 截图是否返回 null（WebView 未初始化）
3. proxy-web-mod v2.0 已加 try-catch 降级处理

---

### 4. 内存溢出

#### 4.1 贴图尺寸过大崩溃

**症状：**
```
java.lang.OutOfMemoryError: Failed to allocate a N byte allocation
```

**根本原因：**
NativeImage（RGBA）内存占用 = width × height × 4 字节。
512×512 = 1MB，2048×2048 = 16MB，4096×4096 = 64MB。
多个大方块同时渲染会快速耗尽内存。

**修复方案：**
1. 限制 `maxTextureSize` 为 512（默认值）
2. 不要放置太多 WebScreenBlock 方块
3. proxy-web-mod v2.0 已内置自动缩放

#### 4.2 Android 堆内存限制

**症状：**
```
java.lang.OutOfMemoryError: Java heap space
```

**根本原因：**
FCL 给 Minecraft 的 JVM 堆内存有限（通常 1-2GB）。

**修复方案：**
- 在 FCL 设置中增加 JVM 堆内存
- 减少同时加载的方块数量
- 使用较小的截图尺寸

---

### 5. Mixin / Mod 初始化异常

#### 5.1 BlockEntityType 注册失败

**症状：**
```
java.lang.IllegalStateException: Block entity type proxy-web-mod:web_screen not registered
```

**根本原因：**
Mod 入口 `ProxyWebMod.onInitialize()` 未正确执行，或注册顺序错误。

**修复方案：**
1. 检查 `fabric.mod.json` 中 `entrypoints.main` 是否正确
2. 确认 Fabric Loader 已加载 mod
3. 查看 `latest.log` 中是否有 "Proxy Web Mod 初始化完成" 日志

#### 5.2 BlockEntityRenderer 注册失败

**症状：**
```
java.lang.NullPointerException: Rendering Block Entity
```

**根本原因：**
客户端入口 `ProxyWebModClient.onInitializeClient()` 未正确注册渲染器。

**修复方案：**
1. 检查 `fabric.mod.json` 中 `entrypoints.client` 是否正确
2. 确认 Fabric API 已加载（`BlockEntityRendererRegistry` 需要 Fabric API）

#### 5.3 Mixin 冲突

**症状：**
```
org.spongepowered.asm.mixin.transformer.throwables.MixinApplyError
```

**根本原因：**
proxy-web-mod v2.0 不使用 Mixin。如果出现此错误，检查是否与其他 mod 冲突。

**修复方案：**
- 移除可能有冲突的 mod
- 查看 mixin crash report 确定冲突来源

---

## 通用排查步骤

1. **查看 `latest.log`** — 搜索 `proxy-web-mod` 或 `ERROR`
2. **查看 crash report** — 在 `crash-reports/` 目录下
3. **确认依赖** — Fabric API 是否安装
4. **确认配置** — `config/proxy-web-mod.json` 是否正确
5. **确认网络** — 截图服务是否可达
6. **减少方块数量** — 排除内存问题

---

## FCL 平台特殊坑点汇总

| 坑点 | 说明 | proxy-web-mod 应对 |
|------|------|-------------------|
| GL4ES 最大纹理限制 | 通常 4096，实际建议 512 | maxTextureSize 默认 512 |
| GL4ES 不支持 GL_BGRA | 桌面 OpenGL 格式 | 使用 GL_RGBA + GL_UNSIGNED_BYTE |
| Application Context 无 Activity | WebView 创建可能失败 | 截图服务在 FCL 层集成 |
| localhost 通信 | 正常工作 | 截图服务用 127.0.0.1 |
| JVM 堆内存限制 | 通常 1-2GB | 限制方块数量和纹理尺寸 |
| 后台渲染限制 | WebView 后台 draw() 可能空白 | 已知限制，无法修复 |
| Android 清理后台 | 长时间运行可能被杀 | 截图服务需前台运行 |
