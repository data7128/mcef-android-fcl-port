package com.cinemamod.mcef.proxy;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 网页显示方块 GUI 界面。
 *
 * 功能：
 *   - 输入网页 URL
 *   - 设置截图尺寸（宽度、高度）
 *   - 设置刷新间隔
 *   - 保存并关闭
 *
 * 操作方式：
 *   1. 放置 WebScreenBlock 方块
 *   2. 右键方块打开此 GUI
 *   3. 输入 URL（如 https://www.baidu.com）
 *   4. 调整尺寸和刷新间隔
 *   5. 点击「保存」按钮
 *
 * 许可证：LGPL-2.1-or-later
 */
public class WebScreenGUI extends Screen {
    private final WebScreenBlockEntity blockEntity;

    private TextFieldWidget urlField;
    private TextFieldWidget widthField;
    private TextFieldWidget heightField;
    private TextFieldWidget intervalField;

    private static final int FIELD_WIDTH = 200;
    private static final int FIELD_HEIGHT = 20;
    private static final int SMALL_FIELD_WIDTH = 60;

    public WebScreenGUI(WebScreenBlockEntity blockEntity) {
        super(Text.literal("网页显示设置"));
        this.blockEntity = blockEntity;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;

        // URL 输入框
        urlField = new TextFieldWidget(this.textRenderer,
                centerX - FIELD_WIDTH / 2, 60, FIELD_WIDTH, FIELD_HEIGHT,
                Text.literal("URL"));
        urlField.setMaxLength(512);
        urlField.setText(blockEntity.getUrl());
        addSelectableChild(urlField);

        // 宽度输入框
        widthField = new TextFieldWidget(this.textRenderer,
                centerX - 100, 95, SMALL_FIELD_WIDTH, FIELD_HEIGHT,
                Text.literal("宽"));
        widthField.setText(String.valueOf(blockEntity.getTextureWidth()));
        addSelectableChild(widthField);

        // 高度输入框
        heightField = new TextFieldWidget(this.textRenderer,
                centerX - 20, 95, SMALL_FIELD_WIDTH, FIELD_HEIGHT,
                Text.literal("高"));
        heightField.setText(String.valueOf(blockEntity.getTextureHeight()));
        addSelectableChild(heightField);

        // 刷新间隔输入框
        intervalField = new TextFieldWidget(this.textRenderer,
                centerX + 60, 95, SMALL_FIELD_WIDTH, FIELD_HEIGHT,
                Text.literal("间隔"));
        intervalField.setText(String.valueOf(ProxyConfig.get().refreshInterval));
        addSelectableChild(intervalField);

        // 保存按钮
        addDrawableChild(ButtonWidget.builder(Text.literal("保存"), button -> {
            saveSettings();
            this.close();
        }).dimensions(centerX - 70, 130, 60, 20).build());

        // 取消按钮
        addDrawableChild(ButtonWidget.builder(Text.literal("取消"), button -> {
            this.close();
        }).dimensions(centerX + 10, 130, 60, 20).build());

        // 清除 URL 按钮
        addDrawableChild(ButtonWidget.builder(Text.literal("清除 URL"), button -> {
            blockEntity.setUrl("");
            urlField.setText("");
            this.close();
        }).dimensions(centerX - 70, 155, 140, 20).build());
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

            ProxyWebMod.LOGGER.info("网页显示设置已保存: url={}, {}x{}, 间隔={}s",
                    url, w, h, interval);
        } catch (NumberFormatException e) {
            ProxyWebMod.LOGGER.warn("无效的数字输入: {}", e.getMessage());
        }
    }

    @Override
    public void render(net.minecraft.client.util.math.MatrixStack matrices,
                       int mouseX, int mouseY, float delta) {
        renderBackground(matrices);

        // 标题
        drawTextWithShadow(matrices, this.textRenderer,
                Text.literal("网页显示设置"), width / 2 - 40, 30, 0xFFFFFF);

        // 标签
        drawTextWithShadow(matrices, this.textRenderer,
                Text.literal("网址:"), width / 2 - 130, 65, 0xAAAAAA);
        drawTextWithShadow(matrices, this.textRenderer,
                Text.literal("宽:"), width / 2 - 115, 100, 0xAAAAAA);
        drawTextWithShadow(matrices, this.textRenderer,
                Text.literal("高:"), width / 2 - 35, 100, 0xAAAAAA);
        drawTextWithShadow(matrices, this.textRenderer,
                Text.literal("间隔(秒):"), width / 2 + 20, 100, 0xAAAAAA);

        // 错误状态
        if (blockEntity.hasError()) {
            drawTextWithShadow(matrices, this.textRenderer,
                    Text.literal("错误: " + blockEntity.getErrorState())
                            .formatted(Formatting.RED),
                    10, height - 20, 0xFF5555);
        }

        // 输入框
        urlField.render(matrices, mouseX, mouseY, delta);
        widthField.render(matrices, mouseX, mouseY, delta);
        heightField.render(matrices, mouseX, mouseY, delta);
        intervalField.render(matrices, mouseX, mouseY, delta);

        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        super.close();
    }
}
