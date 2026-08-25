package com.cinemamod.mcef.proxy;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendereregistry.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * 客户端初始化：注册方块实体渲染器、右键 GUI、左键模拟点击。
 *
 * 交互方式：
 *   - 右键方块：打开 GUI 设置界面
 *   - 左键方块（需开启模拟点击）：发送坐标到截图服务，模拟点击网页
 *
 * 左键点击流程：
 *   1. AttackBlockCallback 检测到左键 WebScreenBlock
 *   2. 检查 clickEnabled 和 URL 非空
 *   3. 从 crosshairTarget 获取精确命中位置
 *   4. 换算成网页像素坐标（基于方块朝向和 UV 映射）
 *   5. 发送给截图服务模拟点击，返回新截图
 *   6. 通过 actionbar 显示点击状态
 *
 * 许可证：LGPL-2.1-or-later
 */
public class ProxyWebModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ProxyWebMod.LOGGER.info("Proxy Web Mod 客户端初始化...");

        // 注册方块实体渲染器
        BlockEntityRendererRegistry.INSTANCE.register(
                ProxyWebMod.WEB_SCREEN_BE_TYPE,
                WebScreenBlockEntityRenderer::new
        );

        // 右键方块打开 GUI
        UseBlockCallback.EVENT.register((PlayerEntity player, World world, Hand hand, net.minecraft.util.hit.BlockHitResult hitResult) -> {
            if (!world.isClient) return ActionResult.PASS;
            if (player.isSpectator()) return ActionResult.PASS;

            var state = world.getBlockState(hitResult.getBlockPos());
            if (state.getBlock() == ProxyWebMod.WEB_SCREEN_BLOCK) {
                var be = world.getBlockEntity(hitResult.getBlockPos());
                if (be instanceof WebScreenBlockEntity webBE) {
                    MinecraftClient.getInstance().setScreen(new WebScreenGUI(webBE));
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });

        // 左键方块：模拟点击交互
        AttackBlockCallback.EVENT.register((PlayerEntity player, World world, Hand hand, BlockPos pos, Direction direction) -> {
            if (!world.isClient) return ActionResult.PASS;

            BlockState state = world.getBlockState(pos);
            if (state.getBlock() != ProxyWebMod.WEB_SCREEN_BLOCK) return ActionResult.PASS;

            Direction facing = state.get(WebScreenBlock.FACING);
            // 只处理正面点击（方块朝向的面）
            if (direction != facing) return ActionResult.PASS;

            BlockEntity be = world.getBlockEntity(pos);
            if (!(be instanceof WebScreenBlockEntity webBE)) return ActionResult.PASS;

            // 检查是否启用模拟点击
            if (!webBE.isClickEnabled()) return ActionResult.PASS;

            // 检查 URL 非空
            if (webBE.getUrl() == null || webBE.getUrl().isEmpty()) return ActionResult.PASS;

            // 检查是否已有点击请求在进行
            if (webBE.isClickInProgress()) {
                player.sendMessage(Text.literal("点击请求进行中...")
                        .formatted(Formatting.YELLOW), true);
                return ActionResult.SUCCESS;
            }

            // 从准星获取精确命中位置
            HitResult hitResult = MinecraftClient.getInstance().crosshairTarget;
            if (hitResult instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos)) {
                int[] coords = webBE.calculatePixelCoords(blockHit.getPos(), pos, facing);
                if (coords != null) {
                    webBE.sendClick(coords[0], coords[1]);
                    player.sendMessage(Text.literal("§e发送点击 (" + coords[0] + ", " + coords[1] + ")..."), true);
                    return ActionResult.SUCCESS;
                }
            }

            return ActionResult.PASS;
        });

        ProxyWebMod.LOGGER.info("方块实体渲染器注册完成");
        ProxyWebMod.LOGGER.info("左键点击交互已注册（需在 GUI 中开启模拟点击）");
    }
}
