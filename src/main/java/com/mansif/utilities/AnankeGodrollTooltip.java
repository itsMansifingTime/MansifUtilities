package com.mansif.utilities;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.Optional;

/** Inserts a GODROLL banner on wiki-matching salvage items (similar to exotic armor tags). */
public final class AnankeGodrollTooltip {
    private AnankeGodrollTooltip() {}

    public static void register() {
        ItemTooltipCallback.EVENT.register(AnankeGodrollTooltip::appendGodrollBanner);
    }

    private static void appendGodrollBanner(
            ItemStack stack,
            Item.TooltipContext context,
            TooltipFlag tooltipFlag,
            List<Component> lines) {
        if (stack.isEmpty() || lines.isEmpty()) {
            return;
        }
        Optional<AnankeGodrollLookup.MatchResult> match = AnankeGodrollLookup.match(stack);
        if (match.isEmpty()) {
            return;
        }

        AnankeGodrollLookup.MatchResult row = match.get();
        int insertAt = godrollInsertIndex(lines);
        lines.add(insertAt, bannerLine(row));
        lines.add(insertAt + 1, attributeLine(row));
    }

    /** Place tag directly under the item title (skip blank spacer lines). */
    private static int godrollInsertIndex(List<Component> lines) {
        int i = 1;
        while (i < lines.size() && lines.get(i).getString().isBlank()) {
            i++;
        }
        return i;
    }

    private static Component bannerLine(AnankeGodrollLookup.MatchResult row) {
        MutableComponent line =
                Component.literal("(")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY))
                        .append(
                                Component.literal("✦ ")
                                        .withStyle(
                                                Style.EMPTY
                                                        .withColor(ChatFormatting.LIGHT_PURPLE)
                                                        .withBold(true)))
                        .append(
                                Component.literal("GODROLL")
                                        .withStyle(
                                                Style.EMPTY
                                                        .withColor(ChatFormatting.GOLD)
                                                        .withBold(true)))
                        .append(
                                Component.literal(" ✦")
                                        .withStyle(
                                                Style.EMPTY
                                                        .withColor(ChatFormatting.LIGHT_PURPLE)
                                                        .withBold(true)))
                        .append(
                                Component.literal(" — ")
                                        .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)))
                        .append(
                                Component.literal(row.feathers() + " Ananke")
                                        .withStyle(
                                                Style.EMPTY
                                                        .withColor(ChatFormatting.AQUA)
                                                        .withBold(true)))
                        .append(
                                Component.literal(")")
                                        .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
        return line;
    }

    private static Component attributeLine(AnankeGodrollLookup.MatchResult row) {
        return Component.literal("Attrs: ")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))
                .append(
                        Component.literal(row.attr1())
                                .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                .append(
                        Component.literal(" + ")
                                .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)))
                .append(
                        Component.literal(row.attr2())
                                .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)));
    }

}
