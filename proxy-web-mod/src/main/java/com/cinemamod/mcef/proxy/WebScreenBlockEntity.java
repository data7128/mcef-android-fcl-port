package com.cinemamod.mcef.proxy;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.util.concurrent.CompletableFuture;

/**
 * 网页显示方块实体。
 *
 * 存储 URL 和截图设置，管理异步截图请求。
 * 纹理上传在渲染线程执行（通过渲染器调用）。
 *
 * 线程安全说明：
 *   - HTTP 请求在异步线程执行
 *   - PNG 解码和纹理上传在渲染线程执行（通过 NativeImageBackedTexture）
 *   - volatile 字段用于跨线程可见性
 *
 * 许可证：LGPL-2.1-or-later
 */
public class WebScreenBlockEntity extends BlockEntity {
    private static final Logger LOGGER = ProxyWebMod.LOGGER;

    // 持久化数据
    private String url = "";
    private int textureWidth = 512;
    private int textureHeight = 512;

    // 异步截图状态
    private volatile boolean requestInProgress = false;
    private volatile byte[] pendingPng = null;
    private volatile String errorState = null;
    private volatile long lastRequestTime = 0;

    // 客户端纹理（渲染线程管理）
    private transient NativeImageBackedTexture texture;
    private transient int glTextureId = 0;

    // 截图缓存 TTL（通过 ProxyConfig.get().refreshInterval 动态读取）

    public WebScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ProxyWebMod.WEB_SCREEN_BE_TYPE, pos, state);
    }

    // ===== NBT 序列化 =====

    @Override
    public void writeNbt(NbtCompound nbt) {
        nbt.putString("url", url);
        nbt.putInt("width", textureWidth);
        nbt.putInt("height", textureHeight);
        super.writeNbt(nbt);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        url = nbt.getString("url");
        textureWidth = nbt.getInt("width");
        textureHeight = nbt.getInt("height");
        if (textureWidth <= 0) textureWidth = 512;
        if (textureHeight <= 0) textureHeight = 512;
        lastRequestTime = 0; // 强制首次加载时请求截图
    }

    // ===== 客户端更新逻辑（由渲染器每帧调用）=====

    /**
     * 客户端更新：检查是否需要请求新截图。
     * 由渲染器在渲染线程调用。
     */
    public void clientUpdate() {
        if (url == null || url.isEmpty()) return;
        if (requestInProgress) return;

        long now = System.currentTimeMillis();
        long refreshMs = ProxyConfig.get().refreshInterval * 1000L;

        if (now - lastRequestTime > refreshMs) {
            lastRequestTime = now;
            requestInProgress = true;
            requestScreenshotAsync();
        }
    }

    private void requestScreenshotAsync() {
        ProxyAPI.requestScreenshotPNG(url, textureWidth, textureHeight)
                .thenAccept(bytes -> {
                    pendingPng = bytes;
                    errorState = null;
                    requestInProgress = false;
                    LOGGER.debug("截图完成: url={}, size={}bytes", url, bytes.length);
                })
                .exceptionally(e -> {
                    errorState = e.getMessage() != null ? e.getMessage() : e.toString();
                    requestInProgress = false;
                    LOGGER.warn("截图请求失败: url={}, error={}", url, errorState);
                    return null;
                });
    }

    /**
     * 获取并更新纹理 GL ID。
     * 如果有新的 PNG 数据待处理，先上传到纹理。
     * 必须在渲染线程调用。
     *
     * @return GL 纹理 ID，0 表示无纹理
     */
    public int getAndUpdateTextureGlId() {
        if (pendingPng != null) {
            uploadPngToTexture();
        }
        return glTextureId;
    }

    private void uploadPngToTexture() {
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(pendingPng));

            // 限制最大纹理尺寸，防止安卓 GL4ES 崩溃
            int max = ProxyConfig.get().maxTextureSize;
            if (image.getWidth() > max || image.getHeight() > max) {
                image = scaleDown(image, max);
            }

            if (texture == null) {
                texture = new NativeImageBackedTexture(image);
                glTextureId = texture.getGlId();
                LOGGER.info("纹理创建: glId={}, {}x{}", glTextureId, image.getWidth(), image.getHeight());
            } else {
                texture.setImage(image);
                texture.upload();
            }
        } catch (Exception e) {
            LOGGER.error("PNG 纹理上传失败: {}", e.getMessage());
            errorState = e.getMessage();
        }
        pendingPng = null;
    }

    /**
     * 缩小 NativeImage 到最大边长。
     * 使用最近邻采样（简单但足够用）。
     */
    private NativeImage scaleDown(NativeImage src, int max) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        float scale = Math.min((float) max / sw, (float) max / sh);
        if (scale >= 1.0f) return src;

        int dw = Math.max(1, (int) (sw * scale));
        int dh = Math.max(1, (int) (sh * scale));
        NativeImage dst = new NativeImage(dw, dh, false);

        for (int y = 0; y < dh; y++) {
            int srcY = Math.min(sh - 1, (int) (y / scale));
            for (int x = 0; x < dw; x++) {
                int srcX = Math.min(sw - 1, (int) (x / scale));
                dst.setPixelRGBA(x, y, src.getPixelRGBA(srcX, srcY));
            }
        }

        src.close();
        return dst;
    }

    /**
     * 销毁纹理，释放 OpenGL 资源。
     * 必须在渲染线程调用。
     */
    public void destroyTexture() {
        if (texture != null) {
            texture.close();
            texture = null;
            glTextureId = 0;
        }
    }

    /**
     * 获取错误状态（null 表示无错误）。
     */
    public String getErrorState() {
        return errorState;
    }

    /**
     * 是否有错误状态。
     */
    public boolean hasError() {
        return errorState != null;
    }

    // ===== Getters / Setters =====

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url != null ? url : "";
        this.lastRequestTime = 0; // 强制立即刷新
        this.errorState = null;
        markDirty();
    }

    public int getTextureWidth() {
        return textureWidth;
    }

    public void setTextureWidth(int w) {
        this.textureWidth = Math.max(64, Math.min(w, ProxyConfig.get().maxTextureSize));
        markDirty();
    }

    public int getTextureHeight() {
        return textureHeight;
    }

    public void setTextureHeight(int h) {
        this.textureHeight = Math.max(64, Math.min(h, ProxyConfig.get().maxTextureSize));
        markDirty();
    }

    public boolean isRequestInProgress() {
        return requestInProgress;
    }
}
