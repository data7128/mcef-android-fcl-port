package com.cinemamod.mcef.proxy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP 截图请求工具类。
 *
 * 向截图服务发送 GET 请求，获取网页 PNG 图片。
 * 截图服务由 FCL 内置的 WebViewScreenshotService 提供（localhost:28085）。
 *
 * 请求格式：
 *   GET http://127.0.0.1:28085/screenshot?url=<URL>&width=<W>&height=<H>
 * 响应格式：
 *   image/png (PNG 字节流)
 *
 * 降级模式：
 *   服务不可用时返回 null，调用方显示占位纹理。
 *
 * 许可证：LGPL-2.1-or-later
 */
public class ProxyAPI {
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 请求网页截图（PNG 格式）。
     *
     * @param url     目标网页 URL
     * @param width   截图宽度
     * @param height  截图高度
     * @return CompletableFuture，包含 PNG 字节数据（失败时返回 null）
     */
    public static CompletableFuture<byte[]> requestScreenshotPNG(String url, int width, int height) {
        String serviceUrl = ProxyConfig.get().serviceUrl;
        int timeout = ProxyConfig.get().requestTimeout;

        String encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8);
        String requestUrl = String.format("%s/screenshot?url=%s&width=%d&height=%d",
                serviceUrl, encodedUrl, width, height);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .timeout(Duration.ofSeconds(timeout))
                .header("Accept", "image/png")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return response.body();
                    } else {
                        ProxyWebMod.LOGGER.warn("截图服务返回: HTTP {}", response.statusCode());
                        return null;
                    }
                })
                .exceptionally(e -> {
                    ProxyWebMod.LOGGER.warn("截图请求失败: {} - {}", url, e.getMessage());
                    return null;
                });
    }

    /**
     * 发送模拟点击请求，返回点击后的新截图。
     *
     * 请求格式：
     *   GET /click?url=<URL>&x=<X>&y=<Y>&width=<W>&height=<H>
     * 响应格式：
     *   image/png (点击后页面的新截图)
     *
     * @param url    目标网页 URL
     * @param x      点击X像素坐标
     * @param y      点击Y像素坐标
     * @param width  截图宽度
     * @param height 截图高度
     * @return CompletableFuture，包含 PNG 字节数据（失败时返回 null）
     */
    public static CompletableFuture<byte[]> sendClick(String url, int x, int y, int width, int height) {
        String serviceUrl = ProxyConfig.get().serviceUrl;
        int timeout = ProxyConfig.get().requestTimeout;

        String encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8);
        String requestUrl = String.format("%s/click?url=%s&x=%d&y=%d&width=%d&height=%d",
                serviceUrl, encodedUrl, x, y, width, height);

        ProxyWebMod.LOGGER.info("发送点击请求: url={}, ({}, {})", url, x, y);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .timeout(Duration.ofSeconds(timeout))
                .header("Accept", "image/png")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        ProxyWebMod.LOGGER.info("点击请求成功: {} bytes", response.body().length);
                        return response.body();
                    } else {
                        ProxyWebMod.LOGGER.warn("点击服务返回: HTTP {}", response.statusCode());
                        return null;
                    }
                })
                .exceptionally(e -> {
                    ProxyWebMod.LOGGER.warn("点击请求失败: {} - {}", url, e.getMessage());
                    return null;
                });
    }

    /**
     * 探测截图服务是否可用。
     *
     * @return true 表示服务可用
     */
    public static CompletableFuture<Boolean> probeService() {
        String serviceUrl = ProxyConfig.get().serviceUrl;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serviceUrl + "/health"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> response.statusCode() == 200)
                .exceptionally(e -> {
                    ProxyWebMod.LOGGER.warn("截图服务探测失败: {}", e.getMessage());
                    return false;
                });
    }
}
