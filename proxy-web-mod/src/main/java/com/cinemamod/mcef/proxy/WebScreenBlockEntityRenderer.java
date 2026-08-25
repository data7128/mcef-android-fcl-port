package com.cinemamod.mcef.proxy;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import org.joml.Matrix4f;

/**
 * 网页显示方块实体渲染器。
 *
 * 在方块正面绘制截图纹理。
 * 使用 RenderSystem + Tessellator 直接渲染（绕过 VertexConsumerProvider），
 * 因为动态 GL 纹理 ID 无法通过标准 RenderLayer 传递。
 *
 * 渲染流程：
 *   1. 调用 entity.clientUpdate() 触发异步截图请求
 *   2. 获取纹理 GL ID（如有新 PNG 数据，先上传）
 *   3. 绑定纹理到 RenderSystem
 *   4. 用 Tessellator 绘制四边形
 *
 * 占位状态：
 *   - 无 URL：灰色四边形
 *   - 有 URL 无纹理：深灰色四边形
 *   - 截图错误：暗红色四边形
 *
 * 许可证：LGPL-2.1-or-later
 */
public class WebScreenBlockEntityRenderer implements BlockEntityRenderer<WebScreenBlockEntity> {

    public WebScreenBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {
        // Context 当前未使用，但 Fabric API 注册要求此构造函数
    }

    @Override
    public void render(WebScreenBlockEntity entity, float tickDelta,
                       MatrixStack matrices,
                       net.minecraft.client.render.VertexConsumerProvider vertexConsumers,
                       int light, int overlay) {
        // 触发截图请求检查
        entity.clientUpdate();

        Direction facing = entity.getCachedState().get(WebScreenBlock.FACING);
        int glId = entity.getAndUpdateTextureGlId();

        matrices.push();
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        if (glId != 0) {
            // 有纹理：绘制带纹理四边形
            RenderSystem.setShader(GameRenderer::getPositionTexProgram);
            RenderSystem.setShaderTexture(0, glId);
            RenderSystem.enableDepthTest();

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);

            drawFaceQuad(buffer, matrix, facing);
            tessellator.draw();
        } else {
            // 无纹理：绘制纯色占位四边形
            float r, g, b;
            if (entity.getUrl() == null || entity.getUrl().isEmpty()) {
                r = 0.15f; g = 0.15f; b = 0.15f; // 深灰：未设置 URL
            } else if (entity.hasError()) {
                r = 0.3f; g = 0.05f; b = 0.05f; // 暗红：截图错误
            } else {
                r = 0.08f; g = 0.08f; b = 0.08f; // 极深灰：加载中
            }

            RenderSystem.setShader(GameRenderer::getPositionProgram);
            RenderSystem.setShaderColor(r, g, b, 1.0f);
            RenderSystem.enableDepthTest();

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);

            drawFaceQuadNoTex(buffer, matrix, facing);
            tessellator.draw();

            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f); // 重置颜色
        }

        matrices.pop();
    }

    /**
     * 绘制带 UV 坐标的四边形（有纹理时使用）。
     */
    private void drawFaceQuad(BufferBuilder buffer, Matrix4f matrix, Direction facing) {
        float o = 0.005f; // Z-fighting 偏移

        switch (facing) {
            case SOUTH: // 正面 z=1
                buffer.vertex(matrix, 0, 0, 1 + o).texture(0, 1).next();
                buffer.vertex(matrix, 1, 0, 1 + o).texture(1, 1).next();
                buffer.vertex(matrix, 1, 1, 1 + o).texture(1, 0).next();
                buffer.vertex(matrix, 0, 1, 1 + o).texture(0, 0).next();
                break;
            case NORTH: // 正面 z=0
                buffer.vertex(matrix, 1, 0, -o).texture(0, 1).next();
                buffer.vertex(matrix, 0, 0, -o).texture(1, 1).next();
                buffer.vertex(matrix, 0, 1, -o).texture(1, 0).next();
                buffer.vertex(matrix, 1, 1, -o).texture(0, 0).next();
                break;
            case EAST: // 正面 x=1
                buffer.vertex(matrix, 1 + o, 0, 1).texture(0, 1).next();
                buffer.vertex(matrix, 1 + o, 0, 0).texture(1, 1).next();
                buffer.vertex(matrix, 1 + o, 1, 0).texture(1, 0).next();
                buffer.vertex(matrix, 1 + o, 1, 1).texture(0, 0).next();
                break;
            case WEST: // 正面 x=0
                buffer.vertex(matrix, -o, 0, 0).texture(0, 1).next();
                buffer.vertex(matrix, -o, 0, 1).texture(1, 1).next();
                buffer.vertex(matrix, -o, 1, 1).texture(1, 0).next();
                buffer.vertex(matrix, -o, 1, 0).texture(0, 0).next();
                break;
            default:
                break;
        }
    }

    /**
     * 绘制无 UV 坐标的四边形（占位色块）。
     */
    private void drawFaceQuadNoTex(BufferBuilder buffer, Matrix4f matrix, Direction facing) {
        float o = 0.005f;

        switch (facing) {
            case SOUTH:
                buffer.vertex(matrix, 0, 0, 1 + o).next();
                buffer.vertex(matrix, 1, 0, 1 + o).next();
                buffer.vertex(matrix, 1, 1, 1 + o).next();
                buffer.vertex(matrix, 0, 1, 1 + o).next();
                break;
            case NORTH:
                buffer.vertex(matrix, 1, 0, -o).next();
                buffer.vertex(matrix, 0, 0, -o).next();
                buffer.vertex(matrix, 0, 1, -o).next();
                buffer.vertex(matrix, 1, 1, -o).next();
                break;
            case EAST:
                buffer.vertex(matrix, 1 + o, 0, 1).next();
                buffer.vertex(matrix, 1 + o, 0, 0).next();
                buffer.vertex(matrix, 1 + o, 1, 0).next();
                buffer.vertex(matrix, 1 + o, 1, 1).next();
                break;
            case WEST:
                buffer.vertex(matrix, -o, 0, 0).next();
                buffer.vertex(matrix, -o, 0, 1).next();
                buffer.vertex(matrix, -o, 1, 1).next();
                buffer.vertex(matrix, -o, 1, 0).next();
                break;
            default:
                break;
        }
    }
}
