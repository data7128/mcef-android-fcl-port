package com.cinemamod.mcef.proxy;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.joml.Matrix4f;

/**
 * 网页显示方块 GUI 界面。
 *
 * 功能：
 *   - URL 输入框
 *   - 截图尺寸设置（宽度、高度）
 *   - 刷新间隔设置
 *   - 按钮组：保存、刷新截图、取消、清除URL
 *   - 模拟点击开关（CyclingButtonWidget）
 *   - 图片预览区域（实时渲染当前纹理）
 *   - 错误状态和点击状态显示
 *
 * 操作方式：
 *   1. 放置 WebScreenBlock 方块
 *   2. 右键方块打开此 GUI
 *   3. 输入 URL（如 https://www.baidu.com）
 *   4. 调整尺寸和刷新间隔
 *   5. 开启/关闭模拟点击功能
 *   6. 点击「保存」按钮
 *   7. 在图片预览区域查看当前截图状态
 *   8. 点击「刷新截图」立即请求新截图
 *
 * 许可证：LGPL-2.1-or-later
 */
public class WebScreenGUI extends Screen {
    private final WebScreenBlockEntity blockEntity;

    private TextFieldWidget urlField;
    private TextFieldWidget widthField;
    private TextFieldWidget heightField;
    private TextFieldWidget intervalField;

    private static final int FIELD_WIDTH = 220;
    private static final int FIELD_HEIGHT = 20;
    private static final int SMALL_FIELD_WIDTH = 50;

    // 预览区域尺寸
    private static final int PREVIEW_W = 160;
    private static final int PREVIEW_H = 90;

    public WebScreenGUI(WebScreenBlockEntity blockEntity) {
        super(Text.literal("网页显示设置"));
        this.blockEntity = blockEntity;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;

        // URL 输入框
        urlField = new TextFieldWidget(this.textRenderer,
                centerX - FIELD_WIDTH / 2, 50, FIELD_WIDTH, FIELD_HEIGHT,
                Text.literal("URL"));
        urlField.setMaxLength(512);
        urlField.setText(blockEntity.getUrl());
        addSelectableChild(urlField);

        // 宽度输入框
        widthField = new TextFieldWidget(this.textRenderer,
                centerX - 110, 90, SMALL_FIELD_WIDTH, FIELD_HEIGHT,
                Text.literal("宽"));
        widthField.setText(String.valueOf(blockEntity.getTextureWidth()));
        addSelectableChild(widthField);

        // 高度输入框
        heightField = new TextFieldWidget(this.textRenderer,
                centerX - 40, 90, SMALL_FIELD_WIDTH, FIELD_HEIGHT,
                Text.literal("高"));
        heightField.setText(String.valueOf(blockEntity.getTextureHeight()));
        addSelectableChild(heightField);

        // 刷新间隔输入框
        intervalField = new TextFieldWidget(this.textRenderer,
                centerX + 30, 90, SMALL_FIELD_WIDTH, FIELD_HEIGHT,
                Text.literal("间隔"));
        intervalField.setText(String.valueOf(ProxyConfig.get().refreshInterval));
        addSelectableChild(intervalField);

        // 保存按钮
        addDrawableChild(ButtonWidget.builder(Text.literal("保存"), button -> {
            saveSettings();
            this.close();
        }).dimensions(centerX - 155, 120, 70, 20).build());

        // 刷新截图按钮
        addDrawableChild(ButtonWidget.builder(Text.literal("刷新截图"), button -> {
            saveSettings();
            blockEntity.forceRefresh();
        }).dimensions(centerX - 80, 120, 70, 20).build());

        // 取消按钮
        addDrawableChild(ButtonWidget.builder(Text.literal("取消"), button -> {
            this.close();
        }).dimensions(centerX - 5, 120, 70, 20).build());

        // 清除 URL 按钮
        addDrawableChild(ButtonWidget.builder(Text.literal("清除网址"), button -> {
            blockEntity.setUrl("");
            urlField.setText("");
            blockEntity.destroyTexture();
        }).dimensions(centerX + 80, 120, 70, 20).build());

        // 模拟点击开关
        addDrawableChild(CyclingButtonWidget.onOffBuilder(blockEntity.isClickEnabled())
                .dimensions(centerX - 70, 150, 140, 20)
                .build(Text.literal("模拟点击"), (widget, value) -> {
                    blockEntity.setClickEnabled(value);
                }));
    }

    private void saveSettings() {
        try {
            String url = urlField.getText().trim();
            if (!url.isEmpty() && !url.startsWith("http")) {
                url = "https://" + url;
            }
            blockEntity.setUrl(url);

            int w = Integer.parseInt(widthField.getText().trim());
            blockEntity.setTextureWidth(w);

            int h = Integer.parseInt(heightField.getText().trim());
            blockEntity.setTextureHeight(h);

            int interval = Integer.parseInt(intervalField.getText().trim());
            ProxyConfig.get().refreshInterval = Math.max(10, interval);
            ProxyConfig.save();

            ProxyWebMod.LOGGER.info("网页显示设置已保存: url={}, {}x{}, 间隔={}s, 点击={}",
                    url, w, h, interval, blockEntity.isClickEnabled());
        } catch (NumberFormatException e) {
            ProxyWebMod.LOGGER.warn("无效的数字输入: {}", e.getMessage());
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 继续触发截图请求（GUI 打开时也要更新）
        blockEntity.clientUpdate();

        // 1.20.1: renderBackground 只接受 DrawContext 参数
        this.renderBackground(context);

        int centerX = this.width / 2;

        // 标题
        context.drawTextWithShadow(this.textRenderer,
                Text.literal("网页显示设置"), centerX - 40, 20, 0xFFFFFF);

        // 标签
        context.drawTextWithShadow(this.textRenderer,
                Text.literal("网址:"), centerX - 110, 40, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal("宽:"), centerX - 125, 95, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal("高:"), centerX - 55, 95, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal("间隔(秒):"), centerX - 5, 95, 0xAAAAAA);

        // 图片预览标签
        context.drawTextWithShadow(this.textRenderer,
                Text.literal("图片预览:"), centerX - 80, 180, 0xAAAAAA);

        // 渲染图片预览
        renderPreview(context, centerX);

        // 错误状态
        if (blockEntity.hasError()) {
            String err = blockEntity.getErrorState();
            if (err != null && err.length() > 60) err = err.substring(0, 57) + "...";
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("错误: " + err).formatted(Formatting.RED),
                    10, this.height - 20, 0xFF5555);
        }

        // 点击状态
        if (blockEntity.getClickStatus() != null) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("点击: " + blockEntity.getClickStatus())
                            .formatted(Formatting.GOLD),
                    10, this.height - 35, 0xFFAA00);
        }

        // 模拟点击提示
        if (blockEntity.isClickEnabled()) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("左键方块面 → 模拟点击网页")
                            .formatted(Formatting.YELLOW),
                    centerX - 80, 175, 0xFFFF00);
        }

        // 输入框
        urlField.render(context, mouseX, mouseY, delta);
        widthField.render(context, mouseX, mouseY, delta);
        heightField.render(context, mouseX, mouseY, delta);
        intervalField.render(context, mouseX, mouseY, delta);

        super.render(context, mouseX, mouseY, delta);
    }

    /**
     * 渲染图片预览区域。
     * 使用 RenderSystem + Tessellator 直接绘制动态 GL 纹理。
     */
    private void renderPreview(DrawContext context, int centerX) {
        int previewX = centerX - PREVIEW_W / 2;
        int previewY = 195;

        // 触发纹理上传（如有待处理 PNG）
        int glId = blockEntity.getAndUpdateTextureGlId();

        // 绘制边框
        context.fill(previewX - 1, previewY - 1,
                previewX + PREVIEW_W + 1, previewY + PREVIEW_H + 1, 0xFF555555);

        if (glId != 0) {
            // 有纹理：渲染图片
            RenderSystem.setShader(GameRenderer::getPositionTexProgram);
            RenderSystem.setShaderTexture(0, glId);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();

            // 推到前景层，避免被 GUI 背景遮挡
            context.getMatrices().push();
            context.getMatrices().translate(0, 0, 200);
            Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);

            // GUI 坐标系：Y 向下增加
            // UV: (0,0)=左上, (1,0)=右上, (1,1)=右下, (0,1)=左下
            buffer.vertex(matrix, previewX, previewY + PREVIEW_H, 0).texture(0, 1).next();
            buffer.vertex(matrix, previewX + PREVIEW_W, previewY + PREVIEW_H, 0).texture(1, 1).next();
            buffer.vertex(matrix, previewX + PREVIEW_W, previewY, 0).texture(1, 0).next();
            buffer.vertex(matrix, previewX, previewY, 0).texture(0, 0).next();

            tessellator.draw();
            context.getMatrices().pop();

            RenderSystem.disableBlend();
        } else {
            // 无纹理：显示占位符
            context.fill(previewX, previewY,
                    previewX + PREVIEW_W, previewY + PREVIEW_H, 0xFF222222);

            String statusText;
            if (blockEntity.getUrl() == null || blockEntity.getUrl().isEmpty()) {
                statusText = "未设置网址";
            } else if (blockEntity.hasError()) {
                statusText = "加载失败";
            } else if (blockEntity.isRequestInProgress()) {
                statusText = "加载中...";
            } else {
                statusText = "等待截图";
            }

            int textW = this.textRenderer.getWidth(statusText);
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal(statusText),
                    previewX + (PREVIEW_W - textW) / 2,
                    previewY + PREVIEW_H / 2 - 4, 0xAAAAAA);
        }

        // 预览区域下方显示尺寸信息
        String sizeInfo = blockEntity.getTextureWidth() + "x" + blockEntity.getTextureHeight();
        int infoW = this.textRenderer.getWidth(sizeInfo);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(sizeInfo),
                previewX + (PREVIEW_W - infoW) / 2,
                previewY + PREVIEW_H + 4, 0x888888);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
