package com.cinemamod.mcef.proxy;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proxy Web Mod — 网页截图代理模组入口。
 *
 * v2.0: 新增自定义方块 WebScreenBlock，右键打开 GUI 输入网址，
 * 模组通过 HTTP 请求截图服务获取网页 PNG 图片，渲染到方块表面。
 *
 * 不依赖任何 CEF / Chromium native 库。
 * 纯 Fabric Java 代码，FCL 可直接加载。
 *
 * 许可证：LGPL-2.1-or-later
 */
public class ProxyWebMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("proxy-web-mod");
    public static final String MOD_ID = "proxy-web-mod";

    public static Block WEB_SCREEN_BLOCK;
    public static BlockItem WEB_SCREEN_ITEM;
    public static BlockEntityType<WebScreenBlockEntity> WEB_SCREEN_BE_TYPE;

    @Override
    public void onInitialize() {
        LOGGER.info("Proxy Web Mod v2.0 初始化中...");
        LOGGER.info("纯 Fabric Java 模组，不依赖 CEF / native 库");

        // 加载配置
        ProxyConfig.load();

        // 注册方块
        WEB_SCREEN_BLOCK = Registry.register(
                Registries.BLOCK,
                new Identifier(MOD_ID, "web_screen"),
                new WebScreenBlock(FabricBlockSettings.create()
                        .mapColor(MapColor.BLACK)
                        .strength(3.5f)
                        .sounds(BlockSoundGroup.STONE))
        );

        // 注册方块物品
        WEB_SCREEN_ITEM = Registry.register(
                Registries.ITEM,
                new Identifier(MOD_ID, "web_screen"),
                new BlockItem(WEB_SCREEN_BLOCK, new Item.Settings())
        );

        // 注册方块实体类型
        WEB_SCREEN_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE,
                new Identifier(MOD_ID, "web_screen"),
                BlockEntityType.Builder.create(WebScreenBlockEntity::new, WEB_SCREEN_BLOCK).build(null)
        );

        // 添加到创造模式物品栏
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.BUILDING_BLOCKS).register(
                entries -> entries.add(WEB_SCREEN_ITEM)
        );

        LOGGER.info("Proxy Web Mod 初始化完成");
        LOGGER.info("截图服务地址: {}", ProxyConfig.get().serviceUrl);
        LOGGER.info("刷新间隔: {}秒, 最大纹理尺寸: {}px",
                ProxyConfig.get().refreshInterval, ProxyConfig.get().maxTextureSize);
    }
}
