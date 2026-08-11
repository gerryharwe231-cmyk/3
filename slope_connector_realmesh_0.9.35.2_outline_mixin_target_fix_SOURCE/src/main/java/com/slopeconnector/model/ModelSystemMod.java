package com.slopeconnector.model;

import com.slopeconnector.connected.ConnectedArcMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModelSystemMod implements ModInitializer {
    public static final String MOD_ID = "slopeconnector_surface_refine";

    public static final ModelBlock MODEL_BLOCK = new ModelBlock(
            FabricBlockSettings.copyOf(Blocks.WHITE_CONCRETE).strength(0.8f));
    public static final ModelEndpointBlock MODEL_ENDPOINT_BLOCK = new ModelEndpointBlock(
            FabricBlockSettings.copyOf(Blocks.WHITE_CONCRETE).nonOpaque().dropsNothing().strength(0.8f));
    public static final BlockItem MODEL_BLOCK_ITEM = new BlockItem(MODEL_BLOCK, new Item.Settings());
    public static final ModelRenderWandItem MODEL_RENDER_WAND = new ModelRenderWandItem(new Item.Settings().maxCount(1));
    public static BlockEntityType<ModelBlockEntity> MODEL_BLOCK_ENTITY;

    public static boolean isModelHolder(net.minecraft.block.BlockState state) {
        return state != null && (state.getBlock() == MODEL_BLOCK || state.getBlock() == MODEL_ENDPOINT_BLOCK);
    }

    public static boolean isEndpointHolder(net.minecraft.block.BlockState state) {
        return state != null && state.getBlock() == MODEL_ENDPOINT_BLOCK;
    }

    public static Identifier id(String path) { return new Identifier(MOD_ID, path); }

    @Override
    public void onInitialize() {
        Registry.register(Registries.BLOCK, id("model_block"), MODEL_BLOCK);
        Registry.register(Registries.BLOCK, id("model_endpoint"), MODEL_ENDPOINT_BLOCK);
        Registry.register(Registries.ITEM, id("model_block"), MODEL_BLOCK_ITEM);
        Registry.register(Registries.ITEM, id("model_render_wand"), MODEL_RENDER_WAND);
        MODEL_BLOCK_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, id("model_block_entity"),
                FabricBlockEntityTypeBuilder.create(ModelBlockEntity::new, MODEL_ENDPOINT_BLOCK).build(null));

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.BUILDING_BLOCKS).register(entries -> entries.add(MODEL_BLOCK_ITEM));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(MODEL_RENDER_WAND));

        // The legacy connected-profile wand is superseded by Model Block + the normal arc wand.
        // Keep the registered item for world compatibility but hide it from all creative/search tabs.
        ItemGroupEvents.MODIFY_ENTRIES_ALL.register((group, entries) -> {
            entries.getDisplayStacks().removeIf(stack -> stack.isOf(ConnectedArcMod.CONNECTED_ARC_WAND));
            entries.getSearchTabStacks().removeIf(stack -> stack.isOf(ConnectedArcMod.CONNECTED_ARC_WAND));
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("slopeconnector").then(CommandManager.literal("modelclear").executes(context -> {
                    ServerPlayerEntity player=context.getSource().getPlayerOrThrow();
                    if(player.getMainHandStack().getItem() instanceof ModelRenderWandItem) ModelRenderWandItem.clearCaptured(player.getMainHandStack());
                    if(player.getOffHandStack().getItem() instanceof ModelRenderWandItem) ModelRenderWandItem.clearCaptured(player.getOffHandStack());
                    context.getSource().sendFeedback(() -> Text.literal("模型渲染杖当前模型已清空。"), false);
                    return 1;
                }))));
    }
}
