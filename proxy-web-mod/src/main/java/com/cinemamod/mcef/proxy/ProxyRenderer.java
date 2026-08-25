package com.cinemamod.mcef.proxy;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * 代理渲染器，将 WebView 截图数据上传到 OpenGL ES 纹理。
 *
 * 与 MCEF MCEFRenderer 的区别：
 *   - MCEFRenderer 使用 GL_BGRA + GL_UNSIGNED_INT_8_8_8_8_REV（桌面 OpenGL）
 *   - ProxyRenderer 使用 GL_RGBA + GL_UNSIGNED_BYTE（OpenGL ES 兼容）
 *
 * 截图服务返回 RGBA 格式数据，无需字节序转换。
 *
 * 关键实现细节：
 *   - LWJGL 的 glTexImage2D / glTexSubImage2D 要求直接缓冲区 (direct buffer)，
 *     不能使用 ByteBuffer.wrap() 包装的堆缓冲区，否则会抛出 IllegalArgumentException
 *     或在 native 层段错误。使用 MemoryUtil.memAlloc() 分配直接缓冲区。
 *
 * 许可证：LGPL-2.1-or-later
 */
public class ProxyRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("proxy-web-mod");

    private final int[] textureID = new int[1];
    private int currentWidth = 0;
    private int currentHeight = 0;

    /**
     * 初始化 OpenGL 纹理。
     * 必须在 Minecraft 渲染线程调用。
     */
    public void initialize(int width, int height) {
        glGenTextures(textureID);
        if (textureID[0] == 0) {
            LOGGER.error("纹理生成失败: glGenTextures 返回 0");
            return;
        }

        currentWidth = width;
        currentHeight = height;

        glBindTexture(GL_TEXTURE_2D, textureID[0]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // 初始化为透明纹理（使用直接缓冲区）
        ByteBuffer empty = MemoryUtil.memAlloc(width * height * 4);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, empty);
        MemoryUtil.memFree(empty);

        LOGGER.info("纹理初始化完成: id={}, {}x{}", textureID[0], width, height);
    }

    /**
     * 上传截图帧到纹理。
     * 必须在 Minecraft 渲染线程调用。
     *
     * @param rgbaData RGBA 格式字节数组
     * @param width    数据宽度
     * @param height   数据高度
     */
    public void uploadFrame(byte[] rgbaData, int width, int height) {
        if (textureID[0] == 0) {
            LOGGER.warn("纹理未初始化，跳过帧上传");
            return;
        }

        // LWJGL 要求直接缓冲区传递给 OpenGL
        ByteBuffer buffer = MemoryUtil.memAlloc(rgbaData.length);
        buffer.put(rgbaData);
        buffer.flip();

        if (width != currentWidth || height != currentHeight) {
            // 尺寸变化，重新分配纹理
            currentWidth = width;
            currentHeight = height;
            glBindTexture(GL_TEXTURE_2D, textureID[0]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        } else {
            // 尺寸不变，使用 glTexSubImage2D 更新
            glBindTexture(GL_TEXTURE_2D, textureID[0]);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        }

        MemoryUtil.memFree(buffer);
    }

    /**
     * 调整纹理尺寸。
     * 必须在 Minecraft 渲染线程调用。
     */
    public void resize(int width, int height) {
        if (textureID[0] == 0) {
            initialize(width, height);
            return;
        }

        currentWidth = width;
        currentHeight = height;

        glBindTexture(GL_TEXTURE_2D, textureID[0]);
        ByteBuffer empty = MemoryUtil.memAlloc(width * height * 4);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, empty);
        MemoryUtil.memFree(empty);

        LOGGER.info("纹理尺寸调整: {}x{}", width, height);
    }

    /**
     * 获取纹理 ID。
     */
    public int getTextureID() {
        return textureID[0];
    }

    /**
     * 销毁纹理，释放 OpenGL 资源。
     * 必须在 Minecraft 渲染线程调用。
     */
    public void destroy() {
        if (textureID[0] != 0) {
            glDeleteTextures(textureID);
            textureID[0] = 0;
            LOGGER.info("纹理已销毁");
        }
    }
}
