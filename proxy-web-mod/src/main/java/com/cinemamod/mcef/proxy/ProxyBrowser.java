package com.cinemamod.mcef.proxy;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.opengl.GL11.*;

/**
 * 代理浏览器实例，模拟 MCEF 的 IBrowser 接口。
 *
 * 不使用 CEF 离屏渲染，而是通过 HTTP 请求 WebView 截图服务获取网页截图，
 * 然后上传到 OpenGL ES 纹理供 Minecraft 渲染。
 *
 * 生命周期：
 *   1. 创建时启动截图轮询线程
 *   2. 每隔 POLL_INTERVAL_MS 请求一次截图
 *   3. 截图到达后上传到 OpenGL 纹理
 *   4. close() 时停止轮询并释放纹理
 *
 * 许可证：LGPL-2.1-or-later
 */
public class ProxyBrowser {
    private static final Logger LOGGER = LoggerFactory.getLogger("proxy-web-mod");

    private final ProxyAPI api;
    private final String url;
    private final ProxyRenderer renderer;

    private int width = 1280;
    private int height = 720;
    private ScheduledFuture<?> pollFuture;
    private volatile boolean closed = false;

    public ProxyBrowser(ProxyAPI api, String url) {
        this.api = api;
        this.url = url;
        this.renderer = new ProxyRenderer();

        // 在 Minecraft 渲染线程上初始化纹理
        MinecraftClient.getInstance().execute(this::initTexture);

        // 启动截图轮询
        startPolling();
    }

    private void initTexture() {
        renderer.initialize(width, height);
        LOGGER.info("代理浏览器纹理初始化: {}x{}", width, height);
    }

    private void startPolling() {
        if (!api.isInitialized()) {
            LOGGER.warn("截图服务不可用，浏览器将显示占位纹理");
            // 仍然上传一次占位纹理
            requestScreenshot();
            return;
        }

        // 立即请求一次截图
        requestScreenshot();

        // 然后按间隔轮询
        pollFuture = api.getScheduler().scheduleAtFixedRate(
                this::requestScreenshot,
                ProxyAPI.POLL_INTERVAL_MS,
                ProxyAPI.POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        LOGGER.info("截图轮询已启动，间隔 {}ms", ProxyAPI.POLL_INTERVAL_MS);
    }

    private void requestScreenshot() {
        if (closed) return;

        CompletableFuture<byte[]> future = api.requestScreenshot(url, width, height);
        future.thenAccept(this::onScreenshotReceived);
    }

    private void onScreenshotReceived(byte[] rgbaData) {
        if (closed || rgbaData == null) return;

        // 必须在 Minecraft 渲染线程上传纹理
        MinecraftClient.getInstance().execute(() -> {
            if (!closed) {
                renderer.uploadFrame(rgbaData, width, height);
            }
        });
    }

    // ===== MCEF 兼容 API =====

    /**
     * 获取纹理 ID，供 Minecraft 渲染使用。
     * 与 MCEF IBrowser.getTextureID() 兼容。
     */
    public int getTextureID() {
        return renderer.getTextureID();
    }

    /**
     * 调整浏览器尺寸。
     * 与 MCEF IBrowser.resize() 兼容。
     */
    public void resize(int w, int h) {
        this.width = w;
        this.height = h;
        MinecraftClient.getInstance().execute(() -> {
            renderer.resize(w, h);
        });
        LOGGER.info("代理浏览器尺寸调整: {}x{}", w, h);
    }

    /**
     * 加载新 URL。
     * 与 MCEF IBrowser.loadURL() 兼容。
     */
    public void loadURL(String newUrl) {
        LOGGER.info("代理浏览器加载 URL: {}", newUrl);
    }

    /**
     * 关闭浏览器，释放资源。
     * 与 MCEF IBrowser.close() 兼容。
     */
    public void close() {
        closed = true;
        if (pollFuture != null) {
            pollFuture.cancel(false);
        }
        MinecraftClient.getInstance().execute(renderer::destroy);
        LOGGER.info("代理浏览器已关闭");
    }

    public String getUrl() {
        return url;
    }

    public boolean isClosed() {
        return closed;
    }
}
