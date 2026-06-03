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
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
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
import java.util.ArrayList;
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
    private static boolean manualPollFeedbackPending = false;
    private static long lastPollErrorChatAtMs = 0;
    private static String lastPollErrorSignature = "";
    private static volatile boolean startupLaunched = false;
    private static volatile String preferredFeedBase;

    private static KeyMapping viewFlipKey;

    public static String preferredFeedBase() {
        return preferredFeedBase;
    }

    public static String lastPollErrorForStatus() {
        return lastPollErrorSignature.isBlank() ? "(none)" : lastPollErrorSignature;
    }

    /** Re-run host probes and refresh preferred feed base (also runs on first in-world tick). */
    public static void requestStartup(Minecraft client) {
        startupLaunched = false;
        maybeRunStartup(client);
    }

    public static void reloadConfig() {
        config = FlipBridgeConfig.loadAndSync();
    }

    public static boolean reloadConfigForPoll() {
        reloadConfig();
        return config.isUsable();
    }

    public static void requestCatchUpPoll() {
        catchUpPoll = true;
        manualPollFeedbackPending = true;
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
        if (client.player != null) {
            maybeRunStartup(client);
        }

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

        if (client.player == null) {
            return;
        }
        if (isFlipKeyBlocked(client.screen)) {
            drainFlipKeyClicks();
            return;
        }
        if (viewFlipKey.consumeClick()) {
            runViewAuction(client);
        }
    }

    /** Chat and container GUIs (player inv, chests, AH, etc.) — never steal keys there. */
    private static boolean isFlipKeyBlocked(Screen screen) {
        return screen instanceof ChatScreen
                || screen instanceof AbstractContainerScreen<?>;
    }

    /** Drop presses made while typing or in an inventory so they do not fire on close. */
    private static void drainFlipKeyClicks() {
        while (viewFlipKey.consumeClick()) {
            // discard
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 18000;

    private static void maybeRunStartup(Minecraft client) {
        if (startupLaunched) {
            return;
        }
        startupLaunched = true;
        Thread startupThread =
                new Thread(
                        () -> {
                            FlipBridgeConfig cfg = FlipBridgeConfig.loadAndSync();
                            FlipBridgeHealth.StartupResult result =
                                    FlipBridgeHealth.runStartup(cfg);
                            config = cfg;
                            if (result.chosenFeedBase() != null) {
                                preferredFeedBase = result.chosenFeedBase();
                            }
                            client.execute(
                                    () -> {
                                        if (client.player == null) {
                                            return;
                                        }
                                        for (String line : result.chatLines()) {
                                            ChatFormatting color =
                                                    result.ready()
                                                            ? ChatFormatting.GREEN
                                                            : line.contains("NOT ready")
                                                                    ? ChatFormatting.RED
                                                                    : ChatFormatting.YELLOW;
                                            client.player.displayClientMessage(
                                                    mansifPrefix()
                                                            .append(
                                                                    Component.literal(line)
                                                                            .withStyle(color)),
                                                    false);
                                        }
                                    });
                        },
                        "mansifutilities-flip-startup");
        startupThread.setDaemon(true);
        startupThread.start();
    }

    private static List<String> pollBasesOrdered() {
        List<String> bases = new ArrayList<>(config.pollApiBasesForFeed());
        if (preferredFeedBase != null && !preferredFeedBase.isBlank()) {
            bases.remove(preferredFeedBase);
            bases.add(0, preferredFeedBase);
        }
        return bases;
    }

    private static void pollFeed(Minecraft client) {
        try {
            long sinceQuery = catchUpPoll ? 0L : lastSeenMs;
            catchUpPoll = false;
            List<String> bases = pollBasesOrdered();
            boolean manualEarly = manualPollFeedbackPending;
            if (bases.isEmpty()) {
                notifyPollFailure(
                        client,
                        "",
                        "No API URL configured — run /mansifbridge startup or /mansifbridge direct https://api.mansif.dev",
                        manualEarly);
                return;
            }

            Exception lastError = null;
            boolean manual = manualPollFeedbackPending;
            String lastEmptyBase = null;
            PollResult lastEmptyResult = null;
            int emptyHostsTried = 0;
            StringBuilder attemptLog = new StringBuilder();
            for (String base : bases) {
                try {
                    PollResult result = pollFeedFromBase(client, base, sinceQuery);
                    if (result.feedCount > 0 || result.newFlips > 0) {
                        lastPollErrorSignature = "";
                        preferredFeedBase = base;
                        if (manual) {
                            notifyPollSuccess(client, base, sinceQuery, result, null);
                        }
                        return;
                    }
                    emptyHostsTried++;
                    lastEmptyBase = base;
                    lastEmptyResult = result;
                    appendAttempt(attemptLog, base, "0 flips", result.serverHint);
                    MansifUtilities.LOGGER.debug(
                            "Flip feed empty on {} ({} hosts tried so far)", base, emptyHostsTried);
                } catch (AuthFailureException e) {
                    notifyPollFailure(
                            client,
                            base,
                            FlipBridgeHealth.formatFailureForChat(base, "HTTP 401 auth failed"),
                            manual);
                    return;
                } catch (Exception e) {
                    lastError = e;
                    appendAttempt(
                            attemptLog,
                            base,
                            e.getMessage() != null ? e.getMessage() : "failed",
                            null);
                    MansifUtilities.LOGGER.debug("Flip feed failed for {}: {}", base, e.toString());
                }
            }

            if (lastEmptyResult != null && lastEmptyBase != null) {
                lastPollErrorSignature = "";
                preferredFeedBase = lastEmptyBase;
                String triedNote = buildAttemptSummary(attemptLog, emptyHostsTried);
                if (manual) {
                    notifyPollSuccess(client, lastEmptyBase, sinceQuery, lastEmptyResult, triedNote);
                }
                return;
            }

            String failedBase = bases.isEmpty() ? "" : bases.get(0);
            String rawDetail =
                    lastError != null && lastError.getMessage() != null
                            ? lastError.getMessage()
                            : "connection failed";
            notifyPollFailure(
                    client,
                    failedBase,
                    FlipBridgeHealth.formatFailureForChat(failedBase, rawDetail),
                    manual);
        } finally {
            pollInFlight.set(false);
        }
    }

    private record PollResult(int feedCount, int newFlips, String serverHint) {}

    private static PollResult pollFeedFromBase(Minecraft client, String base, long sinceQuery)
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
            String apiMsg =
                    parseJsonErrorOrHint(
                            new String(readResponseBody(conn, false), StandardCharsets.UTF_8));
            if (code == 502 && base.contains("vercel.app")) {
                throw new IOException(
                        "HTTP 502 (Vercel cannot reach EC2)"
                                + (apiMsg != null ? " — " + apiMsg : "")
                                + " — use /mansifbridge direct https://api.mansif.dev");
            }
            throw new IOException(
                    "HTTP " + code + " from " + base + (apiMsg != null ? " — " + apiMsg : ""));
        }
        byte[] body = readResponseBody(conn, true);
        JsonObject root =
                JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        if (root.has("ok") && root.get("ok").isJsonPrimitive() && !root.get("ok").getAsBoolean()) {
            String err = stringField(root, "hint");
            if (err == null || err.isBlank()) {
                err = stringField(root, "error");
            }
            throw new IOException(err != null ? err : "feed unavailable");
        }
        String serverHint = stringField(root, "hint");
        if (!root.has("flips") || !root.get("flips").isJsonArray()) {
            return new PollResult(0, 0, serverHint);
        }
        JsonArray flips = root.getAsJsonArray("flips");
        int feedCount = flips.size();
        int newFlips = 0;
        long maxSeen = lastSeenMs;
        for (JsonElement el : flips) {
            if (!el.isJsonObject()) continue;
            JsonObject flip = el.getAsJsonObject();
            String id = stringField(flip, "id");
            if (id == null || !seenFlipIds.add(id)) continue;
            newFlips++;
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
        return new PollResult(feedCount, newFlips, serverHint);
    }

    private static void notifyPollSuccess(
            Minecraft client,
            String base,
            long sinceQuery,
            PollResult result,
            String extraNote) {
        manualPollFeedbackPending = false;
        int newFlips = result.newFlips;
        client.execute(
                () -> {
                    if (client.player == null) {
                        return;
                    }
                    ChatFormatting color =
                            newFlips > 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
                    String detail = explainPollOutcome(base, sinceQuery, result);
                    if (extraNote != null && !extraNote.isBlank()) {
                        detail = detail + " " + extraNote;
                    }
                    client.player.displayClientMessage(
                            mansifPrefix()
                                    .append(
                                            Component.literal("Poll via " + base + " — " + detail)
                                                    .withStyle(color)),
                            false);
                });
    }

    private static void appendAttempt(
            StringBuilder log, String base, String status, String serverHint) {
        if (log.length() > 0) {
            log.append(" | ");
        }
        log.append(shortBaseLabel(base)).append(": ").append(status);
        if (serverHint != null && !serverHint.isBlank()) {
            log.append(" (").append(truncate(serverHint, 80)).append(")");
        }
    }

    private static String buildAttemptSummary(StringBuilder attemptLog, int emptyHostsTried) {
        if (attemptLog.length() == 0) {
            return emptyHostsTried > 0 ? "Checked " + emptyHostsTried + " hosts; all empty." : null;
        }
        return truncate(attemptLog.toString(), 220);
    }

    private static String shortBaseLabel(String base) {
        if (base.contains("vercel.app")) {
            return "Vercel";
        }
        if (base.contains("mansif.dev")) {
            return "api.mansif.dev";
        }
        if (base.startsWith("http://127.0.0.1") || base.startsWith("http://localhost")) {
            return "localhost";
        }
        int slash = base.indexOf("://");
        if (slash >= 0) {
            String rest = base.substring(slash + 3);
            int path = rest.indexOf('/');
            return path >= 0 ? rest.substring(0, path) : rest;
        }
        return base;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 3) + "...";
    }

    private static String explainPollOutcome(String base, long sinceQuery, PollResult result) {
        int feedCount = result.feedCount;
        int newFlips = result.newFlips;
        if (feedCount == 0) {
            if (result.serverHint != null && !result.serverHint.isBlank()) {
                return "0 flips — " + result.serverHint;
            }
            return "0 flips in feed. They are appended on EC2 when BIN Discord alerts fire "
                    + "(data/bin-deal-ingame-feed.json on the box running :3001). "
                    + "Vercel is often empty — use /mansifbridge direct http://YOUR_IP:3001.";
        }
        if (newFlips == 0) {
            if (sinceQuery == 0L) {
                return feedCount
                        + " in feed, 0 new in chat — already shown this session "
                        + "(restart MC to re-print) or entries missing auctionId.";
            }
            return feedCount
                    + " in feed, 0 new in chat — all older than since="
                    + sinceQuery
                    + " (use /mansifbridge poll for catch-up with since=0).";
        }
        return feedCount + " in feed, " + newFlips + " new in chat.";
    }

    private static byte[] readResponseBody(HttpURLConnection conn, boolean successStream)
            throws IOException {
        var stream = successStream ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) {
            return new byte[0];
        }
        return stream.readAllBytes();
    }

    private static String parseJsonErrorOrHint(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(bodyText).getAsJsonObject();
            String error = stringField(root, "error");
            if (error != null && !error.isBlank()) {
                return error;
            }
            return stringField(root, "hint");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class AuthFailureException extends Exception {}

    private static void notifyPollFailure(
            Minecraft client, String failedBase, String detail, boolean manual) {
        long now = System.currentTimeMillis();
        if (!manual) {
            if (detail.equals(lastPollErrorSignature)) {
                return;
            }
            if (now - lastPollErrorChatAtMs < 120_000L && !lastPollErrorSignature.isBlank()) {
                return;
            }
            lastPollErrorSignature = detail;
        } else {
            lastPollErrorSignature = detail;
        }
        if (manual) {
            manualPollFeedbackPending = false;
        }
        lastPollErrorChatAtMs = now;
        MansifUtilities.LOGGER.warn("Flip poll failed ({}): {}", failedBase, detail);
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
