# MCEF Android — FCL 移植框架

MCEF (Minecraft Chromium Embedded Framework) 的 Android 移植基础设施。

> ⚠️ **这是移植框架，不是完整可运行的 mod**
>
> 本模块提供 Android 沙箱适配、原生库加载、路径映射等基础能力，
> 但**不包含 `libcef.so` 二进制文件**。完整浏览器功能需要：
> 1. 自行编译 arm64-v8a 版 `libcef.so`
> 2. 放入 `assets/mcef/native/arm64-v8a/` 目录
> 3. 完成 JNI 层的路径映射 hook

## 模块结构

```
mcef-android/
├── build.gradle                          # Fabric Loom 构建配置
└── src/main/
    ├── java/com/cinemamod/mcef/
    │   ├── MCEFMod.java                  # 【入口类】Fabric Mod 初始化
    │   ├── MCEFBrowser.java              # 【存根】MCEF 浏览器核心
    │   └── android/
    │       ├── McefAndroidPaths.java     # 沙箱目录 + 路径映射
    │       └── NativeLibraryLoader.java  # 原生库解压 + 加载
    └── resources/
        └── fabric.mod.json               # Mod 元数据
```

## 集成调用顺序

```java
ModInitializer.onInitialize() {
    McefAndroidPaths.ensureDirs(ctx);     // 1) 最早：建沙箱目录
    McefAndroidPaths.exportEnv(ctx);      // 2) 导环境变量
    NativeLibraryLoader.loadAll(ctx);     // 3) 解压 so + 改 java.library.path
    MCEFBrowser.initialize(ctx);          // 4) 走 mcef 原本的初始化
}
```

### 关键 3 个调用点

**1. Mod 入口（Java 侧）**

```java
// MCEFMod.java — onInitialize()
McefAndroidPaths.ensureDirs(ctx);     // 先建目录
McefAndroidPaths.exportEnv(ctx);      // 再导环境变量
NativeLibraryLoader.loadAll(ctx);     // 再解压 so + 改 java.library.path
MCEFBrowser.initialize(ctx);          // 最后走 mcef 原本逻辑
```

**2. JNI/native 侧路径映射**

在 mcef 原生库 CefClient 初始化、cef_browser_create、cef_load_url 等调用前：

```c
// 伪代码（JNI 层）
JNIEXPORT void JNICALL
Java_com_cinemamod_mcef_MCEFBrowser_nativeInitialize(JNIEnv *env, jobject obj, jstring cachePath) {
    // 获取 Java 侧的 McefAndroidPaths.toSandboxPath 方法
    jclass pathsCls = (*env)->FindClass(env, "com/cinemamod/mcef/android/McefAndroidPaths");
    jmethodID toSandbox = (*env)->GetStaticMethodID(env, pathsCls, "toSandboxPath",
        "(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;");

    // 所有 PC 风格路径都过一遍映射
    jstring mappedCache = (*env)->CallStaticObjectMethod(env, pathsCls, toSandbox, ctx, cachePath);

    // 用映射后的路径初始化 CEF
    const char *path = (*env)->GetStringUTFChars(env, mappedCache, 0);
    // cef_initialize(settings, ...);
}
```

**3. Context 获取**

FCL 环境下有两种方式获取 Android Context：

- **优先方式**：启动器侧注入 `MCEFMod.APP_CTX` 静态字段
- **降级方式**：通过 `ActivityThread.currentActivityThread().getApplication()` 反射获取

## 沙箱路径映射规则

| PC 路径 | Android 沙箱路径 |
|---------|------------------|
| `/home/user/.config/cef/` | `/data/user/0/<pkg>/files/home/user/.config/cef/` |
| `/home/user/.config/cef_user_data/` | `/data/user/0/<pkg>/files/home/user/.config/cef_user_data/` |
| `/tmp/` | `/data/user/0/<pkg>/files/tmp/` |
| 任意绝对路径 `/a/b/c` | `/data/user/0/<pkg>/files/a/b/c` |

## 已知限制

1. **必须自备 `libcef.so`** — GitHub Actions 免费 runner 内存不足，无法编译 CEF
2. **单进程模式** — Android 不支持 CEF 的多进程沙盒
3. **W^X 内存保护** — Android 10+ 要求可执行内存不可写，CEF JIT 可能受限
4. **无 Activity** — FCL mod 只能拿到 Application Context，拿不到 Activity
5. **GL4ES 限制** — OpenGL ES 纹理尺寸和格式有限制
6. **无原生输入** — 键盘/鼠标事件需要通过 JNI 手动转发

详细分析见 [CEF-PORT-FEASIBILITY.md](../docs/CEF-PORT-FEASIBILITY.md)

## 编译

本模块依赖 Android SDK，无法在标准 PC 环境下通过 `./gradlew build` 编译（会因缺少 `android.*` 类而失败）。

### Android 环境编译

需在 Android 项目中通过 Gradle 或 Android Studio 编译，或者使用 FCL 提供的 mod 编译工具链。

### PC 环境验证语法

如需在 PC 上验证 Java 语法，可注释掉 `android.*` 相关 import 进行检查。

## 许可证

LGPL-2.1-or-later
