package com.mansif.utilities;

import com.mansif.utilities.mixin.AbstractContainerScreenAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

/**
 * Renders the name of the item currently hovered in any container screen
 * (chests, crafting tables, etc.) near the mouse cursor.
 */
public final class ItemHoverDisplay {
    private static boolean keybindPrev = false;

    private static int xMin = 0;
    private static int yMin = 0;

    // Last computed hovered slot from the most recent afterRender
    private static Slot hoveredSlot = null;
    private static boolean locked = false;

    public static void registerHooks() {
        ScreenEvents.AFTER_INIT.register(ItemHoverDisplay::onScreenInit);
        KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath("minecraft", "misc"));
        KeyMapping sendToChatKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping(
                        "key.MansifUtilities.inspect_item", // The translation key for the key mapping.
                        InputConstants.Type.KEYSYM, // // The type of the keybinding; KEYSYM for keyboard, MOUSE for mouse.
                        GLFW.GLFW_KEY_J, // The GLFW keycode of the key.
                        CATEGORY // The category of the mapping.
                ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean isDown = sendToChatKey.isDown();
            if (Minecraft.getInstance().screen != null) {
                var window = Minecraft.getInstance().getWindow();
                InputConstants.Key boundKey = KeyBindingHelper.getBoundKeyOf(sendToChatKey);
                if (boundKey.getType() == InputConstants.Type.KEYSYM) {  // Keyboard key: use its GLFW keycode from the binding (respects rebinds)
                    isDown = isDown || InputConstants.isKeyDown(window, boundKey.getValue());
                } else if (boundKey.getType() == InputConstants.Type.MOUSE) { // Mouse button: poll GLFW directly so it works while screens are open
                    long handle = GLFW.glfwGetCurrentContext();
                    int button = boundKey.getValue();
                    isDown = isDown || GLFW.glfwGetMouseButton(handle, button) == GLFW.GLFW_PRESS;
                }
            }
            if (isDown && !keybindPrev) inspectTrigger();
            keybindPrev = isDown;
        }); //                                TODO: why are we not using screen.KeyPressed????
    }

    private static void inspectTrigger() {
//        DebugHudRenderer.submitDebug("rising edge detected!");
        if (!locked && hoveredSlot == null) return;
        else locked = !locked;
    }

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            // Track the screen layout and mouse position every frame, compute hovered slot name
            ScreenEvents.afterRender(screen).register((scr, drawCtx, mx, my, dt) -> {
                AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) containerScreen;
                xMin = acc.getLeftPos() - 1;
                yMin = acc.getTopPos() - 1;
                Slot slot = getSlotAt(containerScreen, mx, my);
                if (!locked) hoveredSlot = (slot != null && !slot.getItem().isEmpty()) ? slot : null;

                // Draw directly during the screen's afterRender so we render above the container layer
                if (hoveredSlot != null) {
                    String hoveredName = hoveredSlot.getItem().getHoverName().getString();
                    int drawX = xMin + acc.getImageWidth() + 30;
                    int drawY = yMin - 80;

                    var font = Minecraft.getInstance().font;
                    int outline = 0xFF000000;
                    drawCtx.drawString(font, hoveredName, drawX + 1, drawY, outline, false);
                    drawCtx.drawString(font, hoveredName, drawX - 1, drawY, outline, false);
                    drawCtx.drawString(font, hoveredName, drawX, drawY + 1, outline, false);
                    drawCtx.drawString(font, hoveredName, drawX, drawY - 1, outline, false);
                    drawCtx.drawString(font, hoveredName, drawX, drawY, 0xFFFFFFFF, false);
                }
            });
            // Clear when screen is removed
            ScreenEvents.remove(screen).register(scr -> hoveredSlot = null);
        }
    }

    private static Slot getSlotAt(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        // Iterate all slots and find the first that intersects mouse coordinates.
        // Slot positions are relative to the screen's left/top.
        for (Slot s : screen.getMenu().slots) {
            int x = xMin + s.x;
            int y = yMin + s.y;
            if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
                return s;
            }
        }
        return null;
    }

    private ItemHoverDisplay() {}
}
