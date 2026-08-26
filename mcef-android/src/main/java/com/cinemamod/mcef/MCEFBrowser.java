package com.cinemamod.mcef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cinemamod.mcef.android.McefAndroidPaths;

/**
 * MCEF 浏览器核心 — MCEF 原本的初始化入口。
 *
 * 【这是存根类】
 * 真实的 MCEFBrowser 包含完整的 CEF 初始化、浏览器创建、纹理上传等逻辑。
 * 这里只保留 Android 移植相关的初始化骨架，用于展示集成调用顺序。
 *
 * 真实 MCEF 源码结构（参考）：
 *   MCEFBrowser.initialize()   — 全局初始化 CEF
 *   MCEFBrowser.create()       — 创建单个浏览器实例
 *   MCEFBrowser.close()        — 关闭浏览器
 *   MCEFRenderer               — OpenGL 纹理渲染
 *
 * Android 移植改动点：
 *   1. initialize() 增加 Context 参数（Object 类型，避免直接依赖 android.*）
 *   2. 所有文件路径通过 McefAndroidPaths.toSandboxPath() 转换
 *   3. 禁用 CEF 的沙盒进程（Android 不支持）
 *   4. 使用 OSR（离屏渲染）模式，配合 GL 纹理上传
 *
 * 许可证：LGPL-2.1-or-later
 */
public class MCEFBrowser {
    private static final Logger LOGGER = LoggerFactory.getLogger("MCEFBrowser");

    private static boolean sInitialized = false;
    private static Object sAppContext;
    private static String sPackageName = "unknown";

    /**
     * 初始化 MCEF / CEF 全局环境。
     *
     * 【PC 原版签名】MCEFBrowser.initialize() — 无参数，默认用系统路径
     * 【Android 版签名】MCEFBrowser.initialize(Object ctx) — 需要 Application Context
     *
     * 调用前必须完成：
     *   1. McefAndroidPaths.ensureDirs(ctx)
     *   2. McefAndroidPaths.exportEnv(ctx)
     *   3. NativeLibraryLoader.loadAll(ctx)
     *
     * @param ctx Android Application Context（Object 类型，实际为 android.content.Context）
     */
    public static synchronized void initialize(Object ctx) {
        if (sInitialized) {
            LOGGER.info("MCEF 已初始化，跳过");
            return;
        }

        sAppContext = ctx;
        sPackageName = getPackageName(ctx);
        LOGGER.info("MCEFBrowser.initialize() 开始...");
        LOGGER.info("包名: {}", sPackageName);

        // ========== Android 特有：路径映射 ==========
        // 真实 MCEF 中，这里会调用 JNI 初始化 CEF，
        // 传入的 cache_path、user_data_path 等参数必须是沙箱内路径。
        //
        // 示例（伪代码）：
        //   CefSettings settings = new CefSettings();
        //   settings.cache_path = McefAndroidPaths.toSandboxPath(ctx,
        //       "/home/user/.config/cef/cache");
        //   settings.user_data_path = McefAndroidPaths.toSandboxPath(ctx,
        //       "/home/user/.config/cef_user_data");
        //   settings.no_sandbox = true;        // Android 必须关沙盒
        //   settings.single_process = true;    // Android 建议单进程
        //   CefInitialize.initialize(settings);

        LOGGER.info("CEF 设置（沙箱路径）:");
        LOGGER.info("  cache_path: {}", McefAndroidPaths.getCefCacheDir());
        LOGGER.info("  user_data_path: {}", McefAndroidPaths.getCefUserDataDir());
        LOGGER.info("  no_sandbox: true");
        LOGGER.info("  single_process: true");

        // ========== JNI 初始化 ==========
        // 真实实现中这里会调用 native 方法：
        //   nativeInitialize(cachePath, userDataPath, ...);
        //
        // JNI/native 侧关键改动：
        //   1. 所有 fopen/open 调用前，路径过一遍 toSandboxPath
        //   2. 禁用 setuid/setgid 相关代码（Android 无此机制）
        //   3. W^X 内存保护：Android 10+ 要求可执行内存不可写
        //   4. 信号处理：CEF 自定义信号处理可能与 ART 冲突

        sInitialized = true;
        LOGGER.info("MCEF 初始化完成（Android 沙箱模式，arm64-v8a，OSR/VirGL）");
    }

    /**
     * 创建浏览器实例。
     * 对应 PC 版 MCEFBrowser.create(url, width, height)。
     *
     * @param url 初始 URL
     * @param width 浏览器宽度
     * @param height 浏览器高度
     * @return 浏览器实例 ID
     */
    public static int create(String url, int width, int height) {
        if (!sInitialized) {
            throw new IllegalStateException("MCEFBrowser.initialize() 必须先调用");
        }

        LOGGER.info("创建浏览器: {} ({}x{})", url, width, height);

        // 真实实现：调用 nativeCreate(url, width, height)
        // 返回浏览器句柄 ID，后续通过 ID 操作

        return 0; // 存根：返回假 ID
    }

    /**
     * 关闭指定浏览器。
     */
    public static void close(int browserId) {
        LOGGER.info("关闭浏览器: {}", browserId);
        // 真实实现：调用 nativeClose(browserId)
    }

    /**
     * 获取浏览器当前帧的像素数据（OSR 模式）。
     * 用于上传到 OpenGL 纹理。
     */
    public static byte[] getPixels(int browserId) {
        // 真实实现：调用 nativeGetPixels(browserId)
        // 返回 RGBA 格式的像素数组
        return new byte[0]; // 存根
    }

    /**
     * 全局关闭 MCEF。
     */
    public static synchronized void shutdown() {
        if (!sInitialized) return;

        LOGGER.info("MCEF 关闭中...");
        // 真实实现：调用 nativeShutdown()
        sInitialized = false;
        LOGGER.info("MCEF 已关闭");
    }

    public static boolean isInitialized() { return sInitialized; }
    public static Object getAppContext() { return sAppContext; }

    /**
     * 从 Context 获取包名（反射调用，避免依赖 android.*）。
     */
    private static String getPackageName(Object ctx) {
        if (ctx == null) return "unknown";
        try {
            java.lang.reflect.Method method = ctx.getClass().getMethod("getPackageName");
            Object result = method.invoke(ctx);
            if (result instanceof String) {
                return (String) result;
            }
        } catch (Exception e) {
            LOGGER.debug("获取包名失败（非 Android 环境）: {}", e.getMessage());
        }
        return "unknown";
    }
}
