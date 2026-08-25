package com.cinemamod.mcef.proxy;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 代理式轻量替代模组入口。
 *
 * 不依赖任何 CEF / Chromium native 库，通过网络调用 WebView 截图服务
 * 获取网页截图，转为 Minecraft 纹理，规避全部 native so 与 Android 安全限制。
 *
 * 替代方案原理：
 *   Minecraft Java (MCEF API) → HTTP 请求 → WebView 截图服务 → Bitmap → OpenGL ES 纹理
 *
 * 许可证：LGPL-2.1-or-later
 */
public class ProxyWebMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("proxy-web-mod");
    public static final String MOD_ID = "proxy-web-mod";

    private static ProxyAPI api;

    @Override
    public void onInitialize() {
        LOGGER.info("Proxy Web Mod 初始化中...");
        LOGGER.info("此模组不依赖 CEF native 库，使用 WebView 截图代理方案");

        api = new ProxyAPI();
        boolean ok = api.initialize();

        if (ok) {
            LOGGER.info("Proxy API 初始化成功，截图服务地址: {}", api.getScreenshotServiceUrl());
        } else {
            LOGGER.warn("Proxy API 初始化失败，将使用降级模式（静态占位纹理）");
            LOGGER.warn("请在 config/proxy-web-mod.json 中配置截图服务地址");
        }
    }

    public static ProxyAPI getAPI() {
        return api;
    }
}
