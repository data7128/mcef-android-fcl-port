package com.cinemamod.mcef.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 代理式 API 层，模拟 MCEF 的 API 接口。
 *
 * 提供与 MCEF 兼容的 createBrowser / resize / getTextureID 接口，
 * 但底层实现替换为 HTTP 截图服务调用。
 *
 * 默认截图服务地址指向 FCL 内置 WebView 截图服务 (localhost:28085)。
 * 如果 FCL 未修改（未内置截图服务），可配置为远程服务地址。
 *
 * 许可证：LGPL-2.1-or-later
 */
public class ProxyAPI {
    private static final Logger LOGGER = LoggerFactory.getLogger("proxy-web-mod");

    /** 默认截图服务地址：FCL 内置 WebView 截图服务 */
    private static final String DEFAULT_SERVICE_URL = "http://127.0.0.1:28085";

    /** 截图轮询间隔（毫秒） */
    static final long POLL_INTERVAL_MS = 2000;

    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private String screenshotServiceUrl;
    private boolean initialized = false;

    public ProxyAPI() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "proxy-web-mod-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 初始化代理 API。
     * 检测截图服务是否可用。
     */
    public boolean initialize() {
        // 读取配置（【待验证】配置文件路径在 Pojav 环境下的实际位置）
        String configPath = System.getenv("POJAV_GAME_DIR");
        if (configPath == null) {
            configPath = System.getProperty("user.dir");
        }

        // 默认服务地址可被环境变量覆盖
        screenshotServiceUrl = System.getenv("PROXY_WEB_SERVICE_URL");
        if (screenshotServiceUrl == null || screenshotServiceUrl.isEmpty()) {
            screenshotServiceUrl = DEFAULT_SERVICE_URL;
        }

        // 探测截图服务是否可用
        try {
            HttpRequest probe = HttpRequest.newBuilder()
                    .uri(URI.create(screenshotServiceUrl + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(probe, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                initialized = true;
                LOGGER.info("截图服务探测成功: {}", screenshotServiceUrl);
            } else {
                LOGGER.warn("截图服务返回非 200 状态码: {}", resp.statusCode());
            }
        } catch (Exception e) {
            LOGGER.warn("截图服务不可用: {} (原因: {})", screenshotServiceUrl, e.getMessage());
            LOGGER.warn("模组将以降级模式运行，浏览器将显示占位纹理");
        }

        return initialized;
    }

    /**
     * 创建代理浏览器实例。
     * 与 MCEF API 兼容。
     *
     * @param url 要加载的网页 URL
     * @return 代理浏览器实例
     */
    public ProxyBrowser createBrowser(String url) {
        LOGGER.info("创建代理浏览器: url={}", url);
        return new ProxyBrowser(this, url);
    }

    /**
     * 请求网页截图。
     *
     * @param url     网页 URL
     * @param width   截图宽度
     * @param height  截图高度
     * @return 包含 RGBA 字节数据的 CompletableFuture
     */
    public CompletableFuture<byte[]> requestScreenshot(String url, int width, int height) {
        if (!initialized) {
            // 降级模式：返回纯色占位纹理
            return CompletableFuture.completedFuture(generatePlaceholderTexture(width, height));
        }

        String requestBody = String.format(
                "{\"url\":\"%s\",\"width\":%d,\"height\":%d}",
                url, width, height
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(screenshotServiceUrl + "/screenshot"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return response.body();
                    } else {
                        LOGGER.warn("截图请求失败: status={}", response.statusCode());
                        return generatePlaceholderTexture(width, height);
                    }
                })
                .exceptionally(e -> {
                    LOGGER.warn("截图请求异常: {}", e.getMessage());
                    return generatePlaceholderTexture(width, height);
                });
    }

    /**
     * 生成纯色占位纹理（降级模式）。
     */
    private byte[] generatePlaceholderTexture(int width, int height) {
        // 深灰色 RGBA 纹理
        byte[] data = new byte[width * height * 4];
        for (int i = 0; i < width * height; i++) {
            data[i * 4] = 0x33;     // R
            data[i * 4 + 1] = 0x33; // G
            data[i * 4 + 2] = 0x33; // B
            data[i * 4 + 3] = (byte) 0xFF; // A
        }
        return data;
    }

    public String getScreenshotServiceUrl() {
        return screenshotServiceUrl;
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 关闭 API，释放资源。
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        LOGGER.info("Proxy API 已关闭");
    }
}
