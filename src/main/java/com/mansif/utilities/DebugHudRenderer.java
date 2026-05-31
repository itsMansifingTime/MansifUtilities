package com.mansif.utilities;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Locale;

public final class DebugHudRenderer {
    public static final Logger LOGGER = MansifUtilities.LOGGER;
    private record TimestampedString(String message, float timestamp) {}

    private static float ticks = 0f;
    private static final ArrayList<TimestampedString> debugMsgAry = new ArrayList<>();
    private static final ArrayList<String> debugMsgAryQuick = new ArrayList<>();

    private static final int X_INITIAL = 10;
    private static final int Y_INITIAL = 200;

    private static final int MESSAGE_TIMEOUT = 5 * 20; // ticks
    private static boolean active = false;

    public static void registerHooks() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MansifUtilities.MOD_ID, "debug_hud_renderer"), hudLayer());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(
                        ClientCommandManager.literal("toggledebughudrenderer")
                                .executes(DebugHudRenderer::toggleCommand)
                )
        );
        // ClientReceiveMessageEvents.GAME.register((message, overlay) -> { // bazaar n stuff is for sure sys, ab gives your hp and defense etc
        //     String tag = overlay ? "[ab] " : "[sys] ";
        //     submitDebug(tag + message.getString());
        // });
        // ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
        //     var n = sender == null? "null" : sender.toString();
        //     submitDebug(message.getString() + " | from " + n);
        // });
    }

    private static int toggleCommand(CommandContext<FabricClientCommandSource> context) {
        active = !active;
        context.getSource().sendFeedback(Component.literal("toggled debug hud renderer " + (active? "on" : "off")));
        submitDebug("toggled debug hud renderer " + (active? "on" : "off"));
        return 1;
    }

    public static HudElement hudLayer() {
        return (drawContext, tickCounter) -> {
            ticks += tickCounter.getRealtimeDeltaTicks();

            // TextRenderer, text (string, or Text object), x, y, color, shadow
            for (int k = 0; k < debugMsgAry.size(); k++) {
                drawContext.drawString(Minecraft.getInstance().font, debugMsgAry.get(k).message, X_INITIAL, Y_INITIAL + 10*k, 0xFFFFFFFF, false);
            }
            for (int k = 0; k < debugMsgAryQuick.size(); k++) {
                drawContext.drawString(Minecraft.getInstance().font, debugMsgAryQuick.get(k), X_INITIAL, Y_INITIAL - 150 + 10*k, 0xFFDDDDFF, false);
            }
            debugMsgAryQuick.clear();
            for (int k = debugMsgAry.size() - 1; k >= 0; k--) {
                if (ticks - debugMsgAry.get(k).timestamp > MESSAGE_TIMEOUT) debugMsgAry.remove(k);
                else break;
            }
        };
    }

    public static void submitDebug(String inp) {
        if (!active) return;
        debugMsgAry.addFirst(new TimestampedString(inp, ticks));
    }

    public static void submitDebug(String inp, boolean log) {
        if (log) LOGGER.info((active? "" : "debug logged silently:") + inp);
        if (!active) return;
        debugMsgAry.addFirst(new TimestampedString(inp, ticks));
    }

    public static void submitQuickDebug(String inp) {
        if (!active) return;
        debugMsgAryQuick.add(inp);
    }

    private DebugHudRenderer() {}
}