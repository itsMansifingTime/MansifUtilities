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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
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

        Thread hypixelSync =
                new Thread(
                        () -> {
                            FlipBridgeConfig cfg = FlipBridgeConfig.loadAndSync();
                            HypixelKeyHelper.syncExpiryFromServer(cfg);
                        },
                        "mansifutilities-hypixel-key-sync");
        hypixelSync.setDaemon(true);
        hypixelSync.start();

        MansifUtilities.LOGGER.info(
                "Flip alert bridge: {}",
                config.isUsable()
                        ? "enabled (polling " + config.apiBase + ")"
                        : "not ready — /mansifbridge sync or /mansifbridge secret <value>");
    }

    private static void onClientTick(Minecraft client) {
        HypixelKeyHelper.tickExpiryReminder(client, config);

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

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 12000;

    private static void pollFeed(Minecraft client) {
        try {
            long sinceQuery = catchUpPoll ? 0L : lastSeenMs;
            catchUpPoll = false;
            List<String> bases = config.pollApiBasesInOrder();
            if (bases.isEmpty()) {
                notifyPollFailure(
                        client, "no API URL — /mansifbridge sync or /mansifbridge direct <url>");
                return;
            }

            Exception lastError = null;
            for (String base : bases) {
                try {
                    pollFeedFromBase(client, base, sinceQuery);
                    return;
                } catch (AuthFailureException e) {
                    notifyPollFailure(client, "feed auth failed (wrong secret?)");
                    return;
                } catch (Exception e) {
                    lastError = e;
                    MansifUtilities.LOGGER.debug("Flip feed failed for {}: {}", base, e.toString());
                }
            }

            String detail =
                    lastError != null && lastError.getMessage() != null
                            ? lastError.getMessage()
                            : "connection failed";
            notifyPollFailure(client, formatPollFailureDetail(bases, detail));
        } finally {
            pollInFlight.set(false);
        }
    }

    private static void pollFeedFromBase(Minecraft client, String base, long sinceQuery)
            throws Exception {
        String url = config.feedUrlForBase(base, sinceQuery);
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Authorization", "Bearer " + config.secret.trim());
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        if (code == 401) {
            throw new AuthFailureException();
        }
        if (code != 200) {
            if (code == 502 && base.contains("vercel.app")) {
                throw new IOException(
                        "HTTP 502 (Vercel cannot reach EC2) — use /mansifbridge direct http://YOUR_IP:3001");
            }
            throw new IOException("HTTP " + code + " from " + base);
        }
        byte[] body = conn.getInputStream().readAllBytes();
        JsonObject root =
                JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
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
            String viewSellerCommand = stringField(flip, "viewSellerCommand");
            String sellerName = stringField(flip, "sellerName");
            if (auctionForCmd == null) continue;
            if (viewCommand == null || viewCommand.isBlank()) {
                viewCommand = "/viewauction " + auctionForCmd;
            }
            if ((viewSellerCommand == null || viewSellerCommand.isBlank())
                    && sellerName != null
                    && !sellerName.isBlank()) {
                viewSellerCommand = "/ah " + sellerName.trim();
            }

            String finalViewCommand = viewCommand;
            String finalViewSellerCommand = viewSellerCommand;
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
                                    finalViewCommand,
                                    finalViewSellerCommand));
        }
        if (maxSeen > lastSeenMs) {
            lastSeenMs = maxSeen;
        }
        while (seenFlipIds.size() > 500) {
            seenFlipIds.clear();
        }
    }

    private static String formatPollFailureDetail(List<String> bases, String detail) {
        String primary = bases.isEmpty() ? "(none)" : bases.get(0);
        String hint =
                primary.contains("vercel.app")
                        ? " Vercel timed out reaching EC2 — use /mansifbridge direct http://YOUR_IP:3001 or /mansifbridge sync after EC2 config update."
                        : " Check EC2 security group (TCP 3001) and pm2 on :3001.";
        return "cannot reach flip API (" + primary + ") — " + detail + "." + hint;
    }

    private static final class AuthFailureException extends Exception {}

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
                            mansifPrefix()
                                    .append(
                                            Component.literal(detail)
                                                    .withStyle(
                                                            ChatFormatting.RED,
                                                            ChatFormatting.BOLD)),
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
            String viewCommand,
            String viewSellerCommand) {
        latestAuctionIdForCommand = auctionIdForCommand;
        latestViewCommand = viewCommand;

        MutableComponent msg =
                mansifPrefix()
                        .append(
                                Component.literal(itemName)
                                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        if (!tag.isBlank()) {
            msg.append(
                    Component.literal(" (" + tag + ")")
                            .withStyle(ChatFormatting.DARK_AQUA));
        }
        msg.append(
                        Component.literal(
                                        " +" + formatCoins(margin) + " under craft, BIN "
                                                + formatCoins(startingBid))
                                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(clickableCommand(" [View]", viewCommand, ChatFormatting.YELLOW));
        if (viewSellerCommand != null && !viewSellerCommand.isBlank()) {
            msg.append(
                    clickableCommand(
                            " [View seller auctions]",
                            viewSellerCommand,
                            ChatFormatting.LIGHT_PURPLE));
        }
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
                        mansifPrefix()
                                .append(
                                        Component.literal("No flip yet — wait for an alert.")
                                                .withStyle(
                                                        ChatFormatting.RED,
                                                        ChatFormatting.BOLD)),
                        false);
                return;
            }
            cmd = "/viewauction " + latestAuctionIdForCommand;
        }
        String withoutSlash = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        client.player.connection.sendCommand(withoutSlash);
    }

    private static MutableComponent mansifPrefix() {
        return Component.literal("[Mansif] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    private static MutableComponent clickableCommand(
            String label, String command, ChatFormatting color) {
        return Component.literal(label)
                .withStyle(
                        style ->
                                style.withColor(color)
                                        .withBold(true)
                                        .withClickEvent(new ClickEvent.RunCommand(command))
                                        .withHoverEvent(
                                                new HoverEvent.ShowText(
                                                        Component.literal(command)
                                                                .withStyle(color, ChatFormatting.BOLD))));
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
