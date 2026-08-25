package com.cinemamod.mcef.proxy;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendereregistry.v1.BlockEntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;

/**
 * 客户端初始化：注册方块实体渲染器、右键交互。
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
        UseBlockCallback.EVENT.register((PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) -> {
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

        ProxyWebMod.LOGGER.info("方块实体渲染器注册完成");
    }
}
