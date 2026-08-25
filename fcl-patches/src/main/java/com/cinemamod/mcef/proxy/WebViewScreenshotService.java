package com.cinemamod.mcef.proxy;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * FCL 内置 WebView 截图服务。
 *
 * 此类运行在 FCL 的 Android App 进程中（拥有 Context），
 * 监听 localhost:28085，接收截图请求，使用 Android 系统 WebView 加载网页并截取 Bitmap。
 *
 * 注意：此类需要在 FCL 启动器源码中集成（在 JVMActivity.onCreate 中启动），
 * 不属于纯 mod 部分。放在本仓库中作为参考实现和 FCL 补丁的源文件。
 *
 * API:
 *   GET  /health       → 200 OK
 *   POST /screenshot   → {"url":"...","width":W,"height":H} → image/png bytes (RGBA)
 *
 * 许可证：LGPL-2.1-or-later
 */
public class WebViewScreenshotService {
    private static final Logger LOGGER = LoggerFactory.getLogger("proxy-web-mod");
    private static final int PORT = 28085;

    private final Context context;
    private HttpServer server;
    private WebView webView;

    public WebViewScreenshotService(Context context) {
        this.context = context;
    }

    public void start() throws IOException {
        // 在主线程创建 WebView
        ((Activity) context).runOnUiThread(() -> {
            webView = new WebView(context);
            webView.layout(0, 0, 1920, 1080);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            // 【待验证】WebView 在后台无窗口时的渲染行为
        });

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/health", new HealthHandler());
        server.createContext("/screenshot", new ScreenshotHandler());
        server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor());
        server.start();

        LOGGER.info("WebView 截图服务已启动: http://127.0.0.1:{}", PORT);
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
        if (webView != null) {
            ((Activity) context).runOnUiThread(() -> {
                webView.destroy();
                webView = null;
            });
        }
        LOGGER.info("WebView 截图服务已停止");
    }

    /**
     * 截取指定 URL 的网页为 RGBA 字节数组。
     */
    public byte[] captureScreenshot(String url, int width, int height) {
        final CountDownLatch latch = new CountDownLatch(1);
        final byte[][] result = {null};

        ((Activity) context).runOnUiThread(() -> {
            webView.layout(0, 0, width, height);

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String finishedUrl) {
                    // 页面加载完成后截取 Bitmap
                    Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bmp);
                    view.draw(canvas);

                    // 转为 RGBA 字节数组
                    result[0] = bitmapToRGBA(bmp);
                    latch.countDown();
                }
            });

            webView.loadUrl(url);
        });

        // 等待截图完成，最多 10 秒
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                LOGGER.warn("截图超时: url={}", url);
                return generatePlaceholder(width, height);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return generatePlaceholder(width, height);
        }

        return result[0] != null ? result[0] : generatePlaceholder(width, height);
    }

    /**
     * 将 Bitmap 转为 RGBA 字节数组。
     * Android Bitmap.Config.ARGB_8888 的内存布局为 RGBA（小端序）。
     */
    private byte[] bitmapToRGBA(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            // Android int 像素格式: 0xAARRGGBB
            rgba[i * 4]     = (byte) ((pixel >> 16) & 0xFF); // R
            rgba[i * 4 + 1] = (byte) ((pixel >> 8) & 0xFF);  // G
            rgba[i * 4 + 2] = (byte) (pixel & 0xFF);         // B
            rgba[i * 4 + 3] = (byte) ((pixel >> 24) & 0xFF); // A
        }
        return rgba;
    }

    private byte[] generatePlaceholder(int width, int height) {
        byte[] data = new byte[width * height * 4];
        for (int i = 0; i < width * height; i++) {
            data[i * 4] = 0x33;
            data[i * 4 + 1] = 0x33;
            data[i * 4 + 2] = 0x33;
            data[i * 4 + 3] = (byte) 0xFF;
        }
        return data;
    }

    // ===== HTTP Handlers =====

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "OK";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    class ScreenshotHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // 解析请求体
            byte[] body = exchange.getRequestBody().readAllBytes();
            String json = new String(body);

            // 简单 JSON 解析（避免引入额外依赖）
            String url = extractJsonField(json, "url");
            int width = Integer.parseInt(extractJsonField(json, "width"));
            int height = Integer.parseInt(extractJsonField(json, "height"));

            // 截图
            byte[] rgba = captureScreenshot(url, width, height);

            // 返回 RGBA 数据
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, rgba.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(rgba);
            }
        }

        private String extractJsonField(String json, String field) {
            // 非常简单的 JSON 字段提取
            String search = "\"" + field + "\":\"";
            int start = json.indexOf(search);
            if (start < 0) {
                // 尝试数字格式
                search = "\"" + field + "\":";
                start = json.indexOf(search);
                if (start < 0) return "";
                start += search.length();
                int end = json.indexOf(",", start);
                if (end < 0) end = json.indexOf("}", start);
                return json.substring(start, end).trim();
            }
            start += search.length();
            int end = json.indexOf("\"", start);
            return json.substring(start, end);
        }
    }
}
