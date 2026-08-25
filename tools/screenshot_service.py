#!/usr/bin/env python3
"""
网页截图 HTTP 服务 — proxy-web-mod 的配套截图服务（异步并发版）。

支持并发截图请求，使用 Playwright Async API + aiohttp。

API:
  GET  /health                          → 200 OK
  GET  /screenshot?url=...&width=W&height=H → image/png
  GET  /click?url=...&x=X&y=Y&width=W&height=H → image/png (模拟点击后截图)
  GET  /stats                            → JSON 服务统计

依赖:
  pip install aiohttp playwright
  playwright install chromium

运行:
  python3 screenshot_service_async.py --port 28085 --host 0.0.0.0

许可证：LGPL-2.1-or-later
"""

import argparse
import asyncio
import logging
import socket
import time
from contextlib import asynccontextmanager

from aiohttp import web
from playwright.async_api import async_playwright, TimeoutError as PWTimeout

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger("screenshot-service")

# 全局状态
_pw = None
_browser = None
_semaphore = None  # 并发控制信号量
_stats = {
    "total_requests": 0,
    "successful": 0,
    "failed": 0,
    "start_time": 0,
    "concurrent_current": 0,
    "concurrent_peak": 0,
}


async def init_browser(max_concurrent: int):
    """初始化 Playwright 异步浏览器。"""
    global _pw, _browser, _semaphore
    _pw = await async_playwright().start()
    _browser = await _pw.chromium.launch(
        headless=True,
        args=[
            "--no-sandbox",
            "--disable-gpu",
            "--disable-dev-shm-usage",
        ],
    )
    _semaphore = asyncio.Semaphore(max_concurrent)
    _stats["start_time"] = 0
    logger.info("Playwright Chromium 浏览器已启动 (最大并发: %d)", max_concurrent)


async def close_browser():
    """关闭浏览器和 Playwright。"""
    global _pw, _browser
    if _browser:
        await _browser.close()
    if _pw:
        await _pw.stop()
    logger.info("Playwright 已关闭")


async def capture_screenshot(url: str, width: int, height: int) -> bytes:
    """使用 Playwright 异步截图，返回 PNG 字节数据。"""
    async with _semaphore:
        _stats["concurrent_current"] += 1
        if _stats["concurrent_current"] > _stats["concurrent_peak"]:
            _stats["concurrent_peak"] = _stats["concurrent_current"]

        try:
            context = await _browser.new_context(
                viewport={"width": min(width, 1920), "height": min(height, 1080)},
                device_scale_factor=1,
            )
            page = await context.new_page()
            try:
                await page.goto(url, wait_until="domcontentloaded", timeout=15000)
                # 等待页面渲染
                await page.wait_for_timeout(800)
                screenshot = await page.screenshot(
                    type="png",
                    full_page=False,
                    clip={"x": 0, "y": 0, "width": width, "height": height},
                )
                return screenshot
            except PWTimeout:
                logger.warning("页面加载超时: %s", url)
                return b""
            except Exception as e:
                logger.error("截图失败: %s - %s", url, e)
                return b""
            finally:
                await context.close()
        finally:
            _stats["concurrent_current"] -= 1


async def capture_click_screenshot(url: str, x: int, y: int, width: int, height: int) -> bytes:
    """使用 Playwright 异步打开页面，在指定坐标模拟点击后截图，返回 PNG 字节数据。"""
    async with _semaphore:
        _stats["concurrent_current"] += 1
        if _stats["concurrent_current"] > _stats["concurrent_peak"]:
            _stats["concurrent_peak"] = _stats["concurrent_current"]

        try:
            context = await _browser.new_context(
                viewport={"width": min(width, 1920), "height": min(height, 1080)},
                device_scale_factor=1,
            )
            page = await context.new_page()
            try:
                await page.goto(url, wait_until="domcontentloaded", timeout=15000)
                # 等待页面渲染
                await page.wait_for_timeout(500)
                # 在指定坐标模拟点击
                await page.mouse.click(x, y)
                # 等待页面响应点击
                await page.wait_for_timeout(1500)
                screenshot = await page.screenshot(
                    type="png",
                    full_page=False,
                    clip={"x": 0, "y": 0, "width": width, "height": height},
                )
                return screenshot
            except PWTimeout:
                logger.warning("页面加载超时: %s", url)
                return b""
            except Exception as e:
                logger.error("点击截图失败: %s - %s", url, e)
                return b""
            finally:
                await context.close()
        finally:
            _stats["concurrent_current"] -= 1


async def health_handler(request):
    """健康检查端点。"""
    return web.Response(text="OK", status=200)


async def screenshot_handler(request):
    """截图端点。"""
    url = request.query.get("url", "")
    width = int(request.query.get("width", "512"))
    height = int(request.query.get("height", "512"))

    if not url:
        return web.Response(text="Missing url parameter", status=400)

    if not url.startswith("http"):
        url = "https://" + url

    _stats["total_requests"] += 1
    logger.info("截图请求: url=%s, %dx%d (并发: %d)",
                url, width, height, _stats["concurrent_current"])

    t0 = time.time()
    png_data = await capture_screenshot(url, width, height)
    elapsed = time.time() - t0

    if png_data:
        _stats["successful"] += 1
        logger.info("截图完成: url=%s, %d bytes, %.2fs",
                    url, len(png_data), elapsed)
        return web.Response(body=png_data, content_type="image/png")
    else:
        _stats["failed"] += 1
        logger.warning("截图失败: url=%s, %.2fs", url, elapsed)
        return web.Response(text="Screenshot failed", status=503)


async def click_handler(request):
    """点击端点：在指定坐标模拟点击后截图。"""
    url = request.query.get("url", "")
    x = int(request.query.get("x", "0"))
    y = int(request.query.get("y", "0"))
    width = int(request.query.get("width", "512"))
    height = int(request.query.get("height", "512"))

    if not url:
        return web.Response(text="Missing url parameter", status=400)

    if not url.startswith("http"):
        url = "https://" + url

    _stats["total_requests"] += 1
    logger.info("点击请求: url=%s, (%d,%d), %dx%d (并发: %d)",
                url, x, y, width, height, _stats["concurrent_current"])

    t0 = time.time()
    png_data = await capture_click_screenshot(url, x, y, width, height)
    elapsed = time.time() - t0

    if png_data:
        _stats["successful"] += 1
        logger.info("点击截图完成: url=%s, (%d,%d), %d bytes, %.2fs",
                    url, x, y, len(png_data), elapsed)
        return web.Response(body=png_data, content_type="image/png")
    else:
        _stats["failed"] += 1
        logger.warning("点击截图失败: url=%s, (%d,%d), %.2fs",
                       url, x, y, elapsed)
        return web.Response(text="Click screenshot failed", status=503)


async def stats_handler(request):
    """服务统计端点。"""
    uptime = time.time() - _stats["start_time"]
    return web.json_response({
        "total_requests": _stats["total_requests"],
        "successful": _stats["successful"],
        "failed": _stats["failed"],
        "concurrent_current": _stats["concurrent_current"],
        "concurrent_peak": _stats["concurrent_peak"],
        "uptime_seconds": round(uptime, 1),
    })


async def index_handler(request):
    """服务信息页面。"""
    return web.Response(text="""
    <html><body>
    <h1>Proxy Web Mod — 截图服务 (异步并发版)</h1>
    <p>状态: 运行中</p>
    <p>API:</p>
    <ul>
      <li>GET /health — 健康检查</li>
      <li>GET /screenshot?url=...&width=512&height=512 — 网页截图</li>
      <li>GET /click?url=...&x=0&y=0&width=512&height=512 — 模拟点击后截图</li>
      <li>GET /stats — 服务统计</li>
    </ul>
    </body></html>
    """, content_type="text/html")


def check_port_available(host: str, port: int) -> bool:
    """检查端口是否可用。"""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.bind((host, port))
        return True
    except OSError:
        return False


async def create_app(max_concurrent: int):
    """创建 aiohttp 应用并初始化浏览器。"""
    await init_browser(max_concurrent)
    _stats["start_time"] = time.time()

    app = web.Application()
    app.router.add_get("/", index_handler)
    app.router.add_get("/health", health_handler)
    app.router.add_get("/screenshot", screenshot_handler)
    app.router.add_get("/click", click_handler)
    app.router.add_get("/stats", stats_handler)
    return app


def main():
    parser = argparse.ArgumentParser(description="网页截图 HTTP 服务 (异步并发版)")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址 (默认: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=28085, help="监听端口 (默认: 28085)")
    parser.add_argument("--concurrency", type=int, default=4,
                        help="最大并发截图数 (默认: 4)")
    args = parser.parse_args()

    if not check_port_available(args.host, args.port):
        logger.error("端口 %d 已被占用", args.port)
        return

    logger.info("截图服务启动: http://%s:%d (最大并发: %d)",
                args.host, args.port, args.concurrency)
    logger.info("在 mod 配置中将 serviceUrl 设为: http://<本机IP>:%d", args.port)

    async def on_cleanup(app):
        await close_browser()

    async def run():
        app = await create_app(args.concurrency)
        app.on_cleanup.append(on_cleanup)
        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, host=args.host, port=args.port)
        await site.start()
        logger.info("服务已就绪，等待请求...")

        # 保持运行
        try:
            while True:
                await asyncio.sleep(1)
        except (KeyboardInterrupt, asyncio.CancelledError):
            pass
        finally:
            await runner.cleanup()

    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        logger.info("服务停止")


if __name__ == "__main__":
    main()
