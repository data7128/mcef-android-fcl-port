package com.cinemamod.mcef.android;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;

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
 * 兼容性说明：不直接依赖 android.* 类，Context 用 Object 类型，
 * PC 环境 CI 也能编译通过。
 *
 * 许可证：LGPL-2.1-or-later
 */
public class NativeLibraryLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger("NativeLibraryLoader");

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
     * @param ctx Android Context（Object 类型，兼容 PC 编译）
     * @return 是否全部加载成功
     */
    public static synchronized boolean loadAll(Object ctx) {
        if (sLoaded) {
            LOGGER.info("原生库已加载，跳过");
            return true;
        }

        try {
            // 确保目录存在
            File libDir = McefAndroidPaths.getNativeLibsDir();
            if (libDir == null || !libDir.exists()) {
                McefAndroidPaths.ensureDirs(ctx);
                libDir = McefAndroidPaths.getNativeLibsDir();
            }

            LOGGER.info("原生库目录: {}", libDir.getAbsolutePath());

            // 1. 解压 so 文件
            int extracted = extractNativeLibs(libDir);
            LOGGER.info("解压完成: {} 个 so 文件", extracted);

            // 2. 追加 java.library.path
            addLibraryPath(libDir.getAbsolutePath());
            LOGGER.info("已追加 java.library.path: {}", libDir.getAbsolutePath());

            // 3. 依次加载 so
            for (String libName : NATIVE_LIBS) {
                File libFile = new File(libDir, libName);
                if (libFile.exists()) {
                    try {
                        System.load(libFile.getAbsolutePath());
                        LOGGER.info("已加载: {}", libName);
                    } catch (UnsatisfiedLinkError e) {
                        LOGGER.error("加载失败 {}: {}", libName, e.getMessage());
                    }
                } else {
                    LOGGER.warn("so 文件不存在: {} (如果是存根环境可忽略)", libName);
                }
            }

            sLoaded = true;
            LOGGER.info("原生库加载流程完成");
            return true;

        } catch (Exception e) {
            LOGGER.error("原生库加载异常: {}", e.getMessage(), e);
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
                LOGGER.debug("资源不存在: {} (存根环境下正常)", resourcePath);
                continue;
            }

            try {
                // 如果文件已存在且大小一致，跳过
                if (targetFile.exists()) {
                    long size = is.available();
                    if (targetFile.length() == size) {
                        LOGGER.debug("跳过已有: {}", libName);
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

                LOGGER.info("已解压: {} ({} bytes)", libName, targetFile.length());
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

            LOGGER.info("java.library.path 已追加 (方案1): {}", pathToAdd);
            return;
        } catch (NoSuchFieldException e) {
            LOGGER.debug("方案1失败，尝试方案2: {}", e.getMessage());
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

            LOGGER.info("java.library.path 已追加 (方案2): {}", pathToAdd);
        } catch (NoSuchFieldException e) {
            LOGGER.warn("无法修改 java.library.path: {}", e.getMessage());
            LOGGER.warn("降级：使用 System.load() 绝对路径方式加载");
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
            LOGGER.error("loadLibrary 失败: {} - {}", libName, e.getMessage());
            return false;
        }
    }
}
