package com.mansif.utilities;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls MansifTracker BIN deal feed and prints flips to client chat.
 * Keybind runs Hypixel {@code /viewauction} for the latest flip.
 */
public final class FlipAlertBridge {
    private static FlipBridgeConfig config = FlipBridgeConfig.loadAndSync();
    private static long lastSeenMs = System.currentTimeMillis();
    private static long lastPollAtMs = 0;
    private static String latestAuctionIdForCommand;
    private static String latestViewCommand;
    private static final Set<String> seenFlipIds = new HashSet<>();
    private static final AtomicBoolean pollInFlight = new AtomicBoolean(false);
    /** First poll uses {@code since=0} so alerts from before MC launched are not missed. */
    private static boolean catchUpPoll = true;
    private static long lastPollErrorChatAtMs = 0;

    private static boolean viewKeyPrev = false;
    private static KeyMapping viewFlipKey;

    public static void reloadConfig() {
        config = FlipBridgeConfig.loadAndSync();
    }

    public static boolean reloadConfigForPoll() {
        reloadConfig();
        return config.isUsable();
    }

    public static void requestCatchUpPoll() {
        catchUpPoll = true;
        lastPollAtMs = 0;
    }

    public static void registerHooks() {
        config = FlipBridgeConfig.loadAndSync();
        Thread syncThread =
                new Thread(
                        () -> {
                            FlipBridgeConfig.syncFromServer(config);
                            reloadConfig();
                        },
                        "mansifutilities-flip-config-sync");
        syncThread.setDaemon(true);
        syncThread.start();

        KeyMapping.Category category =
                new KeyMapping.Category(Identifier.fromNamespaceAndPath(MansifUtilities.MOD_ID, "flip_alerts"));
        viewFlipKey =
                KeyBindingHelper.registerKeyBinding(
                        new KeyMapping(
                                "key.mansifutilities.view_flip_auction",
                                InputConstants.Type.KEYSYM,
                                GLFW.GLFW_KEY_V,
                                category));

        ClientTickEvents.END_CLIENT_TICK.register(FlipAlertBridge::onClientTick);
        MansifUtilities.LOGGER.info(
                "Flip alert bridge: {}",
                config.isUsable()
                        ? "enabled (polling " + config.apiBase + ")"
                        : "not ready — /mansifbridge sync or /mansifbridge secret <value>");
    }

    private static void onClientTick(Minecraft client) {
        if (config.isUsable()) {
            long now = System.currentTimeMillis();
            int interval = Math.max(500, config.pollIntervalMs);
            if (now - lastPollAtMs >= interval && pollInFlight.compareAndSet(false, true)) {
                lastPollAtMs = now;
                Thread pollThread =
                        new Thread(() -> pollFeed(client), "mansifutilities-flip-poll");
                pollThread.setDaemon(true);
                pollThread.start();
            }
        }

        boolean isDown = viewFlipKey.isDown();
        if (client.screen != null && client.getWindow() != null) {
            InputConstants.Key boundKey = KeyBindingHelper.getBoundKeyOf(viewFlipKey);
            if (boundKey.getType() == InputConstants.Type.KEYSYM) {
                isDown =
                        isDown
                                || InputConstants.isKeyDown(
                                        client.getWindow(), boundKey.getValue());
            } else if (boundKey.getType() == InputConstants.Type.MOUSE) {
                long handle = GLFW.glfwGetCurrentContext();
                isDown =
                        isDown
                                || GLFW.glfwGetMouseButton(handle, boundKey.getValue())
                                        == GLFW.GLFW_PRESS;
            }
        }
        if (isDown && !viewKeyPrev) {
            runViewAuction(client);
        }
        viewKeyPrev = isDown;
    }

    private static void pollFeed(Minecraft client) {
        try {
            long sinceQuery = catchUpPoll ? 0L : lastSeenMs;
            catchUpPoll = false;
            String url = config.feedUrl(sinceQuery);
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Authorization", "Bearer " + config.secret.trim());
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                MansifUtilities.LOGGER.warn("Flip feed HTTP {} from {}", code, config.apiBase);
                notifyPollFailure(
                        client,
                        code == 401
                                ? "feed auth failed (wrong secret?)"
                                : "feed HTTP " + code);
                return;
            }
            byte[] body = conn.getInputStream().readAllBytes();
            JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("flips") || !root.get("flips").isJsonArray()) {
                return;
            }
            JsonArray flips = root.getAsJsonArray("flips");
            long maxSeen = lastSeenMs;
            for (JsonElement el : flips) {
                if (!el.isJsonObject()) continue;
                JsonObject flip = el.getAsJsonObject();
                String id = stringField(flip, "id");
                if (id == null || !seenFlipIds.add(id)) continue;
                long sentAt = flip.has("sentAtMs") ? flip.get("sentAtMs").getAsLong() : 0L;
                if (sentAt > maxSeen) maxSeen = sentAt;

                String itemName = stringField(flip, "itemName");
                String tag = stringField(flip, "tag");
                long margin = flip.has("margin") ? flip.get("margin").getAsLong() : 0L;
                long startingBid =
                        flip.has("startingBid") ? flip.get("startingBid").getAsLong() : 0L;
                String auctionForCmd = stringField(flip, "auctionIdForCommand");
                String viewCommand = stringField(flip, "viewCommand");
                if (auctionForCmd == null) continue;
                if (viewCommand == null || viewCommand.isBlank()) {
                    viewCommand = "/viewauction " + auctionForCmd;
                }

                String finalViewCommand = viewCommand;
                String finalAuctionForCmd = auctionForCmd;
                client.execute(
                        () ->
                                onNewFlip(
                                        client,
                                        itemName != null ? itemName : "Flip",
                                        tag != null ? tag : "",
                                        margin,
                                        startingBid,
                                        finalAuctionForCmd,
                                        finalViewCommand));
            }
            if (maxSeen > lastSeenMs) {
                lastSeenMs = maxSeen;
            }
            while (seenFlipIds.size() > 500) {
                seenFlipIds.clear();
            }
        } catch (Exception e) {
            MansifUtilities.LOGGER.warn("Flip feed poll failed: {}", e.toString());
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            notifyPollFailure(
                    client,
                    "cannot reach API ("
                            + config.apiBase
                            + ") — "
                            + msg
                            + ". Open port 3001 or use SSH tunnel.");
        } finally {
            pollInFlight.set(false);
        }
    }

    private static void notifyPollFailure(Minecraft client, String detail) {
        long now = System.currentTimeMillis();
        if (now - lastPollErrorChatAtMs < 30_000L) {
            return;
        }
        lastPollErrorChatAtMs = now;
        client.execute(
                () -> {
                    if (client.player == null) {
                        return;
                    }
                    client.player.displayClientMessage(
                            Component.literal("[Flip] " + detail)
                                    .withStyle(ChatFormatting.RED),
                            false);
                });
    }

    private static void onNewFlip(
            Minecraft client,
            String itemName,
            String tag,
            long margin,
            long startingBid,
            String auctionIdForCommand,
            String viewCommand) {
        latestAuctionIdForCommand = auctionIdForCommand;
        latestViewCommand = viewCommand;

        MutableComponent msg =
                Component.literal("[Flip] ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(itemName).withStyle(ChatFormatting.WHITE));
        if (!tag.isBlank()) {
            msg.append(Component.literal(" (" + tag + ")").withStyle(ChatFormatting.GRAY));
        }
        msg.append(
                        Component.literal(
                                        " +" + formatCoins(margin) + " under craft, BIN "
                                                + formatCoins(startingBid))
                                .withStyle(ChatFormatting.GREEN))
                .append(
                        Component.literal(" [View]")
                                .withStyle(
                                        style ->
                                                style.withColor(ChatFormatting.AQUA)
                                                        .withClickEvent(
                                                                new ClickEvent.RunCommand(
                                                                        viewCommand))
                                                        .withHoverEvent(
                                                                new HoverEvent.ShowText(
                                                                        Component.literal(
                                                                                viewCommand)))));
        if (client.player != null) {
            client.player.displayClientMessage(msg, false);
        }
    }

    private static void runViewAuction(Minecraft client) {
        if (client.player == null) {
            return;
        }
        String cmd = latestViewCommand;
        if (cmd == null || cmd.isBlank()) {
            if (latestAuctionIdForCommand == null || latestAuctionIdForCommand.isBlank()) {
                client.player.displayClientMessage(
                        Component.literal("[Flip] No flip yet — wait for an alert.")
                                .withStyle(ChatFormatting.RED),
                        false);
                return;
            }
            cmd = "/viewauction " + latestAuctionIdForCommand;
        }
        String withoutSlash = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        client.player.connection.sendCommand(withoutSlash);
    }

    private static String stringField(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    private static String formatCoins(long coins) {
        if (coins >= 1_000_000_000L) {
            return String.format(Locale.US, "%.2fB", coins / 1_000_000_000.0);
        }
        if (coins >= 1_000_000L) {
            return String.format(Locale.US, "%.1fM", coins / 1_000_000.0);
        }
        if (coins >= 1_000L) {
            return String.format(Locale.US, "%.0fK", coins / 1_000.0);
        }
        return Long.toString(coins);
    }

    private FlipAlertBridge() {}
}
