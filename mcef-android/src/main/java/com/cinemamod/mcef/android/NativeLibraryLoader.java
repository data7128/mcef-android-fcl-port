package com.cinemamod.mcef.android;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 原生库加载器 — 从 jar 资源中解压 .so 到沙箱目录，
 * 并通过反射追加到 java.library.path。
 *
 * 背景：MCEF/CEF 需要多个 native 库（libcef.so、libjawt.so 等），
 * 但 Fabric mod 的 jar 包无法直接被 System.loadLibrary() 找到。
 * 因此需要：
 *   1. 将 so 文件从 jar 资源复制到应用可写目录
 *   2. 通过反射修改 java.library.path，让 JVM 能找到这些 so
 *   3. 设置文件权限为可执行
 *
 * 使用方式：NativeLibraryLoader.loadAll(ctx)
 * 必须在 MCEFBrowser.create() / System.loadLibrary 之前调用。
 *
 * 资源目录约定：
 *   assets/mcef/native/arm64-v8a/libcef.so
 *   assets/mcef/native/arm64-v8a/libjawt.so
 *   ...
 *
 * 许可证：LGPL-2.1-or-later
 */
public class NativeLibraryLoader {
    private static final String TAG = "NativeLibraryLoader";

    // jar 内 so 文件所在的资源路径前缀
    private static final String ASSET_PREFIX = "/assets/mcef/native/arm64-v8a/";

    // 需要加载的 so 列表（按依赖顺序）
    private static final String[] NATIVE_LIBS = {
        "libcef.so",
        "libjawt.so",
        "libmcef.so",
    };

    private static boolean sLoaded = false;

    /**
     * 加载全部原生库。
     * 1. 从 jar 资源解压 .so 到 native_libs 目录
     * 2. 反射追加 java.library.path
     * 3. 依次 loadLibrary
     *
     * @param ctx Android Context
     * @return 是否全部加载成功
     */
    public static synchronized boolean loadAll(Context ctx) {
        if (sLoaded) {
            Log.i(TAG, "原生库已加载，跳过");
            return true;
        }

        try {
            // 确保目录存在
            File libDir = McefAndroidPaths.getNativeLibsDir();
            if (libDir == null || !libDir.exists()) {
                McefAndroidPaths.ensureDirs(ctx);
                libDir = McefAndroidPaths.getNativeLibsDir();
            }

            Log.i(TAG, "原生库目录: " + libDir.getAbsolutePath());

            // 1. 解压 so 文件
            int extracted = extractNativeLibs(libDir);
            Log.i(TAG, "解压完成: " + extracted + " 个 so 文件");

            // 2. 追加 java.library.path
            addLibraryPath(libDir.getAbsolutePath());
            Log.i(TAG, "已追加 java.library.path: " + libDir.getAbsolutePath());

            // 3. 依次加载 so
            for (String libName : NATIVE_LIBS) {
                File libFile = new File(libDir, libName);
                if (libFile.exists()) {
                    try {
                        System.load(libFile.getAbsolutePath());
                        Log.i(TAG, "已加载: " + libName);
                    } catch (UnsatisfiedLinkError e) {
                        Log.e(TAG, "加载失败 " + libName + ": " + e.getMessage());
                    }
                } else {
                    Log.w(TAG, "so 文件不存在: " + libName + " (如果是存根环境可忽略)");
                }
            }

            sLoaded = true;
            Log.i(TAG, "原生库加载流程完成");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "原生库加载异常: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 从 jar 资源中解压所有 .so 文件到目标目录。
     * 已存在且大小一致的跳过。
     */
    private static int extractNativeLibs(File targetDir) throws IOException {
        int count = 0;

        for (String libName : NATIVE_LIBS) {
            String resourcePath = ASSET_PREFIX + libName;
            File targetFile = new File(targetDir, libName);

            InputStream is = NativeLibraryLoader.class.getResourceAsStream(resourcePath);
            if (is == null) {
                Log.d(TAG, "资源不存在: " + resourcePath + " (存根环境下正常)");
                continue;
            }

            try {
                // 如果文件已存在且大小一致，跳过
                if (targetFile.exists()) {
                    long size = is.available();
                    if (targetFile.length() == size) {
                        Log.d(TAG, "跳过已有: " + libName);
                        count++;
                        continue;
                    }
                }

                // 复制文件
                FileOutputStream fos = new FileOutputStream(targetFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
                fos.close();

                // 设置权限
                targetFile.setReadable(true, false);
                targetFile.setWritable(true, false);
                targetFile.setExecutable(true, false);

                Log.i(TAG, "已解压: " + libName + " (" + targetFile.length() + " bytes)");
                count++;
            } finally {
                is.close();
            }
        }

        return count;
    }

    /**
     * 通过反射将指定路径追加到 java.library.path。
     * 这样后续的 System.loadLibrary() 可以找到我们解压的 so。
     */
    @SuppressWarnings("unchecked")
    private static void addLibraryPath(String pathToAdd) throws Exception {
        try {
            // 方案1：修改 usr_paths（用户指定的库路径）
            Field usrPathsField = ClassLoader.class.getDeclaredField("usr_paths");
            usrPathsField.setAccessible(true);
            String[] paths = (String[]) usrPathsField.get(null);

            // 检查是否已存在
            for (String p : paths) {
                if (p.equals(pathToAdd)) {
                    return;
                }
            }

            // 追加新路径
            String[] newPaths = new String[paths.length + 1];
            System.arraycopy(paths, 0, newPaths, 0, paths.length);
            newPaths[paths.length] = pathToAdd;
            usrPathsField.set(null, newPaths);

            Log.i(TAG, "java.library.path 已追加 (方案1): " + pathToAdd);
            return;
        } catch (NoSuchFieldException e) {
            Log.d(TAG, "方案1失败，尝试方案2: " + e.getMessage());
        }

        try {
            // 方案2：修改 sys_paths
            Field sysPathsField = ClassLoader.class.getDeclaredField("sys_paths");
            sysPathsField.setAccessible(true);
            String[] paths = (String[]) sysPathsField.get(null);

            for (String p : paths) {
                if (p.equals(pathToAdd)) {
                    return;
                }
            }

            String[] newPaths = new String[paths.length + 1];
            System.arraycopy(paths, 0, newPaths, 0, paths.length);
            newPaths[paths.length] = pathToAdd;
            sysPathsField.set(null, newPaths);

            Log.i(TAG, "java.library.path 已追加 (方案2): " + pathToAdd);
        } catch (NoSuchFieldException e) {
            Log.w(TAG, "无法修改 java.library.path: " + e.getMessage());
            Log.w(TAG, "降级：使用 System.load() 绝对路径方式加载");
            // 降级方案：loadAll 中已经用 System.load(绝对路径) 加载了
        }
    }

    /**
     * 检查原生库是否已加载。
     */
    public static boolean isLoaded() {
        return sLoaded;
    }

    /**
     * 手动加载单个 so（用于延迟加载的场景）。
     */
    public static boolean loadLibrary(String libName) {
        try {
            System.loadLibrary(libName);
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "loadLibrary 失败: " + libName + " - " + e.getMessage());
            return false;
        }
    }
}
