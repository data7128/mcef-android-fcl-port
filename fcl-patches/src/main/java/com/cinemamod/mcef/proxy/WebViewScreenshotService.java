package com.cinemamod.mcef.proxy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * FCL 内置 WebView 截图服务 v2。
 *
 * 监听 localhost:28085，接收截图请求，返回 PNG 图片。
 *
 * API:
 *   GET  /health                → 200 OK
 *   GET  /screenshot?url=...&width=W&height=H → image/png
 *   POST /screenshot (JSON)      → image/png (向后兼容)
 *
 * === 关键限制 ===
 *
 * 1. Activity 依赖问题：
 *    FCL 环境只能拿到 Application Context，拿不到 Activity。
 *    Android WebView 在某些版本上用 Application Context 创建会崩溃
 *    （Crash: "Unable to add window -- token null is not for an application"）。
 *    本类使用 Handler(Looper.getMainLooper()) 替代 Activity.runOnUiThread()，
 *    但 WebView 的创建仍然可能因缺少 Activity 而失败。
 *    这是 Android 系统硬限制，无法在 mod 层面绕过。
 *
 * 2. 后台渲染问题：
 *    WebView 在无窗口/后台状态下 draw() 可能返回空白 Bitmap。
 *    这是 Android 渲染管线的限制，非代码可修复。
 *
 * 3. 集成要求：
 *    此类需要在 FCL 启动器源码中集成（在 JVMActivity.onCreate 中启动），
 *    不属于纯 Fabric mod 部分。放在本仓库中作为参考实现和 FCL 补丁的源文件。
 *
 * 许可证：LGPL-2.1-or-later
 */
public class WebViewScreenshotService {
    private static final Logger LOGGER = LoggerFactory.getLogger("proxy-web-mod");
    private static final int PORT = 28085;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HttpServer server;
    private WebView webView;

    public WebViewScreenshotService(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() throws IOException {
        // 在主线程创建 WebView
        mainHandler.post(() -> {
            try {
                webView = new WebView(context);
                webView.layout(0, 0, 1920, 1080);
                webView.getSettings().setJavaScriptEnabled(true);
                webView.getSettings().setDomStorageEnabled(true);
                LOGGER.info("WebView 创建成功");
            } catch (Exception e) {
                LOGGER.error("WebView 创建失败（可能是 Application Context 限制）: {}", e.getMessage());
            }
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
            mainHandler.post(() -> {
                try {
                    webView.destroy();
                } catch (Exception e) {
                    LOGGER.warn("WebView 销毁异常: {}", e.getMessage());
                }
                webView = null;
            });
        }
        LOGGER.info("WebView 截图服务已停止");
    }

    /**
     * 截取指定 URL 的网页为 PNG 字节数组。
     */
    public byte[] captureScreenshot(String url, int width, int height) {
        if (webView == null) {
            LOGGER.warn("WebView 未初始化，返回 null");
            return null;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final byte[][] result = {null};

        mainHandler.post(() -> {
            try {
                webView.layout(0, 0, width, height);

                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String finishedUrl) {
                        try {
                            // 延迟 500ms 等待渲染完成
                            Thread.sleep(500);

                            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                            Canvas canvas = new Canvas(bmp);
                            canvas.drawColor(android.graphics.Color.WHITE);
                            view.draw(canvas);

                            // 转为 PNG
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            bmp.compress(Bitmap.CompressFormat.PNG, 90, baos);
                            result[0] = baos.toByteArray();
                            baos.close();
                            bmp.recycle();
                        } catch (Exception e) {
                            LOGGER.warn("截图绘制失败: {}", e.getMessage());
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onReceivedError(WebView view, int errorCode,
                                                String description, String failingUrl) {
                        LOGGER.warn("WebView 加载错误: {} (code={})", description, errorCode);
                        latch.countDown();
                    }
                });

                webView.loadUrl(url);
            } catch (Exception e) {
                LOGGER.error("截图请求异常: {}", e.getMessage());
                latch.countDown();
            }
        });

        // 等待截图完成，最多 10 秒
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                LOGGER.warn("截图超时: url={}", url);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        return result[0];
    }

    // ===== HTTP Handlers =====

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "OK";
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    class ScreenshotHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())
                    && !"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String url;
            int width, height;

            if ("GET".equals(exchange.getRequestMethod())) {
                // GET /screenshot?url=...&width=512&height=512
                Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
                url = params.getOrDefault("url", "");
                width = Integer.parseInt(params.getOrDefault("width", "512"));
                height = Integer.parseInt(params.getOrDefault("height", "512"));
            } else {
                // POST /screenshot (JSON body, 向后兼容)
                byte[] body = exchange.getRequestBody().readAllBytes();
                String json = new String(body, StandardCharsets.UTF_8);
                url = extractJsonField(json, "url");
                width = Integer.parseInt(extractJsonField(json, "width"));
                height = Integer.parseInt(extractJsonField(json, "height"));
            }

            // 限制最大尺寸
            width = Math.min(width, 1920);
            height = Math.min(height, 1080);

            // 截图
            byte[] png = captureScreenshot(url, width, height);

            if (png != null) {
                // 返回 PNG 数据
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, png.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(png);
                }
            } else {
                // 截图失败
                byte[] error = "Screenshot failed".getBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(503, error.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(error);
                }
            }
        }

        private Map<String, String> parseQueryParams(String query) {
            Map<String, String> params = new HashMap<>();
            if (query == null || query.isEmpty()) return params;

            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    params.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                }
            }
            return params;
        }

        private String extractJsonField(String json, String field) {
            String search = "\"" + field + "\":\"";
            int start = json.indexOf(search);
            if (start < 0) {
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
