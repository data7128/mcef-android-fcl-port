package com.cinemamod.mcef.proxy;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.util.concurrent.CompletableFuture;

/**
 * 网页显示方块实体。
 *
 * 存储 URL 和截图设置，管理异步截图请求和模拟点击交互。
 * 纹理上传在渲染线程执行（通过渲染器调用）。
 *
 * 功能：
 *   - 网页截图自动刷新（基于 ProxyConfig.refreshInterval）
 *   - 模拟点击：左键方块，换算成网页像素坐标，发送给截图服务
 *   - NBT 持久化：网址、截图尺寸、点击开关状态
 *   - 纹理自动缩放，防止安卓 GL4ES 崩溃
 *   - 容错：空 URL 跳过、网络超时、异常捕获
 *
 * 线程安全：
 *   - HTTP 请求在异步线程执行
 *   - PNG 解码和纹理上传在渲染线程执行
 *   - volatile 字段用于跨线程可见性
 *   - MinecraftClient.execute() 确保回调在客户端线程执行
 *
 * 许可证：LGPL-2.1-or-later
 */
public class WebScreenBlockEntity extends BlockEntity {
    private static final Logger LOGGER = ProxyWebMod.LOGGER;

    // ===== 持久化数据（NBT） =====
    private String url = "";
    private int textureWidth = 512;
    private int textureHeight = 512;
    private boolean clickEnabled = false;

    // ===== 截图请求状态 =====
    private volatile boolean requestInProgress = false;
    private volatile byte[] pendingPng = null;
    private volatile String errorState = null;
    private volatile long lastRequestTime = 0;

    // ===== 模拟点击状态 =====
    private volatile boolean clickInProgress = false;
    private volatile String clickStatus = null;

    // ===== 客户端纹理（渲染线程管理） =====
    private transient NativeImageBackedTexture texture;
    private transient int glTextureId = 0;

    public WebScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ProxyWebMod.WEB_SCREEN_BE_TYPE, pos, state);
    }

    // ===== NBT 序列化 =====

    @Override
    public void writeNbt(NbtCompound nbt) {
        nbt.putString("url", url);
        nbt.putInt("width", textureWidth);
        nbt.putInt("height", textureHeight);
        nbt.putBoolean("clickEnabled", clickEnabled);
        super.writeNbt(nbt);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        url = nbt.getString("url");
        textureWidth = nbt.getInt("width");
        textureHeight = nbt.getInt("height");
        clickEnabled = nbt.getBoolean("clickEnabled");
        if (textureWidth <= 0) textureWidth = 512;
        if (textureHeight <= 0) textureHeight = 512;
        lastRequestTime = 0; // 强制首次加载时请求截图
    }

    // ===== 客户端更新逻辑（由渲染器每帧调用） =====

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
                    if (bytes != null && bytes.length > 0) {
                        pendingPng = bytes;
                        errorState = null;
                        LOGGER.debug("截图完成: url={}, size={}bytes", url, bytes.length);
                    } else {
                        errorState = "截图服务返回空数据";
                        LOGGER.warn("截图返回空数据: url={}", url);
                    }
                    requestInProgress = false;
                })
                .exceptionally(e -> {
                    errorState = e.getMessage() != null ? e.getMessage() : e.toString();
                    requestInProgress = false;
                    LOGGER.warn("截图请求失败: url={}, error={}", url, errorState);
                    return null;
                });
    }

    // ===== 模拟点击交互 =====

    /**
     * 计算从世界命中位置到网页像素坐标的映射。
     *
     * 根据 BlockHitResult 的命中位置和方块朝向，
     * 换算出在纹理上的像素坐标。
     *
     * UV 映射与渲染器中的 drawFaceQuad 一致：
     *   SOUTH: U=localX, V=1-localY
     *   NORTH: U=1-localX, V=1-localY
     *   EAST:  U=1-localZ, V=1-localY
     *   WEST:  U=localZ, V=1-localY
     *
     * @param hitPos    命中的世界坐标
     * @param blockPos  方块位置
     * @param facing    方块朝向
     * @return int[2]{pixelX, pixelY}，或 null（不支持的朝向）
     */
    public int[] calculatePixelCoords(Vec3d hitPos, BlockPos blockPos, Direction facing) {
        double localX = hitPos.x - blockPos.getX();
        double localY = hitPos.y - blockPos.getY();
        double localZ = hitPos.z - blockPos.getZ();

        double u, v;

        switch (facing) {
            case SOUTH:
                u = localX;
                v = 1.0 - localY;
                break;
            case NORTH:
                u = 1.0 - localX;
                v = 1.0 - localY;
                break;
            case EAST:
                u = 1.0 - localZ;
                v = 1.0 - localY;
                break;
            case WEST:
                u = localZ;
                v = 1.0 - localY;
                break;
            default:
                return null;
        }

        // 转换为像素坐标，并限制范围
        int pixelX = Math.max(0, Math.min((int)(u * textureWidth), textureWidth - 1));
        int pixelY = Math.max(0, Math.min((int)(v * textureHeight), textureHeight - 1));

        return new int[]{pixelX, pixelY};
    }

    /**
     * 发送模拟点击请求到截图服务。
     * 截图服务在指定坐标模拟点击，返回新截图。
     *
     * @param pixelX 点击X像素坐标
     * @param pixelY 点击Y像素坐标
     */
    public void sendClick(int pixelX, int pixelY) {
        if (url == null || url.isEmpty()) return;
        if (clickInProgress) return;

        clickInProgress = true;
        clickStatus = "发送中...";

        LOGGER.info("模拟点击: url={}, ({}, {})", url, pixelX, pixelY);

        ProxyAPI.sendClick(url, pixelX, pixelY, textureWidth, textureHeight)
                .thenAccept(bytes -> {
                    MinecraftClient.getInstance().execute(() -> {
                        if (bytes != null && bytes.length > 0) {
                            pendingPng = bytes;
                            errorState = null;
                            clickStatus = "点击成功";
                            PlayerEntity player = MinecraftClient.getInstance().player;
                            if (player != null) {
                                player.sendMessage(Text.literal("§a点击成功 ✓")
                                        .formatted(Formatting.GREEN), true);
                            }
                        } else {
                            clickStatus = "点击失败: 服务返回空数据";
                            PlayerEntity player = MinecraftClient.getInstance().player;
                            if (player != null) {
                                player.sendMessage(Text.literal("§c点击失败: 服务返回空数据")
                                        .formatted(Formatting.RED), true);
                            }
                        }
                        clickInProgress = false;
                    });
                })
                .exceptionally(e -> {
                    MinecraftClient.getInstance().execute(() -> {
                        clickStatus = "错误: " + e.getMessage();
                        clickInProgress = false;
                        PlayerEntity player = MinecraftClient.getInstance().player;
                        if (player != null) {
                            player.sendMessage(Text.literal("§c点击失败: " + e.getMessage())
                                    .formatted(Formatting.RED), true);
                        }
                    });
                    return null;
                });
    }

    // ===== 纹理管理 =====

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
                texture.upload();
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
     * Yarn 1.20.1+build.10 中使用 getColor / setColor 进行逐像素操作。
     * 使用 fillRect 作为降级方案（当逐像素访问出现异常时）。
     */
    private NativeImage scaleDown(NativeImage src, int max) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw <= max && sh <= max) return src;

        float scale = Math.min((float) max / sw, (float) max / sh);
        int dw = Math.max(1, (int) (sw * scale));
        int dh = Math.max(1, (int) (sh * scale));
        NativeImage dst = new NativeImage(dw, dh, false);

        try {
            for (int y = 0; y < dh; y++) {
                int srcY = Math.min(sh - 1, (int) (y / scale));
                for (int x = 0; x < dw; x++) {
                    int srcX = Math.min(sw - 1, (int) (x / scale));
                    dst.setColor(x, y, src.getColor(srcX, srcY));
                }
            }
            src.close();
            return dst;
        } catch (Exception e) {
            LOGGER.warn("逐像素缩放失败，使用降级方案: {}", e.getMessage());
            dst.fillRect(0, 0, dw, dh, 0xFF333333);
            src.close();
            return dst;
        }
    }

    /**
     * 强制刷新截图。GUI 刷新按钮调用。
     */
    public void forceRefresh() {
        if (url == null || url.isEmpty()) return;
        lastRequestTime = 0;
        errorState = null;
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

    // ===== Getters / Setters =====

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url != null ? url : "";
        this.lastRequestTime = 0;
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

    public boolean isClickEnabled() {
        return clickEnabled;
    }

    public void setClickEnabled(boolean enabled) {
        this.clickEnabled = enabled;
        markDirty();
    }

    public boolean isRequestInProgress() {
        return requestInProgress;
    }

    public boolean isClickInProgress() {
        return clickInProgress;
    }

    public String getClickStatus() {
        return clickStatus;
    }

    public String getErrorState() {
        return errorState;
    }

    public boolean hasError() {
        return errorState != null;
    }
}
