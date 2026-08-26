package com.cinemamod.mcef;

import com.cinemamod.mcef.android.McefAndroidPaths;
import com.cinemamod.mcef.android.NativeLibraryLoader;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCEF Android 版 — Fabric Mod 入口类。
 *
 * 【集成调用顺序】
 * ModInitializer.onInitialize() {
 *     McefAndroidPaths.ensureDirs(ctx);     // 1) 最早：建沙箱目录
 *     McefAndroidPaths.exportEnv(ctx);      // 2) 导环境变量
 *     NativeLibraryLoader.loadAll(ctx);     // 3) 解压 so + 改 java.library.path
 *     MCEFBrowser.initialize(ctx);          // 4) 走 mcef 原本的初始化
 * }
 *
 * JNI/native 侧路径映射：
 *   在 mcef 原生库 CefClient 初始化、cef_browser_create、cef_load_url 等调用前：
 *     String pc_path = "/home/user/.config/cef/cache";
 *     String android_path = McefAndroidPaths.toSandboxPath(ctx, pc_path);
 *     // → /data/user/0/<pkg>/files/home/user/.config/cef/cache
 *
 * Context 说明：
 *   所有 Context 参数使用 Object 类型，避免直接依赖 android.* 类，
 *   保证 PC 环境 CI 能编译通过。
 *   实际运行时由 FCL 启动器注入 Android Context 对象。
 *
 * 许可证：LGPL-2.1-or-later
 */
public class MCEFMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcef-mod");

    /**
     * Android Application Context。
     * FCL 环境下由启动器在 mod 加载前注入。
     * 如果未注入，尝试通过反射从 ActivityThread 获取。
     *
     * 类型说明：实际运行时是 android.content.Context，
     * 这里声明为 Object 以兼容 PC 编译环境。
     */
    public static Object APP_CTX;

    @Override
    public void onInitialize() {
        LOGGER.info("MCEF Mod (Android) 初始化中...");

        Object ctx = getAppContext();
        if (ctx == null) {
            LOGGER.warn("无法获取 Android Context，使用 PC 降级模式");
            LOGGER.warn("FCL 环境下应在启动器侧注入 APP_CTX");
        } else {
            LOGGER.info("已获取 Application Context");
        }

        // ============================================================
        // 集成调用顺序（严格按此顺序，不可调换）
        // ============================================================

        // 1) 最早：确保沙箱目录、HOME/CEF 路径全到位
        LOGGER.info("[1/4] 初始化沙箱目录...");
        McefAndroidPaths.ensureDirs(ctx);
        McefAndroidPaths.exportEnv(ctx);

        // 2) 解压 jar 内 so 到 instance/native_libs/arm64-v8a，
        //    反射追加 java.library.path
        //    必须在 MCEFBrowser.create() / System.loadLibrary 之前调
        LOGGER.info("[2/4] 加载原生库...");
        boolean libsOk = NativeLibraryLoader.loadAll(ctx);
        if (!libsOk) {
            LOGGER.warn("原生库加载失败或部分失败，MCEF 可能无法正常工作");
            LOGGER.warn("检查: libcef.so 是否存在于 assets/mcef/native/arm64-v8a/");
        }

        // 3) 路径映射 hook: JNI/native 侧 open 之前过一遍
        //    在 mcef native 那边的 CefClient 初始化里加:
        //      String mapped = McefAndroidPaths.toSandboxPath(getAppContext(), nativePath);
        //    或者直接在 mcef 原生库(PC 端 /home 假设)那边在 open() 调用前过这个函数
        LOGGER.info("[3/4] 路径映射已就绪: McefAndroidPaths.toSandboxPath()");
        LOGGER.info("  沙箱根: {}", McefAndroidPaths.getSandboxRoot());
        LOGGER.info("  HOME: {}", McefAndroidPaths.getHomeDir());
        LOGGER.info("  CEF Cache: {}", McefAndroidPaths.getCefCacheDir());
        LOGGER.info("  Native Libs: {}", McefAndroidPaths.getNativeLibsDir());

        // 4) 走 mcef 原本的初始化
        LOGGER.info("[4/4] 初始化 MCEF 浏览器核心...");
        try {
            MCEFBrowser.initialize(ctx);
            LOGGER.info("MCEF ready (FCL sandbox mode, arm64-v8a, OSR/VirGL)");
        } catch (Exception e) {
            LOGGER.error("MCEF 初始化异常: {}", e.getMessage(), e);
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("MCEF 链接错误（缺少 native 库）: {}", e.getMessage());
            LOGGER.error("请确认 libcef.so 已放入 assets/mcef/native/arm64-v8a/");
        }
    }

    /**
     * 获取 Android Application Context。
     *
     * 优先级：
     *   1. 静态字段 APP_CTX（由 FCL 启动器注入）
     *   2. 反射从 ActivityThread 获取 Application Context
     *   3. 返回 null（PC 降级模式）
     *
     * @return Application Context，获取失败返回 null
     */
    private static Object getAppContext() {
        // 1. 优先用注入的 Context
        if (APP_CTX != null) {
            return APP_CTX;
        }

        // 2. 尝试从 ActivityThread 获取 Application Context
        // （无需 Activity，拿 Application 级别 Context 即可）
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method m = atCls.getMethod("currentActivityThread");
            Object activityThread = m.invoke(null);
            if (activityThread != null) {
                java.lang.reflect.Method getApp = atCls.getMethod("getApplication");
                Object app = getApp.invoke(activityThread);
                if (app != null) {
                    APP_CTX = app;
                    LOGGER.info("通过 ActivityThread 获取到 Application Context");
                    return APP_CTX;
                }
            }
        } catch (ClassNotFoundException e) {
            LOGGER.debug("非 Android 环境（找不到 ActivityThread），使用降级模式");
        } catch (Exception e) {
            LOGGER.debug("ActivityThread 方式获取 Context 失败: {}", e.getMessage());
        }

        return null;
    }
}
