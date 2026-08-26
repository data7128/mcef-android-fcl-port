package com.cinemamod.mcef.android;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 绕过 FCL/Pojav 原生库安全限制。
 *
 * FCL 默认只把 jar 内 so 解压到 instance/native_libs,
 * 第三方 jar 内的 so 无法直接被 System.loadLibrary 找到,
 * 出现 UnsatisfiedLinkError。
 *
 * 本类在 MCEF 初始化最早期阶段把 jar 内 /lib/arm64-v8a/*.so
 * 全部拷贝到 instance/native_libs/<arch>/,然后:
 *   1. 改写 java.library.path,addLibraryPath(nativeLibsDir/arch)
 *   2. 显式 System.load(so) 强制加载
 *   3. 之后 mcef 走自己的 osr/osr-linux 路径
 */
public final class NativeLibraryLoader {

    private static final String TAG = "MCEF-NativeLoader";
    private static final String ABI = "arm64-v8a";
    private static volatile boolean loaded = false;

    private NativeLibraryLoader() {}

    public static synchronized void loadAll(Context ctx) {
        if (loaded) return;
        McefAndroidPaths.ensureDirs(ctx);
        File out = McefAndroidPaths.nativeLibsDir(ctx);
        File archDir = new File(out, ABI);
        if (!archDir.exists() && !archDir.mkdirs()) {
            Log.w(TAG, "create arch dir failed: " + archDir);
        }
        McefAndroidPaths.exportEnv(ctx);

        Set<File> extracted = extractFromJars(ctx, archDir);

        // 强制 addLibraryPath,这样 System.loadLibrary 也能找到
        addLibraryPath(archDir.getAbsolutePath());

        // 优先尝试显式 load 解压出来的 .so
        for (File so : extracted) {
            try {
                System.load(so.getAbsolutePath());
                Log.i(TAG, "loaded: " + so.getName());
            } catch (UnsatisfiedLinkError e) {
                Log.w(TAG, "skip " + so.getName() + ": " + e.getMessage());
            }
        }
        loaded = true;
    }

    private static Set<File> extractFromJars(Context ctx, File outDir) {
        Set<File> result = new LinkedHashSet<>();
        String codePath = ctx.getApplicationInfo().sourceDir;
        if (codePath == null) return result;
        File jar = new File(codePath);
        if (!jar.isFile()) return result;
        try (JarFile jf = new JarFile(jar)) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (!name.endsWith(".so")) continue;
                if (!name.contains("/" + ABI + "/") && !name.startsWith("lib/" + ABI + "/")) {
                    // 也兼容 lib/ 顶层
                    if (!name.startsWith("lib/")) continue;
                }
                String base = name.substring(name.lastIndexOf('/') + 1);
                File target = new File(outDir, base);
                if (target.exists() && target.length() == e.getSize()) {
                    result.add(target);
                    continue;
                }
                try (InputStream in = jf.getInputStream(e);
                     OutputStream os = new FileOutputStream(target)) {
                    copy(in, os);
                }
                result.add(target);
                Log.d(TAG, "extracted: " + name + " -> " + target);
            }
        } catch (IOException io) {
            Log.e(TAG, "extract failed", io);
        }
        return result;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    /**
     * 反射把 archDir 追加到 java.library.path。
     * Android 上 dexopt 前设置才有效,务必在首次 loadLibrary 之前调用。
     */
    private static void addLibraryPath(String path) {
        try {
            String cur = System.getProperty("java.library.path", "");
            if (cur.contains(path)) return;
            String next = cur.isEmpty() ? path : cur + File.pathSeparator + path;
            System.setProperty("java.library.path", next);
            // 重置 ClassLoader 的 usr_paths / sys_paths
            ClassLoader cl = ClassLoader.getSystemClassLoader();
            try {
                java.lang.reflect.Field f = ClassLoader.class.getDeclaredField("usr_paths");
                f.setAccessible(true);
                Object v = f.get(cl);
                if (v instanceof String[]) {
                    String[] old = (String[]) v;
                    String[] nv = new String[old.length + 1];
                    System.arraycopy(old, 0, nv, 0, old.length);
                    nv[old.length] = path;
                    f.set(cl, nv);
                }
            } catch (NoSuchFieldException ignored) {
                // 非标准 ClassLoader,跳过
            }
        } catch (Throwable t) {
            Log.w(TAG, "addLibraryPath failed: " + t.getMessage());
        }
    }
}
