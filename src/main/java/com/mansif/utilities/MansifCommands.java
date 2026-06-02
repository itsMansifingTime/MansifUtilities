package com.mansif.utilities;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public final class MansifCommands {
    private MansifCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) ->
                        dispatcher.register(
                                ClientCommandManager.literal("mansif")
                                        .then(
                                                ClientCommandManager.literal("editgui")
                                                        .executes(
                                                                ctx -> {
                                                                    toggleEditGui(ctx.getSource());
                                                                    return 1;
                                                                }))
                                        .then(buildAnankeCommand())));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<
                    net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>
            buildAnankeCommand() {
        return ClientCommandManager.literal("ananke")
                .then(
                        ClientCommandManager.literal("resetgui")
                                .executes(
                                        ctx -> {
                                            resetGui(ctx.getSource());
                                            return 1;
                                        }))
                .then(
                        ClientCommandManager.literal("gui")
                                .executes(
                                        ctx -> {
                                            showGui(ctx.getSource());
                                            return 1;
                                        }))
                .then(
                        ClientCommandManager.literal("hoveroffset")
                                .then(
                                        ClientCommandManager.argument(
                                                        "pixels",
                                                        IntegerArgumentType.integer(0, 200))
                                                .executes(
                                                        ctx -> {
                                                            int px =
                                                                    IntegerArgumentType.getInteger(
                                                                            ctx, "pixels");
                                                            setHoverOffset(ctx.getSource(), px);
                                                            return 1;
                                                        })))
                .then(
                        ClientCommandManager.literal("debug")
                                .executes(
                                        ctx -> {
                                            AnankeFeatherDisplay.debugHoveredSlot(ctx.getSource());
                                            return 1;
                                        }));
    }

    private static void toggleEditGui(FabricClientCommandSource source) {
        boolean on = AnankeFeatherDisplay.toggleEditMode();
        if (on) {
            source.sendFeedback(
                    Component.literal("[Mansif] Ananke GUI edit mode ")
                            .withStyle(ChatFormatting.GOLD)
                            .append(Component.literal("ON").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                            .append(
                                    Component.literal(
                                                    "\n  Open a chest and drag the overlay. Arrow keys nudge (hold Shift for hover lines). Run ")
                                            .withStyle(ChatFormatting.GRAY))
                            .append(
                                    Component.literal("/mansif editgui")
                                            .withStyle(ChatFormatting.YELLOW))
                            .append(Component.literal(" again to save & exit.").withStyle(ChatFormatting.GRAY)));
        } else {
            AnankeGuiConfig.save(AnankeGuiConfig.get());
            source.sendFeedback(
                    Component.literal("[Mansif] Ananke GUI edit mode ")
                            .withStyle(ChatFormatting.GOLD)
                            .append(Component.literal("OFF").withStyle(ChatFormatting.RED))
                            .append(
                                    Component.literal(" — layout saved to ")
                                            .withStyle(ChatFormatting.GRAY))
                            .append(
                                    Component.literal(AnankeGuiConfig.configPath().getFileName().toString())
                                            .withStyle(ChatFormatting.AQUA)));
        }
    }

    private static void resetGui(FabricClientCommandSource source) {
        AnankeGuiConfig.reset();
        AnankeFeatherDisplay.reloadLayout();
        source.sendFeedback(
                Component.literal("[Mansif] Reset Ananke overlay position to defaults.")
                        .withStyle(ChatFormatting.GREEN));
    }

    private static void showGui(FabricClientCommandSource source) {
        AnankeGuiConfig cfg = AnankeGuiConfig.get();
        source.sendFeedback(
                Component.literal("[Mansif] Ananke GUI: ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(
                                Component.literal(
                                                "offsetX="
                                                        + cfg.offsetX
                                                        + " offsetY="
                                                        + cfg.offsetY
                                                        + " hoverOffsetY="
                                                        + cfg.hoverOffsetY)
                                        .withStyle(ChatFormatting.WHITE))
                        .append(
                                Component.literal("\n  File: " + AnankeGuiConfig.configPath())
                                        .withStyle(ChatFormatting.DARK_GRAY)));
    }

    private static void setHoverOffset(FabricClientCommandSource source, int px) {
        AnankeGuiConfig.update(cfg -> cfg.hoverOffsetY = px);
        source.sendFeedback(
                Component.literal("[Mansif] hoverOffsetY=" + px).withStyle(ChatFormatting.GREEN));
    }
}
