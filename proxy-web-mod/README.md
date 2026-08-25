# proxy-web-mod

> 代理式轻量替代模组 — 不依赖 CEF native 库，通过 Android 系统 WebView 获取网页截图转为 Minecraft 纹理。

## 背景

当完整 CEF 内嵌移植因以下原因无法实现时，本模块作为降级方案：

- Android 10+ W^X / SELinux 限制无法加载外部 native 库
- 官方 CEF 不支持 Android，从源码编译 Chromium 需要 128GB+ 内存
- FCL 内部 JVM 线程缺少 Android Activity/Context/Surface

## 原理

```
Minecraft Java (MCEF API)
    ↓ 请求 URL + 分辨率
FCL 内置 HTTP 服务 (Android WebView 截图)
    ↓ WebView.loadUrl()
Android 系统 WebView (无需 libcef.so)
    ↓ onPageFinished
截取 Bitmap
    ↓ BGRA→RGBA 转换
ByteBuffer → OpenGL ES 纹理
    ↓ getTextureID()
返回 Minecraft 渲染
```

## 优势

- 不需要任何 native `.so` 库
- 不需要修改 FCL 启动器源码
- 不需要 CI 编译 Chromium 源码
- 纯 Java/Kotlin 实现，兼容所有 Android 版本
- 最终产物是纯 Fabric mod JAR

## 局限

- 截图轮询模式，延迟较高（1-5 秒/帧）
- 无法实现实时网页交互（点击、滚动、输入）
- 仅适合显示静态或半静态网页内容（公告板、图文展示）

## 构建

```bash
cd proxy-web-mod
./gradlew build
```

产物：`build/libs/proxy-web-mod-1.0.0.jar`

## 许可证

LGPL-2.1-or-later（沿用上游 MCEF 许可证）
