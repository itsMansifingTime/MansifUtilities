package com.mansif.utilities;

import com.mansif.utilities.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;

public class InventoryHighlighter {
    private static final int DEFAULT_RED = 0xB0FF0000;

    private static final ArrayList<ColorSlot> renderedSlots = new ArrayList<>();

    private record ColorSlot(Slot slot, int color) {}


    public static void registerHooks() {
        ScreenEvents.AFTER_INIT.register(InventoryHighlighter::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        ScreenEvents.afterBackground(screen).register((scr, drawCtx, mx, my, dt) -> {
            AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) containerScreen;
            drawHighlights(drawCtx, acc.getLeftPos(), acc.getTopPos());
        });
        ScreenEvents.remove(screen).register(scr -> clearAry());
    }

    private static void drawHighlights(GuiGraphics drawCtx, int leftPos, int topPos) {
        if (renderedSlots.isEmpty()) {
            return;
        }
        for (ColorSlot cSlot : renderedSlots) {
            Slot slot = cSlot.slot;
            int x = slot.x + leftPos;
            int y = slot.y + topPos;
            drawCtx.renderOutline(x, y, 16, 16, multAlpha(cSlot.color, 0.9));
            for (int k = 1; k < 8; k++) {
                drawCtx.renderOutline(x + k, y + k, 16 - 2 * k, 16 - 2 * k, multAlpha(cSlot.color, 0.2));
            }
            drawCtx.renderOutline(x, y - 1, 1, 3, 0xFFf9e743);
            drawCtx.renderOutline(x - 1, y, 3, 1, 0xFFf9e743);
            DebugHudRenderer.submitQuickDebug("boxing " + x + ", " + y);
        }
        DebugHudRenderer.submitQuickDebug("rendered: " + leftPos + ", " + topPos);
    }

    private static int multAlpha(int color, double mult) {
        int alpha = (color >>> 24) & 0xFF;
        int newAlpha = (int) (alpha * mult);
        return (color & 0x00FFFFFF) | (newAlpha << 24);
    }

    public static void submitSlotColor(Slot slot, int c) { renderedSlots.addLast(new ColorSlot(slot, c)); }

    public static void submitSlot(Slot slot) { renderedSlots.addLast(new ColorSlot(slot, DEFAULT_RED)); }

    public static void submitSlots(NonNullList<Slot> slots) { for (Slot s : slots) renderedSlots.addLast(new ColorSlot(s, DEFAULT_RED)); }

    public static void clearAry() {
        renderedSlots.clear();
    }
}

