#!/data/data/com.termux/files/usr/bin/python3
"""
网页截图 HTTP 服务 — Termux 轻量版。

专为 Android 手机 Termux 环境设计，使用 selenium + Chromium（而非 Playwright）。
Termux 原生支持安装 Chromium，兼容性更好。

安装步骤（在 Termux 中执行）：
  pkg update && pkg upgrade -y
  pkg install python python-pip chromium
  pip install aiohttp selenium --break-system-packages

运行：
  python3 screenshot_service_termux.py --port 28085 --host 127.0.0.1

API:
  GET  /health                          → 200 OK
  GET  /screenshot?url=...&width=W&height=H → image/png
  GET  /click?url=...&x=X&y=Y&width=W&height=H → image/png
  GET  /stats                            → JSON

许可证：LGPL-2.1-or-later
"""

import argparse
import asyncio
import logging
import socket
import time
from contextlib import asynccontextmanager

from aiohttp import web

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger("screenshot-termux")

# 全局状态
_driver = None
_stats = {
    "total_requests": 0,
    "successful": 0,
    "failed": 0,
    "start_time": 0,
    "concurrent_current": 0,
    "concurrent_peak": 0,
}

# Chromium 路径（Termux 安装位置）
CHROMIUM_PATHS = [
    "/data/data/com.termux/files/usr/bin/chromium-browser",
    "/data/data/com.termux/files/usr/bin/chromium",
    "/usr/bin/chromium-browser",
    "/usr/bin/chromium",
]


def find_chromium():
    """查找 Chromium 可执行文件。"""
    import os
    for path in CHROMIUM_PATHS:
        if os.path.exists(path):
            return path
    # 尝试 PATH 查找
    import shutil
    for name in ["chromium-browser", "chromium"]:
        path = shutil.which(name)
        if path:
            return path
    return None


def init_driver():
    """初始化 Selenium ChromeDriver（无头模式）。"""
    global _driver
    from selenium import webdriver
    from selenium.webdriver.chrome.options import Options
    from selenium.webdriver.chrome.service import Service

    chromium_path = find_chromium()
    if not chromium_path:
        raise RuntimeError(
            "未找到 Chromium。请运行: pkg install chromium"
        )

    logger.info("Chromium 路径: %s", chromium_path)

    options = Options()
    options.binary_location = chromium_path
    options.add_argument("--headless=new")
    options.add_argument("--no-sandbox")
    options.add_argument("--disable-gpu")
    options.add_argument("--disable-dev-shm-usage")
    options.add_argument("--single-process")
    options.add_argument("--disable-extensions")
    options.add_argument("--disable-plugins")
    options.add_argument("--disable-images")  # 加速页面加载
    options.add_argument("--window-size=1920,1080")

    # Termux 中不需要 chromedriver，Chromium 自带
    try:
        _driver = webdriver.Chrome(options=options)
    except Exception as e:
        logger.error("Chromium 启动失败: %s", e)
        raise

    logger.info("Chromium 已启动")


async def capture_screenshot(url, width, height):
    """使用 Selenium 截图。"""
    import asyncio

    def _capture():
        try:
            _driver.set_window_size(width, height)
            _driver.get(url)
            time.sleep(1.5)  # 等待页面渲染
            png = _driver.get_screenshot_as_png()
            return png
        except Exception as e:
            logger.error("截图失败: %s - %s", url, e)
            return b""

    # Selenium 是同步的，用 run_in_executor 包装
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _capture)


async def capture_click_screenshot(url, x, y, width, height):
    """使用 Selenium 模拟点击后截图。"""
    from selenium.webdriver.common.action_chains import ActionChains

    def _capture_click():
        try:
            _driver.set_window_size(width, height)
            _driver.get(url)
            time.sleep(1.5)

            # 模拟点击
            ActionChains(_driver).move_by_offset(
                x - width // 2, y - height // 2
            ).click().perform()

            time.sleep(1.5)  # 等待页面响应
            png = _driver.get_screenshot_as_png()
            return png
        except Exception as e:
            logger.error("点击截图失败: %s - %s", url, e)
            return b""

    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _capture_click)


async def health_handler(request):
    return web.Response(text="OK", status=200)


async def screenshot_handler(request):
    url = request.query.get("url", "")
    width = int(request.query.get("width", "512"))
    height = int(request.query.get("height", "512"))

    if not url:
        return web.Response(text="Missing url parameter", status=400)
    if not url.startswith("http"):
        url = "https://" + url

    _stats["total_requests"] += 1
    _stats["concurrent_current"] += 1
    if _stats["concurrent_current"] > _stats["concurrent_peak"]:
        _stats["concurrent_peak"] = _stats["concurrent_current"]

    logger.info("截图请求: url=%s, %dx%d", url, width, height)
    t0 = time.time()

    try:
        png_data = await capture_screenshot(url, width, height)
        elapsed = time.time() - t0

        if png_data:
            _stats["successful"] += 1
            logger.info("截图完成: %d bytes, %.2fs", len(png_data), elapsed)
            return web.Response(body=png_data, content_type="image/png")
        else:
            _stats["failed"] += 1
            return web.Response(text="Screenshot failed", status=503)
    finally:
        _stats["concurrent_current"] -= 1


async def click_handler(request):
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
    _stats["concurrent_current"] += 1
    if _stats["concurrent_current"] > _stats["concurrent_peak"]:
        _stats["concurrent_peak"] = _stats["concurrent_current"]

    logger.info("点击请求: url=%s, (%d, %d), %dx%d", url, x, y, width, height)
    t0 = time.time()

    try:
        png_data = await capture_click_screenshot(url, x, y, width, height)
        elapsed = time.time() - t0

        if png_data:
            _stats["successful"] += 1
            logger.info("点击截图完成: %d bytes, %.2fs", len(png_data), elapsed)
            return web.Response(body=png_data, content_type="image/png")
        else:
            _stats["failed"] += 1
            return web.Response(text="Click failed", status=503)
    finally:
        _stats["concurrent_current"] -= 1


async def stats_handler(request):
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
    return web.Response(text="""
    <html><body>
    <h1>Proxy Web Mod — 截图服务 (Termux 版)</h1>
    <p>状态: 运行中</p>
    <p>API:</p>
    <ul>
      <li>GET /health</li>
      <li>GET /screenshot?url=...&width=512&height=512</li>
      <li>GET /click?url=...&x=0&y=0&width=512&height=512</li>
      <li>GET /stats</li>
    </ul>
    </body></html>
    """, content_type="text/html")


def check_port_available(host, port):
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.bind((host, port))
        return True
    except OSError:
        return False


async def create_app():
    init_driver()
    _stats["start_time"] = time.time()

    app = web.Application()
    app.router.add_get("/", index_handler)
    app.router.add_get("/health", health_handler)
    app.router.add_get("/screenshot", screenshot_handler)
    app.router.add_get("/click", click_handler)
    app.router.add_get("/stats", stats_handler)

    async def on_cleanup(app):
        global _driver
        if _driver:
            _driver.quit()
            logger.info("Chromium 已关闭")

    app.on_cleanup.append(on_cleanup)
    return app


def main():
    parser = argparse.ArgumentParser(description="网页截图服务 (Termux 版)")
    parser.add_argument("--host", default="127.0.0.1", help="监听地址")
    parser.add_argument("--port", type=int, default=28085, help="监听端口")
    args = parser.parse_args()

    if not check_port_available(args.host, args.port):
        logger.error("端口 %d 已被占用", args.port)
        return

    logger.info("截图服务启动: http://%s:%d", args.host, args.port)
    logger.info("mod 配置 serviceUrl 设为: http://127.0.0.1:%d", args.port)

    async def run():
        app = await create_app()
        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, host=args.host, port=args.port)
        await site.start()
        logger.info("服务已就绪，等待请求...")

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
