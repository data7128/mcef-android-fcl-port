package com.cinemamod.mcef.android;

import android.content.Context;
import android.os.Environment;

import java.io.File;

/**
 * FCL 安卓沙箱路径适配。
 * 原 PC CEF 假定 /home/user、~/.config、~/.cache 等固定路径,
 * 在 FCL/Pojav 沙箱下全部需要重定向到 Android 私有目录。
 */
public final class McefAndroidPaths {

    private McefAndroidPaths() {}

    public static File sandboxRoot(Context ctx) {
        return ctx.getFilesDir().getAbsoluteFile();
    }

    public static File cacheRoot(Context ctx) {
        return ctx.getCacheDir().getAbsoluteFile();
    }

    public static File externalRoot(Context ctx) {
        File f = ctx.getExternalFilesDir(null);
        return f != null ? f : cacheRoot(ctx);
    }

    public static File homeDir(Context ctx) {
        return new File(sandboxRoot(ctx), "home");
    }

    public static File configDir(Context ctx) {
        return new File(homeDir(ctx), ".config");
    }

    public static File cefUserDataDir(Context ctx) {
        return new File(configDir(ctx), "cef");
    }

    public static File cefCacheDir(Context ctx) {
        return new File(cacheRoot(ctx), "cef_cache");
    }

    public static File cefBundleDir(Context ctx) {
        File f = new File(externalRoot(ctx), "cef");
        return f.exists() ? f : new File(sandboxRoot(ctx), "cef");
    }

    public static File nativeLibsDir(Context ctx) {
        return new File(sandboxRoot(ctx), "instance/native_libs");
    }

    public static File mcefRuntimeDir(Context ctx) {
        return new File(sandboxRoot(ctx), "mcef");
    }

    public static void ensureDirs(Context ctx) {
        for (File d : new File[]{
                homeDir(ctx), configDir(ctx), cefUserDataDir(ctx),
                cefCacheDir(ctx), cefBundleDir(ctx), nativeLibsDir(ctx),
                mcefRuntimeDir(ctx)
        }) {
            if (!d.exists() && !d.mkdirs()) {
                // 静默失败,后续 IO 会暴露
            }
        }
    }

    /**
     * 把 PC 风格路径(/home/xxx, ~/yyy)映射到沙箱内对应位置。
     * JNI 侧 / native 侧拿到的路径先过这里再 open。
     */
    public static String toSandboxPath(Context ctx, String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        String r = raw;
        File home = homeDir(ctx);
        if (r.startsWith("/home/")) {
            int third = r.indexOf('/', 6);
            if (third > 0) {
                String user = r.substring(6, third);
                File userHome = new File(home, user);
                return new File(userHome, r.substring(third + 1)).getAbsolutePath();
            }
        }
        if (r.startsWith("~/")) {
            return new File(home, r.substring(2)).getAbsolutePath();
        }
        if (r.startsWith("/root/")) {
            return new File(home, "root/" + r.substring(6)).getAbsolutePath();
        }
        if (r.startsWith("/tmp/")) {
            return new File(cacheRoot(ctx), r.substring(5)).getAbsolutePath();
        }
        if (r.startsWith("/sdcard/") || r.startsWith("/storage/")) {
            return r;
        }
        if (r.startsWith("/data/") || r.startsWith("/system/") || r.startsWith("/vendor/")) {
            return r;
        }
        if (r.charAt(0) == '/') {
            File redirected = new File(mcefRuntimeDir(ctx), r.substring(1));
            return redirected.getAbsolutePath();
        }
        return r;
    }

    public static void exportEnv(Context ctx) {
        ensureDirs(ctx);
        File home = homeDir(ctx);
        File config = configDir(ctx);
        File cache = cefCacheDir(ctx);
        File tmp = new File(cacheRoot(ctx), "tmp");
        if (!tmp.exists()) tmp.mkdirs();
        set("HOME", home.getAbsolutePath());
        set("XDG_CONFIG_HOME", config.getAbsolutePath());
        set("XDG_CACHE_HOME", cache.getAbsolutePath());
        set("XDG_DATA_HOME", new File(home, ".local/share").getAbsolutePath());
        set("TMPDIR", tmp.getAbsolutePath());
        set("TMP", tmp.getAbsolutePath());
        set("TEMP", tmp.getAbsolutePath());
        // CEF 特定
        set("CEF_USER_DATA_DIR", cefUserDataDir(ctx).getAbsolutePath());
        set("CEF_CACHE_DIR", cache.getAbsolutePath());
    }

    private static void set(String k, String v) {
        try {
            System.setProperty(k, v);
        } catch (Throwable ignored) {}
        // envp 注入在 NativeLibraryLoader 启动子进程时处理
    }
}
