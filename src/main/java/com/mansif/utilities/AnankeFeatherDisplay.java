package com.mansif.utilities;

import com.mansif.utilities.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/**
 * Shows Ananke Feather salvage value for hovered godrolls and totals for the open chest.
 * Layout: {@link AnankeGuiConfig}; drag to reposition with {@code /mansif editgui}.
 */
public final class AnankeFeatherDisplay {
    private static final int PANEL_WIDTH = 148;
    private static final int PANEL_PAD = 4;
    private static final int CHEST_SUMMARY_HEIGHT = 34;
    private static final int HOVER_BLOCK_HEIGHT = 22;
    private static final int DEFAULT_ANCHOR_X = 8;
    private static final int DEFAULT_ANCHOR_Y = 4;

    private static Slot hoveredSlot = null;
    private static boolean editMode = false;
    private static boolean draggingPanel = false;
    private static boolean draggingHover = false;
    private static double dragStartMouseX;
    private static double dragStartMouseY;
    private static int dragStartOffsetX;
    private static int dragStartOffsetY;
    private static int dragStartHoverOffsetY;

    private AnankeFeatherDisplay() {}

    public static void registerHooks() {
        ScreenEvents.AFTER_INIT.register(AnankeFeatherDisplay::onScreenInit);
    }

    public static void reloadLayout() {
        AnankeGuiConfig.load();
    }

    public static boolean toggleEditMode() {
        editMode = !editMode;
        draggingPanel = false;
        draggingHover = false;
        if (!editMode) {
            AnankeGuiConfig.save(AnankeGuiConfig.get());
        }
        return editMode;
    }

    public static boolean isEditMode() {
        return editMode;
    }

    public static boolean isDragging() {
        return draggingPanel || draggingHover;
    }

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;

        ScreenMouseEvents.allowMouseClick(screen)
                .register(
                        (scr, click) -> {
                            if (isDragging()) {
                                return false;
                            }
                            if (!editMode || click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                                return true;
                            }
                            int[] panel = panelBounds(containerScreen);
                            int[] hover = hoverBounds(containerScreen);
                            if (contains(panel, click.x(), click.y())) {
                                beginPanelDrag(click);
                                return false;
                            }
                            if (contains(hover, click.x(), click.y())) {
                                beginHoverDrag(click);
                                return false;
                            }
                            return true;
                        });

        // Chest panel, slot badges, and hover lines render with the container (under tooltips).
        ScreenEvents.afterBackground(screen)
                .register(
                        (scr, drawCtx, mx, my, dt) -> {
                            AbstractContainerScreenAccessor acc =
                                    (AbstractContainerScreenAccessor) containerScreen;
                            int left = acc.getLeftPos();
                            int top = acc.getTopPos();

                            if (editMode) {
                                updateDrag(mx, my);
                            }

                            hoveredSlot =
                                    editMode
                                            ? null
                                            : findSlotAt(containerScreen, left, top, mx, my);

                            int panelX = panelX(acc);
                            int panelY = panelY(acc);

                            ChestTotals totals =
                                    editMode
                                            ? new ChestTotals(42, 6)
                                            : sumContainerGodrolls(client, containerScreen);

                            HoverSalvageLines hoverLines = HoverSalvageLines.none();
                            if (editMode) {
                                hoverLines =
                                        new HoverSalvageLines(
                                                "Salvage: 9 Ananke", "Mana Pool + Mana Regen", null);
                            } else if (hoveredSlot != null && !hoveredSlot.getItem().isEmpty()) {
                                hoverLines = hoverSalvageLines(hoveredSlot.getItem());
                            }

                            int hoverY = panelY + AnankeGuiConfig.get().hoverOffsetY;
                            int panelHeight = panelHeight(totals, editMode, hoverLines);
                            drawPanelBackdrop(drawCtx, panelX, panelY, panelHeight);
                            drawChestPanel(drawCtx, panelX, panelY, totals, editMode);

                            if (editMode) {
                                drawOutlined(
                                        drawCtx,
                                        Component.literal(hoverLines.line1()),
                                        panelX + PANEL_PAD,
                                        hoverY,
                                        0xFFFFD966);
                                drawOutlined(
                                        drawCtx,
                                        Component.literal(hoverLines.line2()),
                                        panelX + PANEL_PAD,
                                        hoverY + 10,
                                        0xFFAAAAAA);
                                drawEditChrome(drawCtx, panelX, panelY, hoverY, panelHeight);
                            } else if (!hoverLines.isEmpty()) {
                                int textX = panelX + PANEL_PAD;
                                if (hoverLines.line1() != null) {
                                    drawOutlined(
                                            drawCtx,
                                            Component.literal(hoverLines.line1()),
                                            textX,
                                            hoverY,
                                            0xFFFFD966);
                                }
                                if (hoverLines.line2() != null) {
                                    drawOutlined(
                                            drawCtx,
                                            Component.literal(hoverLines.line2()),
                                            textX,
                                            hoverY + 10,
                                            0xFFAAAAAA);
                                }
                                if (hoverLines.debugLine() != null) {
                                    int debugColor =
                                            hoverLines.debugLine().startsWith("Shift")
                                                    ? 0xFF888888
                                                    : 0xFF88CCFF;
                                    drawOutlined(
                                            drawCtx,
                                            Component.literal(hoverLines.debugLine()),
                                            textX,
                                            hoverY + (hoverLines.line1() != null ? 22 : 12),
                                            debugColor);
                                }
                            }

                            if (!editMode) {
                                drawSlotFeatherBadges(
                                        drawCtx,
                                        containerScreen,
                                        left,
                                        top,
                                        client,
                                        hoveredSlot);
                            }
                        });

        ScreenKeyboardEvents.afterKeyPress(screen)
                .register(
                        (scr, input) -> {
                            if (!editMode && input.key() == GLFW.GLFW_KEY_K && Minecraft.getInstance().hasControlDown()) {
                                debugHoveredToChat(client);
                                return;
                            }
                            if (!editMode) return;
                            boolean shift = Minecraft.getInstance().hasShiftDown();
                            int step = shift ? 5 : 1;
                            AnankeGuiConfig cfg = AnankeGuiConfig.get();
                            boolean changed = false;
                            if (shift) {
                                switch (input.key()) {
                                    case GLFW.GLFW_KEY_UP -> {
                                        cfg.hoverOffsetY -= step;
                                        changed = true;
                                    }
                                    case GLFW.GLFW_KEY_DOWN -> {
                                        cfg.hoverOffsetY += step;
                                        changed = true;
                                    }
                                    default -> {}
                                }
                            } else {
                                switch (input.key()) {
                                    case GLFW.GLFW_KEY_LEFT -> {
                                        cfg.offsetX -= step;
                                        changed = true;
                                    }
                                    case GLFW.GLFW_KEY_RIGHT -> {
                                        cfg.offsetX += step;
                                        changed = true;
                                    }
                                    case GLFW.GLFW_KEY_UP -> {
                                        cfg.offsetY -= step;
                                        changed = true;
                                    }
                                    case GLFW.GLFW_KEY_DOWN -> {
                                        cfg.offsetY += step;
                                        changed = true;
                                    }
                                    default -> {}
                                }
                            }
                            if (changed) {
                                cfg.hoverOffsetY = Math.max(0, Math.min(200, cfg.hoverOffsetY));
                                AnankeGuiConfig.save(cfg);
                            }
                        });

        ScreenEvents.remove(screen)
                .register(
                        scr -> {
                            hoveredSlot = null;
                            draggingPanel = false;
                            draggingHover = false;
                        });
    }

    private static int panelX(AbstractContainerScreenAccessor acc) {
        return acc.getLeftPos() + acc.getImageWidth() + DEFAULT_ANCHOR_X + AnankeGuiConfig.get().offsetX;
    }

    private static int panelY(AbstractContainerScreenAccessor acc) {
        return acc.getTopPos() + DEFAULT_ANCHOR_Y + AnankeGuiConfig.get().offsetY;
    }

    private static int[] panelBounds(AbstractContainerScreen<?> screen) {
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int y = panelY(acc);
        return new int[] {panelX(acc), y, PANEL_WIDTH, panelHeight(new ChestTotals(0, 0), editMode, HoverSalvageLines.none())};
    }

    private static int[] hoverBounds(AbstractContainerScreen<?> screen) {
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int x = panelX(acc);
        int y = panelY(acc) + AnankeGuiConfig.get().hoverOffsetY;
        return new int[] {x, y, PANEL_WIDTH, HOVER_BLOCK_HEIGHT};
    }

    private static boolean contains(int[] rect, double mx, double my) {
        return mx >= rect[0]
                && mx < rect[0] + rect[2]
                && my >= rect[1]
                && my < rect[1] + rect[3];
    }

    private static void beginPanelDrag(MouseButtonEvent click) {
        draggingPanel = true;
        draggingHover = false;
        dragStartMouseX = click.x();
        dragStartMouseY = click.y();
        AnankeGuiConfig cfg = AnankeGuiConfig.get();
        dragStartOffsetX = cfg.offsetX;
        dragStartOffsetY = cfg.offsetY;
    }

    private static void beginHoverDrag(MouseButtonEvent click) {
        draggingHover = true;
        draggingPanel = false;
        dragStartMouseY = click.y();
        dragStartHoverOffsetY = AnankeGuiConfig.get().hoverOffsetY;
    }

    private static void updateDrag(double mx, double my) {
        long handle = GLFW.glfwGetCurrentContext();
        boolean leftDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS;
        if (!leftDown) {
            if (draggingPanel || draggingHover) {
                AnankeGuiConfig.save(AnankeGuiConfig.get());
            }
            draggingPanel = false;
            draggingHover = false;
            return;
        }
        AnankeGuiConfig cfg = AnankeGuiConfig.get();
        if (draggingPanel) {
            cfg.offsetX = dragStartOffsetX + (int) Math.round(mx - dragStartMouseX);
            cfg.offsetY = dragStartOffsetY + (int) Math.round(my - dragStartMouseY);
        } else if (draggingHover) {
            cfg.hoverOffsetY =
                    Math.max(0, Math.min(200, dragStartHoverOffsetY + (int) Math.round(my - dragStartMouseY)));
        }
    }

    private static void drawEditChrome(GuiGraphics drawCtx, int panelX, int panelY, int hoverY, int panelHeight) {
        int border = 0xAAFFFF00;
        drawCtx.fill(panelX - 2, panelY - 2, panelX + PANEL_WIDTH + 2, panelY + panelHeight + 2, border);
        drawCtx.fill(panelX - 2, hoverY - 2, panelX + PANEL_WIDTH + 2, hoverY + HOVER_BLOCK_HEIGHT + 2, 0xAA00CCFF);
        drawOutlined(
                drawCtx,
                Component.literal("Editing — drag boxes"),
                panelX + PANEL_PAD,
                panelY + panelHeight + 4,
                0xFF88FF88);
    }

    private record ChestTotals(int feathers, int godrollItems) {}

    private record HoverSalvageLines(String line1, String line2, String debugLine) {
        static HoverSalvageLines none() {
            return new HoverSalvageLines(null, null, null);
        }

        boolean isEmpty() {
            return line1 == null && line2 == null && debugLine == null;
        }
    }

    private static HoverSalvageLines hoverSalvageLines(ItemStack stack) {
        SkyblockStackData.Parsed parsed = SkyblockStackData.parse(stack);
        Optional<AnankeGodrollLookup.MatchResult> match = AnankeGodrollLookup.matchParsed(parsed);
        String line1 = null;
        String line2 = null;
        String debug = null;
        if (match.isPresent()) {
            line1 = "Salvage: " + match.get().feathers() + " Ananke";
            line2 = match.get().attr1() + " + " + match.get().attr2();
        }
        if (Minecraft.getInstance().hasShiftDown()) {
            debug = AnankeGodrollLookup.formatDebug(parsed);
        } else if (match.isEmpty() && parsed.itemId() != null && KNOWN_KUUDRA(parsed.itemId())) {
            debug = "Shift for attr debug";
        }
        return new HoverSalvageLines(line1, line2, debug);
    }

    private static int panelHeight(ChestTotals totals, boolean editing, HoverSalvageLines hover) {
        int h = CHEST_SUMMARY_HEIGHT;
        if (editing || !hover.isEmpty()) {
            int hoverY = AnankeGuiConfig.get().hoverOffsetY;
            int hoverBottom = hoverY + HOVER_BLOCK_HEIGHT;
            if (hover.debugLine() != null) {
                hoverBottom += hover.line1() != null ? 22 : 12;
                hoverBottom += 10;
            }
            h = Math.max(h, hoverBottom + PANEL_PAD);
        }
        return h + PANEL_PAD;
    }

    private static void drawPanelBackdrop(GuiGraphics drawCtx, int x, int y, int height) {
        drawCtx.fill(x - 1, y - 1, x + PANEL_WIDTH + 1, y + height + 1, 0xCC101010);
        drawCtx.fill(x, y, x + PANEL_WIDTH, y + height, 0xAA000000);
    }

    private static void drawSlotFeatherBadges(
            GuiGraphics drawCtx,
            AbstractContainerScreen<?> screen,
            int left,
            int top,
            Minecraft client,
            Slot skipSlot) {
        var font = Minecraft.getInstance().font;
        Container playerInv = client.player != null ? client.player.getInventory() : null;
        for (Slot slot : screen.getMenu().slots) {
            if (playerInv != null && slot.container == playerInv) continue;
            if (skipSlot != null && slot == skipSlot) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            Optional<AnankeGodrollLookup.MatchResult> match = AnankeGodrollLookup.match(stack);
            if (match.isEmpty()) continue;
            String label = String.valueOf(match.get().feathers());
            int sx = left + slot.x + 17 - font.width(label);
            int sy = top + slot.y + 1;
            drawCtx.drawString(font, label, sx + 1, sy + 1, 0xFF000000, false);
            drawCtx.drawString(font, label, sx, sy, 0xFFFFEE55, false);
        }
    }

    private static boolean KNOWN_KUUDRA(String itemId) {
        return itemId.contains("AURORA")
                || itemId.contains("CRIMSON")
                || itemId.contains("TERROR")
                || itemId.contains("THUNDER")
                || itemId.contains("MAGMA_LORD")
                || itemId.contains("MOLTEN")
                || itemId.contains("HOLLOW")
                || itemId.contains("FERVOR")
                || itemId.contains("BURNING")
                || itemId.contains("HEAT");
    }

    public static void debugHoveredSlot(FabricClientCommandSource source) {
        if (hoveredSlot == null || hoveredSlot.getItem().isEmpty()) {
            source.sendFeedback(Component.literal("[Mansif] Hover a container item first."));
            return;
        }
        source.sendFeedback(
                Component.literal(
                        "[Mansif] "
                                + AnankeGodrollLookup.formatDebug(
                                        SkyblockStackData.parse(hoveredSlot.getItem()))));
    }

    private static void debugHoveredToChat(Minecraft client) {
        if (client.player == null) return;
        if (hoveredSlot == null || hoveredSlot.getItem().isEmpty()) {
            client.player.displayClientMessage(Component.literal("[Mansif] Hover an item, then Ctrl+K"), false);
            return;
        }
        client.player.displayClientMessage(
                Component.literal("[Mansif] " + AnankeGodrollLookup.formatDebug(SkyblockStackData.parse(hoveredSlot.getItem()))),
                false);
    }

    private static ChestTotals sumContainerGodrolls(Minecraft client, AbstractContainerScreen<?> screen) {
        int feathers = 0;
        int items = 0;
        Container playerInv = client.player != null ? client.player.getInventory() : null;

        for (Slot slot : screen.getMenu().slots) {
            if (playerInv != null && slot.container == playerInv) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            Optional<AnankeGodrollLookup.MatchResult> match = AnankeGodrollLookup.match(stack);
            if (match.isPresent()) {
                feathers += match.get().feathers();
                items++;
            }
        }
        return new ChestTotals(feathers, items);
    }

    private static void drawChestPanel(GuiGraphics drawCtx, int x, int y, ChestTotals totals, boolean editing) {
        int textX = x + PANEL_PAD;
        int textY = y + PANEL_PAD;
        drawOutlined(drawCtx, Component.literal("Ananke salvage"), textX, textY, 0xFFFFAA00);
        if (editing) {
            drawOutlined(drawCtx, Component.literal("Chest: 42 feathers"), textX, textY + 12, 0xFFFFFFFF);
            drawOutlined(drawCtx, Component.literal("6 godrolls (preview)"), textX, textY + 22, 0xFFAAAAAA);
            return;
        }
        if (totals.godrollItems() == 0) {
            drawOutlined(drawCtx, Component.literal("No godrolls"), textX, textY + 12, 0xFF888888);
            return;
        }
        drawOutlined(
                drawCtx,
                Component.literal("Chest: " + totals.feathers() + " feathers"),
                textX,
                textY + 12,
                0xFFFFFFFF);
        drawOutlined(
                drawCtx,
                Component.literal(totals.godrollItems() + " godroll" + (totals.godrollItems() == 1 ? "" : "s")),
                textX,
                textY + 22,
                0xFFAAAAAA);
    }

    private static Slot findSlotAt(AbstractContainerScreen<?> screen, int left, int top, double mouseX, double mouseY) {
        for (Slot s : screen.getMenu().slots) {
            int x = left + s.x;
            int y = top + s.y;
            if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
                return s;
            }
        }
        return null;
    }

    private static void drawOutlined(GuiGraphics drawCtx, Component text, int x, int y, int color) {
        var font = Minecraft.getInstance().font;
        int outline = 0xFF000000;
        String s = text.getString();
        drawCtx.drawString(font, s, x + 1, y, outline, false);
        drawCtx.drawString(font, s, x - 1, y, outline, false);
        drawCtx.drawString(font, s, x, y + 1, outline, false);
        drawCtx.drawString(font, s, x, y - 1, outline, false);
        drawCtx.drawString(font, text, x, y, color, false);
    }
}
