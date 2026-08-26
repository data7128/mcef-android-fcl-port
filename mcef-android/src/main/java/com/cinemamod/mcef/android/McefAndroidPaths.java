package com.cinemamod.mcef.android;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * MCEF Android 路径管理 — 沙箱目录创建 + PC→Android 路径映射。
 *
 * 背景：MCEF/CEF 的 PC 版本假设可以自由访问 /home、/tmp 等路径，
 * 但 Android 应用只能在自己的沙箱目录（/data/user/0/<pkg>/files）内读写。
 * 因此需要把所有 PC 风格路径映射到 Android 沙箱内的对应子目录。
 *
 * 映射规则：
 *   /home/user/.minecraft/... → /data/user/0/<pkg>/files/home/user/.minecraft/...
 *   /tmp/...                 → /data/user/0/<pkg>/files/tmp/...
 *   /etc/...                 → /data/user/0/<pkg>/files/etc/...
 *   绝对路径                 → 直接映射到沙箱根目录下
 *
 * 使用方式：在 MCEF 初始化最早期调用 ensureDirs() + exportEnv()。
 *
 * 兼容性说明：本类不直接依赖 android.* 类，
 * Context 参数使用 Object 类型，实际运行时由 FCL 注入 Android Context。
 * 这样 PC 环境 CI 也能编译通过。
 *
 * 许可证：LGPL-2.1-or-later
 */
public class McefAndroidPaths {
    private static final Logger LOGGER = LoggerFactory.getLogger("McefAndroidPaths");

    private static File sSandboxRoot;      // /data/user/0/<pkg>/files
    private static File sHomeDir;          // /data/user/0/<pkg>/files/home/user
    private static File sCefCacheDir;      // /data/user/0/<pkg>/files/home/user/.config/cef
    private static File sCefUserDataDir;   // /data/user/0/<pkg>/files/home/user/.config/cef_user_data
    private static File sTmpDir;           // /data/user/0/<pkg>/files/tmp
    private static File sNativeLibsDir;    // /data/user/0/<pkg>/files/native_libs/arm64-v8a
    private static boolean sInitialized = false;

    /**
     * 确保所有沙箱目录存在。
     * 必须在 MCEF 任何初始化之前调用。
     *
     * @param ctx Android Context（实际类型 android.content.Context，
     *            这里用 Object 以兼容 PC 编译环境）
     */
    public static synchronized void ensureDirs(Object ctx) {
        if (sInitialized) return;

        // 尝试从 Context 获取 filesDir（Android 环境）
        sSandboxRoot = getFilesDir(ctx);
        if (sSandboxRoot == null) {
            // PC 环境降级：用 user.home 下的临时目录
            String home = System.getProperty("user.home", "/tmp");
            sSandboxRoot = new File(home, ".mcef_android_sandbox");
            LOGGER.warn("未检测到 Android Context，使用降级沙箱目录: {}", sSandboxRoot.getAbsolutePath());
        }

        LOGGER.info("沙箱根目录: {}", sSandboxRoot.getAbsolutePath());

        // 关键目录列表
        sHomeDir = new File(sSandboxRoot, "home/user");
        sCefCacheDir = new File(sHomeDir, ".config/cef");
        sCefUserDataDir = new File(sHomeDir, ".config/cef_user_data");
        sTmpDir = new File(sSandboxRoot, "tmp");
        sNativeLibsDir = new File(sSandboxRoot, "native_libs/arm64-v8a");

        // 批量创建
        mkdirs(sHomeDir);
        mkdirs(sCefCacheDir);
        mkdirs(sCefUserDataDir);
        mkdirs(sTmpDir);
        mkdirs(sNativeLibsDir);

        // tmp 目录需要可执行权限（CEF 可能解压临时文件）
        if (sTmpDir.exists()) {
            sTmpDir.setReadable(true, false);
            sTmpDir.setWritable(true, false);
            sTmpDir.setExecutable(true, false);
        }

        sInitialized = true;
        LOGGER.info("沙箱目录初始化完成");
    }

    /**
     * 导出环境变量，供 CEF native 库读取。
     * 包括：HOME, TMPDIR, CEF_CACHE_PATH, CEF_USER_DATA_PATH
     */
    public static void exportEnv(Object ctx) {
        if (!sInitialized) {
            throw new IllegalStateException("ensureDirs() 必须先调用");
        }

        Map<String, String> env = new HashMap<>();
        env.put("HOME", sHomeDir.getAbsolutePath());
        env.put("TMPDIR", sTmpDir.getAbsolutePath());
        env.put("TEMP", sTmpDir.getAbsolutePath());
        env.put("CEF_CACHE_PATH", sCefCacheDir.getAbsolutePath());
        env.put("CEF_USER_DATA_PATH", sCefUserDataDir.getAbsolutePath());
        env.put("LD_LIBRARY_PATH", sNativeLibsDir.getAbsolutePath());

        // 通过反射设置环境变量（仅对当前 JVM 进程生效）
        setEnvVars(env);

        // 同时设置系统属性，方便 Java 侧读取
        System.setProperty("mcef.home", sHomeDir.getAbsolutePath());
        System.setProperty("mcef.tmp", sTmpDir.getAbsolutePath());
        System.setProperty("mcef.native_libs", sNativeLibsDir.getAbsolutePath());

        LOGGER.info("环境变量已导出: HOME={}", sHomeDir.getAbsolutePath());
    }

    /**
     * 将 PC 风格路径映射为 Android 沙箱内路径。
     *
     * 例：
     *   /home/user/.config/cef/cache
     *   → /data/user/0/<pkg>/files/home/user/.config/cef/cache
     *
     * JNI/native 侧拿到路径时调用：
     *   String pc_path = "/home/user/.config/cef/cache";
     *   String android_path = McefAndroidPaths.toSandboxPath(ctx, pc_path);
     *
     * @param ctx Android Context（Object 类型，兼容 PC 编译）
     * @param pcPath PC 风格绝对路径
     * @return Android 沙箱内的绝对路径
     */
    public static String toSandboxPath(Object ctx, String pcPath) {
        if (pcPath == null || pcPath.isEmpty()) {
            return pcPath;
        }

        if (!sInitialized) {
            ensureDirs(ctx);
        }

        // 已经是沙箱路径，直接返回
        if (pcPath.startsWith(sSandboxRoot.getAbsolutePath())) {
            return pcPath;
        }

        // 相对路径：直接拼到沙箱根目录
        if (!pcPath.startsWith("/")) {
            return new File(sSandboxRoot, pcPath).getAbsolutePath();
        }

        // 绝对路径：去掉开头的 /，拼到沙箱根目录下
        // /home/... → sandbox/home/...
        // /tmp/...  → sandbox/tmp/...
        String relative = pcPath.substring(1);
        File mapped = new File(sSandboxRoot, relative);

        // 确保父目录存在
        File parent = mapped.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        LOGGER.debug("路径映射: {} → {}", pcPath, mapped.getAbsolutePath());
        return mapped.getAbsolutePath();
    }

    // ===== Android Context 反射工具 =====

    /**
     * 从 Context 对象获取 filesDir。
     * 通过反射调用，避免直接依赖 android.* 类。
     */
    private static File getFilesDir(Object ctx) {
        if (ctx == null) return null;
        try {
            java.lang.reflect.Method method = ctx.getClass().getMethod("getFilesDir");
            Object result = method.invoke(ctx);
            if (result instanceof File) {
                return (File) result;
            }
        } catch (Exception e) {
            LOGGER.debug("获取 filesDir 失败（非 Android 环境）: {}", e.getMessage());
        }
        return null;
    }

    // ===== 工具方法 =====

    private static void mkdirs(File dir) {
        if (!dir.exists()) {
            boolean ok = dir.mkdirs();
            LOGGER.debug("创建目录 {}: {}", dir.getAbsolutePath(), ok ? "成功" : "失败");
        }
    }

    /**
     * 通过反射设置 JVM 环境变量。
     * 注意：这只影响当前进程内通过 System.getenv() 读取的值。
     * native 代码需要通过 JNI 单独传递路径。
     */
    @SuppressWarnings("unchecked")
    private static void setEnvVars(Map<String, String> vars) {
        try {
            // 方案1：获取 UnmodifiableMap 背后的实际 Map
            Map<String, String> env = System.getenv();
            Class<?> cl = env.getClass();
            Field field = cl.getDeclaredField("m");
            field.setAccessible(true);
            Map<String, String> writableEnv = (Map<String, String>) field.get(env);
            writableEnv.putAll(vars);
            LOGGER.info("环境变量已通过反射设置 (方案1)");
            return;
        } catch (Exception e) {
            LOGGER.debug("方案1失败，尝试方案2: {}", e.getMessage());
        }

        try {
            // 方案2：直接修改 processEnvironment
            Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
            Field theEnvField = pe.getDeclaredField("theUnmodifiableEnvironment");
            theEnvField.setAccessible(true);
            Map<String, String> unmodifiableEnv = (Map<String, String>) theEnvField.get(null);

            // 拿到背后的可写 Map
            Class<?> umCl = unmodifiableEnv.getClass().getSuperclass();
            Field mField = umCl.getDeclaredField("m");
            mField.setAccessible(true);
            Map<String, String> writable = (Map<String, String>) mField.get(unmodifiableEnv);
            writable.putAll(vars);
            LOGGER.info("环境变量已通过反射设置 (方案2)");
        } catch (Exception e) {
            LOGGER.warn("无法通过反射设置环境变量: {}", e.getMessage());
            // 降级：native 侧通过 JNI 参数传入路径
        }
    }

    // ===== Getter =====

    public static File getSandboxRoot() { return sSandboxRoot; }
    public static File getHomeDir() { return sHomeDir; }
    public static File getCefCacheDir() { return sCefCacheDir; }
    public static File getCefUserDataDir() { return sCefUserDataDir; }
    public static File getTmpDir() { return sTmpDir; }
    public static File getNativeLibsDir() { return sNativeLibsDir; }
    public static boolean isInitialized() { return sInitialized; }
}
