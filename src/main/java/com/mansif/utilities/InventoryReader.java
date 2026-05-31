package com.mansif.utilities;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InventoryReader {
    private static final Logger LOGGER = MansifUtilities.LOGGER;
    private record ScreenSearchBoxes(EditBox searchBox, EditBox searchDataBox) {}

    private static AbstractContainerScreen<?> pendingScreen = null;
    private static int pendingDelayTicks = 0;
    private static final WeakHashMap<Screen, ScreenSearchBoxes> screenSearchBoxes = new WeakHashMap<>();

    private static final ArrayList<String> searches = new ArrayList<>();
    private static final ArrayList<String> customDataSearches = new ArrayList<>();

    public static void registerHooks() {
        // Fires whenever a new screen is initialized on the client
        ScreenEvents.AFTER_INIT.register(InventoryReader::onScreenInit);

        // Tick listener to run a one-shot delayed scan after the screen opens
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingDelayTicks > 0) {
                pendingDelayTicks--;
//                DebugHudRenderer.submitDebug("pending with " + pendingScreen.getRectangle().width()+ ", " + pendingScreen.getRectangle().height());
            }
            if (pendingDelayTicks == 0 && pendingScreen != null) {
                // Ensure the intended screen is still open
                if (client.screen == pendingScreen) {
                    scanScreen(client, pendingScreen);
//                    scanScreenPrinter(client, pendingScreen);
                }
                // Clear pending regardless to avoid repeats
                pendingScreen = null;
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(
                        ClientCommandManager.literal("search")
                                .then(ClientCommandManager.argument("value", StringArgumentType.string())
                                        .executes(InventoryReader::executeSearchCommand))
                )
        );
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(
                        ClientCommandManager.literal("searchdata")
                                .then(ClientCommandManager.argument("value", StringArgumentType.string())
                                        .executes(InventoryReader::executeSearchDataCommand))
                )
        );
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(
                        ClientCommandManager.literal("searchclear")
                                .executes(InventoryReader::executeSearchClearCommand)
                )
        );
    }

    private static int executeSearchCommand(CommandContext<FabricClientCommandSource> context) {
        String value = normalizeSearchInput(StringArgumentType.getString(context, "value"));
        searches.addLast(value);
        context.getSource().sendFeedback(Component.literal("search added for: " + value));
        return 1;
    }

    private static int executeSearchDataCommand(CommandContext<FabricClientCommandSource> context) {
        String value = normalizeSearchInput(StringArgumentType.getString(context, "value"));
        customDataSearches.addLast(value);
        context.getSource().sendFeedback(Component.literal("search added for: " + value));
        return 1;
    }

    private static int executeSearchClearCommand(CommandContext<FabricClientCommandSource> context) {
        clearSavedSearches();
        context.getSource().sendFeedback(Component.literal("cleared searches"));
        return 1;
    }

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        // Only handle container-based screens (chests, crafting tables, etc.)
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            addChestSearchBox(client, screen, scaledWidth, scaledHeight);
            // Defer actual scanning a couple of ticks to allow slot contents to sync from server
            pendingScreen = containerScreen;
            pendingDelayTicks = 2; // small delay to ensure contents are available
        }
    }

    private static void addChestSearchBox(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (client == null || client.font == null) return;
        int width = 120;
        int height = 16;
        int spacing = 6;
        int totalWidth = width * 2 + spacing;
        int x = Math.max(6, (scaledWidth - totalWidth) / 2);
        int y = Math.max(6, scaledHeight - height - 10);

        EditBox searchBox = new ChestSearchField(client.font, x, y, width, height, Component.literal("search name"), searches::addLast);
        searchBox.setHint(Component.literal("search name"));
        searchBox.setBordered(true);
        searchBox.setFocused(false);
        searchBox.setMaxLength(64);
        EditBox searchDataBox = new ChestSearchField(client.font, x + width + spacing, y, width, height, Component.literal("search data"), customDataSearches::addLast);
        searchDataBox.setHint(Component.literal("search data"));
        searchDataBox.setBordered(true);
        searchDataBox.setFocused(false);
        searchDataBox.setMaxLength(64);
        Button clearButton = Button.builder(Component.literal("Clear"), btn -> {
                    clearSavedSearches();
                    searchBox.setValue("");
                    searchDataBox.setValue("");
                    if (screen == client.screen && screen instanceof AbstractContainerScreen<?> containerScreen) {
                        scanScreen(client, containerScreen);
                    }
                })
                .bounds(x + totalWidth + spacing, y - 1, 48, height + 2)
                .build();

        Runnable refreshFromUi = () -> {
            if (screen == client.screen && screen instanceof AbstractContainerScreen<?> containerScreen) {
                scanScreen(client, containerScreen);
            }
        };
        searchBox.setResponder(text -> refreshFromUi.run());
        searchDataBox.setResponder(text -> refreshFromUi.run());

        Screens.getButtons(screen).add(searchBox);
        Screens.getButtons(screen).add(searchDataBox);
        Screens.getButtons(screen).add(clearButton);
        screenSearchBoxes.put(screen, new ScreenSearchBoxes(searchBox, searchDataBox));
        ScreenEvents.remove(screen).register(scr -> screenSearchBoxes.remove(scr));

        if (screen instanceof AbstractContainerScreen<?> handledScreen) {
            // Unfocus our search fields when clicking anywhere else on the screen.
            // We do this from afterRender because it gives us mx/my in screen coordinates.
            final boolean[] prevLeftDown = {false};
            final boolean[] prevRightDown = {false};
            ScreenEvents.afterRender(handledScreen).register((scr, drawCtx, mx, my, dt) -> {
                ScreenSearchBoxes boxes = screenSearchBoxes.get(scr);
                if (boxes == null) return;
                boolean focused = boxes.searchBox().isFocused() || boxes.searchDataBox().isFocused();
                if (!focused) {
                    // Keep state in sync so we detect the next real click.
                    long handle = GLFW.glfwGetCurrentContext();
                    prevLeftDown[0] = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS;
                    prevRightDown[0] = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_2) == GLFW.GLFW_PRESS;
                    return;
                }

                long handle = GLFW.glfwGetCurrentContext();
                boolean leftDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS;
                boolean rightDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_2) == GLFW.GLFW_PRESS;
                boolean leftClicked = leftDown && !prevLeftDown[0];
                boolean rightClicked = rightDown && !prevRightDown[0];
                prevLeftDown[0] = leftDown;
                prevRightDown[0] = rightDown;

                if (!leftClicked && !rightClicked) return;

                boolean overSearch = boxes.searchBox().isMouseOver(mx, my);
                boolean overSearchData = boxes.searchDataBox().isMouseOver(mx, my);
                if (!overSearch && !overSearchData) {
                    boxes.searchBox().setFocused(false);
                    boxes.searchDataBox().setFocused(false);
                }
            });
        }
    }

    private static final class ChestSearchField extends EditBox {
        private final Consumer<String> submitSearch;

        public ChestSearchField(net.minecraft.client.gui.Font font, int x, int y, int width, int height, Component message, Consumer<String> submitSearch) {
            super(font, x, y, width, height, message);
            this.submitSearch = submitSearch;
        }

        @Override
        public boolean keyPressed(KeyEvent input) {
            if (isFocused() && (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER)) {
                String value = normalizeSearchInput(getValue());
                if (!value.isBlank()) {
                    submitSearch.accept(value);
                    setValue("");
                }
                return true;
            }
            // Catch all key presses (except escape) while focused so the screen doesn't close on inventory key.
            return super.keyPressed(input) || (input.key() != GLFW.GLFW_KEY_ESCAPE && this.isFocused());
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
            // Unfocus when clicking outside.
            if (isFocused() && !isMouseOver(click.x(), click.y())) {
                setFocused(false);
                return false;
            }

            if (super.mouseClicked(click, doubled)) {
                setFocused(true);
                return true;
            }
            return false;
        }
    }

    private static String normalizeSearchInput(String value) {
        return value.trim().toLowerCase(); //.replace('_', ' ');
    }

    private static void clearSavedSearches() {
        searches.clear();
        customDataSearches.clear();
    }

    private static void scanScreen(Minecraft client, AbstractContainerScreen<?> containerScreen) {
        long start_time = System.nanoTime();
        InventoryHighlighter.clearAry();
        AbstractContainerMenu menu = containerScreen.getMenu();
        if (menu.slots.isEmpty()) return;
        String[] uiSearchValues = getUiSearchValues(containerScreen);
        String uiSearchValue = uiSearchValues[0];
        String uiSearchDataValue = uiSearchValues[1];
        for (Slot s : menu.slots) {
            ArrayList<String> fields = new ArrayList<>();
            ArrayList<String> customDataFields = new ArrayList<>();
            fields.addLast(s.getItem().getHoverName().getString().toLowerCase());
            boolean[] trues = {false, false};
            for (TypedDataComponent<?> c : s.getItem().getComponents()) {
                String str = c.toString().toLowerCase();
                if (str.startsWith("minecraft:custom_name=>") || str.startsWith("minecraft:lore=>")) fields.addAll(extractLiterals(str));
                else if (str.startsWith("minecraft:custom_data=>")) customDataFields.add(str);
            }
            for (String search : searches) {
                if (searchHelper(search, fields)) {
                    trues[0] = true;
                    break;
                }
            }
            for (String search : customDataSearches) {
                if (searchHelper(search, customDataFields)) {
                    trues[1] = true;
                    break;
                }
            }
            if (!uiSearchValue.isBlank() && searchHelper(uiSearchValue, fields)) {
                trues[0] = true;
            }
            if (!uiSearchDataValue.isBlank() && searchHelper(uiSearchDataValue, customDataFields)) {
                trues[1] = true;
            }
            if (trues[0] || trues[1]) InventoryHighlighter.submitSlotColor(s, (0xFF << 24) | (trues[0]? 0x00FF0000 : 0) | (trues[1]? 0x000000FF : 0));
        }
//        LOGGER.info("scanned inventory in {} ns", System.nanoTime() - start_time);
//        debugItemDataDisplay(menu.slots.getFirst());
    }

    private static String[] getUiSearchValues(Screen screen) {
        ScreenSearchBoxes uiBoxes = screenSearchBoxes.get(screen);
        if (uiBoxes == null) return new String[] {"", ""};
        String searchValue = uiBoxes.searchBox().getValue().trim().toLowerCase();
        String searchDataValue = uiBoxes.searchDataBox().getValue().trim().toLowerCase();
        return new String[] {searchValue, searchDataValue};
    }

    private static boolean searchHelper(String s, ArrayList<String> ary) {
        for (String s1 : ary) {
//            if (!s1.equalsIgnoreCase("air")) DebugHudRenderer.submitDebug("field: " + s1, true);
            if (s1.contains(s)) return true;
        }
        return false;
    }

    private static ArrayList<String> extractLiterals(String inp) {
//        DebugHudRenderer.submitDebug("extracting literals for: " + inp, true);
        Pattern pattern = Pattern.compile("literal\\{([^}]*)}");
        Matcher matcher = pattern.matcher(inp);
        ArrayList<String> results = new ArrayList<>();
        while (matcher.find()) {
            results.add(matcher.group(1));
        }
//        for (String s : results) DebugHudRenderer.submitDebug("result: " + s, true);
        return results;
    }

    public static void debugItemDataDisplay(Slot s) {
        DebugHudRenderer.submitDebug("topleft item components read: ", true);
        for (TypedDataComponent c : s.getItem().getComponents()) {
            DebugHudRenderer.submitDebug(c.toString(), true);
        }

    }

    @Deprecated
    private static void scanScreenPrinter(Minecraft client, AbstractContainerScreen<?> containerScreen) {
        AbstractContainerMenu menu = containerScreen.getMenu();
        Map<Integer, String> counts = new HashMap<>();

        // Identify the player's own inventory so we can exclude those slots
        Container playerInv = client.player != null ? client.player.getInventory() : null;

        InventoryHighlighter.submitSlots(menu.slots);



        for (int k = 0; k < menu.slots.size(); k++) {
            Slot slot = menu.slots.get(k);
            // Skip slots that belong to the player's inventory; we want only the opened container
            if (playerInv != null && slot.container == playerInv) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            String name = stack.getHoverName().getString();
//            int count = stack.getCount();
            counts.put(k, name);
        }

        if (counts.isEmpty()) {
            DebugHudRenderer.submitDebug("Opened inventory: (empty)");
        } else {
            DebugHudRenderer.submitDebug("Opened inventory contains:");
            int shown = 0;
            for (Map.Entry<Integer, String> e : counts.entrySet()) {
                // Limit messages to avoid flooding HUD
                if (shown++ > 15) { // show up to ~16 lines
                    DebugHudRenderer.submitDebug("...and more");
                    break;
                }
                DebugHudRenderer.submitDebug(e.getKey() + ": " + e.getValue());
            }
        }
    }
}
