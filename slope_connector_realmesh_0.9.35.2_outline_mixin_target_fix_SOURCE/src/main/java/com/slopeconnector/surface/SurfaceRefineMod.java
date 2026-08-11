package com.slopeconnector.surface;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.slopeconnector.ArcSlopeWandItem;
import com.slopeconnector.SlopeConnectorMod;
import com.slopeconnector.connected.ConnectedArcWandItem;
import com.slopeconnector.model.ModelSystemMod;
import com.slopeconnector.surface.dimensions.ArcDimensionSettings;
import com.slopeconnector.surface.orientation.ArcPlacementOrientationSettings;
import com.slopeconnector.surface.orientation.PlacedOrientationService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Direction;

/**
 * 0.9.24.2 deliberately keeps the 0.9.23 arc-wand implementation intact.
 * The only interaction change is an early gate: the ordinary arc wand may only click Model Blocks.
 * Once the click is accepted, PASS lets ArcSlopeWandItem execute its original selection/generation code.
 */
public final class SurfaceRefineMod implements ModInitializer {
    public static final String MOD_ID = "slopeconnector_surface_refine";

    @Override
    public void onInitialize() {
        PlacedOrientationService.initialize();
        registerModelOnlyGate();
        registerCommands();
    }

    private static void registerModelOnlyGate() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            ItemStack stack = player.getStackInHand(hand);

            if (stack.getItem() instanceof ArcSlopeWandItem) {
                if (!ModelSystemMod.isModelHolder(world.getBlockState(hitResult.getBlockPos()))) {
                    if (!world.isClient()) {
                        player.sendMessage(Text.literal(
                                "弧方块连接杖现在只允许连接模型方块；原两点/三点/方向/弧向/宽度/厚度逻辑保持不变。"), false);
                    }
                    return ActionResult.SUCCESS;
                }

                // Standalone ModelBlocks intentionally have no BlockEntity for performance.  The
                // inner-face metadata is stamped after the original generator succeeds, when the two
                // selected endpoint blocks are promoted to real endpoint BEs.  PASS is essential:
                // the original 0.9.23/0.9.10 wand still owns every selection and geometry transition.
                return ActionResult.PASS;
            }

            // Legacy connected-profile wand is intentionally superseded by Model Block + normal arc wand.
            if (stack.getItem() instanceof ConnectedArcWandItem) {
                if (!world.isClient()) {
                    player.sendMessage(Text.literal(
                            "弧栏杆连接杖已合并：请用弧方块连接杖连接模型方块，再用模型渲染杖获取栏杆/围栏模型。"), false);
                }
                return ActionResult.SUCCESS;
            }

            return ActionResult.PASS;
        });
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("slopeconnector")
                        .then(CommandManager.literal("lrwidth")
                                .then(CommandManager.argument("blocks", IntegerArgumentType.integer(
                                                ArcDimensionSettings.MIN_SIZE,
                                                ArcDimensionSettings.MAX_LEFT_RIGHT))
                                        .executes(context -> {
                                            ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                            int value = ArcDimensionSettings.setLeftRight(player,
                                                    IntegerArgumentType.getInteger(context, "blocks"));
                                            context.getSource().sendFeedback(
                                                    () -> Text.literal("弧带上下厚度：" + value + " 格"), false);
                                            return value;
                                        })))
                        .then(CommandManager.literal("udwidth")
                                .then(CommandManager.argument("blocks", IntegerArgumentType.integer(
                                                ArcDimensionSettings.MIN_SIZE,
                                                ArcDimensionSettings.MAX_UP_DOWN))
                                        .executes(context -> {
                                            ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                            int value = ArcDimensionSettings.setUpDown(player,
                                                    IntegerArgumentType.getInteger(context, "blocks"));
                                            context.getSource().sendFeedback(
                                                    () -> Text.literal("弧带侧面宽度：" + value + " 格"), false);
                                            return value;
                                        })))
                        .then(CommandManager.literal("dimensions")
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                    int lr = ArcDimensionSettings.leftRight(player);
                                    int ud = ArcDimensionSettings.upDown(player);
                                    context.getSource().sendFeedback(
                                            () -> Text.literal("弧带尺寸：上下厚度 " + lr + " 格，侧面宽度 " + ud + " 格"), false);
                                    return 1;
                                }))
                        .then(CommandManager.literal("vieworient")
                                .then(CommandManager.literal("on").executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                    ArcPlacementOrientationSettings.set(player, true);
                                    context.getSource().sendFeedback(() -> Text.literal("视角定向放置：开启"), false);
                                    return 1;
                                }))
                                .then(CommandManager.literal("off").executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                    ArcPlacementOrientationSettings.set(player, false);
                                    context.getSource().sendFeedback(() -> Text.literal("视角定向放置：关闭"), false);
                                    return 1;
                                }))
                                .then(CommandManager.literal("toggle").executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                    boolean value = ArcPlacementOrientationSettings.toggle(player);
                                    context.getSource().sendFeedback(() -> Text.literal("视角定向放置：" + (value ? "开启" : "关闭")), false);
                                    return 1;
                                }))
                                .then(CommandManager.literal("status").executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                    boolean value = ArcPlacementOrientationSettings.enabled(player);
                                    ArcPlacementOrientationSettings.set(player, value);
                                    context.getSource().sendFeedback(() -> Text.literal("视角定向放置：" + (value ? "开启" : "关闭")), false);
                                    return 1;
                                })))
        ));
    }
}
