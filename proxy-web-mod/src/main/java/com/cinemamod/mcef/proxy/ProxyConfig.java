package com.cinemamod.mcef.proxy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模组配置管理。
 *
 * 配置文件位于 config/proxy-web-mod.json，包含：
 *   - serviceUrl: 截图服务地址（默认 http://127.0.0.1:28085）
 *   - refreshInterval: 截图刷新间隔（秒，默认 120）
 *   - maxTextureSize: 最大纹理尺寸（像素，默认 512，防止 GL4ES 崩溃）
 *   - requestTimeout: HTTP 请求超时（秒，默认 10）
 *
 * 许可证：LGPL-2.1-or-later
 */
public class ProxyConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ProxyConfig instance;

    public String serviceUrl = "http://127.0.0.1:28085";
    public int refreshInterval = 120;
    public int maxTextureSize = 512;
    public int requestTimeout = 10;

    public static ProxyConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        Path configPath = getConfigPath();
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                instance = GSON.fromJson(json, ProxyConfig.class);
                if (instance == null) {
                    instance = new ProxyConfig();
                }
                ProxyWebMod.LOGGER.info("配置已加载: {}", configPath);
            } catch (Exception e) {
                ProxyWebMod.LOGGER.warn("配置加载失败，使用默认值: {}", e.getMessage());
                instance = new ProxyConfig();
            }
        } else {
            instance = new ProxyConfig();
            save();
            ProxyWebMod.LOGGER.info("默认配置已创建: {}", configPath);
        }

        // 安全检查
        if (instance.serviceUrl == null || instance.serviceUrl.isEmpty()) {
            instance.serviceUrl = "http://127.0.0.1:28085";
        }
        if (instance.refreshInterval < 5) {
            instance.refreshInterval = 5;
        }
        if (instance.maxTextureSize < 64) {
            instance.maxTextureSize = 64;
        }
        if (instance.maxTextureSize > 4096) {
            instance.maxTextureSize = 4096;
        }
        if (instance.requestTimeout < 3) {
            instance.requestTimeout = 3;
        }
    }

    public static void save() {
        try {
            Path configPath = getConfigPath();
            Files.writeString(configPath, GSON.toJson(get()));
        } catch (IOException e) {
            ProxyWebMod.LOGGER.warn("配置保存失败: {}", e.getMessage());
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("proxy-web-mod.json");
    }
}
