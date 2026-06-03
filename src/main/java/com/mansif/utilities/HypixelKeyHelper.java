package com.mansif.utilities;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Hypixel API key storage + expiry reminders for flip seller lookup on MansifTracker. */
public final class HypixelKeyHelper {
    private static final long WARN_BEFORE_MS = 3L * 24 * 60 * 60 * 1000L;
    private static final long WARN_COOLDOWN_MS = 6L * 60 * 60 * 1000L;
    private static final int DEFAULT_VALID_DAYS = 3;

    private static long lastExpiryWarnAtMs = 0;

    private HypixelKeyHelper() {}

    public static void saveKey(
            FabricClientCommandSource source, String apiKey, int validDays) {
        if (apiKey == null || apiKey.isBlank()) {
            source.sendError(
                    Component.literal("[MansifUtilities] Hypixel API key cannot be empty.")
                            .withStyle(ChatFormatting.RED));
            return;
        }
        long expiresAtMs =
                System.currentTimeMillis() + (long) validDays * 24L * 60L * 60L * 1000L;
        String keyTrimmed = apiKey.trim();
        FlipBridgeConfig.update(
                cfg -> {
                    cfg.hypixelApiKey = keyTrimmed;
                    cfg.hypixelApiKeyExpiresAtMs = expiresAtMs;
                });

        Minecraft client = source.getClient();
        source.sendFeedback(
                Component.literal(
                                "[MansifUtilities] Saved Hypixel API key locally (expires in "
                                        + validDays
                                        + " day"
                                        + (validDays == 1 ? "" : "s")
                                        + "). Pushing to server in background…")
                        .withStyle(ChatFormatting.YELLOW));
        FlipAlertBridge.reloadConfig();

        FlipBridgeConfig cfg = FlipBridgeConfig.loadAndSync();
        Thread pushThread =
                new Thread(
                        () -> {
                            String pushResult =
                                    pushKeyToServer(cfg, keyTrimmed, expiresAtMs, validDays);
                            client.execute(
                                    () -> {
                                        source.sendFeedback(
                                                Component.literal(
                                                                "[MansifUtilities] "
                                                                        + pushResult)
                                                        .withStyle(
                                                                pushResult.startsWith("Server updated")
                                                                        ? ChatFormatting.GREEN
                                                                        : ChatFormatting.GOLD));
                                        FlipAlertBridge.reloadConfig();
                                    });
                        },
                        "mansifutilities-hypixel-key-push");
        pushThread.setDaemon(true);
        pushThread.start();
    }

    public static void clearKey(FabricClientCommandSource source) {
        FlipBridgeConfig.update(
                cfg -> {
                    cfg.hypixelApiKey = "";
                    cfg.hypixelApiKeyExpiresAtMs = 0L;
                });
        source.sendFeedback(
                Component.literal(
                                "[MansifUtilities] Cleared local Hypixel API key. Server key unchanged — edit data/hypixel-api-config.json on EC2 or set again in-game.")
                        .withStyle(ChatFormatting.YELLOW));
        FlipAlertBridge.reloadConfig();
    }

    public static String hypixelKeyStatusLine(FlipBridgeConfig cfg) {
        if (cfg.hypixelApiKey == null || cfg.hypixelApiKey.isBlank()) {
            return "missing — /mansifbridge hypixel <key>";
        }
        if (cfg.hypixelApiKeyExpiresAtMs <= 0) {
            return "set (" + cfg.hypixelApiKey.length() + " chars), no expiry tracked";
        }
        long msLeft = cfg.hypixelApiKeyExpiresAtMs - System.currentTimeMillis();
        if (msLeft <= 0) {
            return "set but EXPIRED — regenerate at developer.hypixel.net";
        }
        long days = (msLeft + 86_400_000L - 1) / 86_400_000L;
        return "set, expires in ~" + days + " day" + (days == 1 ? "" : "s");
    }

    public static void tickExpiryReminder(Minecraft client, FlipBridgeConfig cfg) {
        if (cfg.hypixelApiKeyExpiresAtMs <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long msLeft = cfg.hypixelApiKeyExpiresAtMs - now;
        if (msLeft > WARN_BEFORE_MS) {
            return;
        }
        if (now - lastExpiryWarnAtMs < WARN_COOLDOWN_MS) {
            return;
        }
        lastExpiryWarnAtMs = now;

        Component msg;
        if (msLeft <= 0) {
            msg =
                    Component.literal("[Mansif] Hypixel API key expired — ")
                            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                            .append(
                                    Component.literal(
                                                    "/mansifbridge hypixel <new-key> — seller buttons need a valid key")
                                            .withStyle(ChatFormatting.YELLOW));
        } else {
            long days = Math.max(1, (msLeft + 86_400_000L - 1) / 86_400_000L);
            msg =
                    Component.literal(
                                    "[Mansif] Hypixel API key expires in ~"
                                            + days
                                            + " day"
                                            + (days == 1 ? "" : "s"))
                            .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                            .append(
                                    Component.literal(
                                                    " — renew at developer.hypixel.net or /mansifbridge hypixel <key>")
                                            .withStyle(ChatFormatting.YELLOW));
        }

        client.execute(
                () -> {
                    if (client.player != null) {
                        client.player.displayClientMessage(msg, false);
                    }
                });
    }

    /** Pull expiry from server when local expiry is unset but bridge is ready. */
    public static void syncExpiryFromServer(FlipBridgeConfig cfg) {
        if (!cfg.isUsable()) {
            return;
        }
        for (String base : FlipBridgeConfig.apiBasesForHypixelKeyPush(cfg)) {
            if (trySyncExpiryFromBase(cfg, base)) {
                return;
            }
        }
    }

    private static boolean trySyncExpiryFromBase(FlipBridgeConfig cfg, String base) {
        try {
            String url = base.trim().replaceAll("/+$", "") + "/api/bin-deal-hypixel-key";
            HttpURLConnection conn =
                    (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("Authorization", "Bearer " + cfg.secret.trim());
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) {
                return false;
            }
            byte[] body = conn.getInputStream().readAllBytes();
            JsonObject root =
                    JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (!root.has("expiresAtMs") || root.get("expiresAtMs").isJsonNull()) {
                return false;
            }
            long expiresAtMs = root.get("expiresAtMs").getAsLong();
            if (expiresAtMs <= 0) {
                return false;
            }
            FlipBridgeConfig.update(
                    local -> {
                        if (local.hypixelApiKeyExpiresAtMs <= 0) {
                            local.hypixelApiKeyExpiresAtMs = expiresAtMs;
                        }
                    });
            return true;
        } catch (Exception e) {
            MansifUtilities.LOGGER.debug("Hypixel key status sync from {} failed: {}", base, e.toString());
            return false;
        }
    }

    private static String pushKeyToServer(
            FlipBridgeConfig cfg, String apiKey, long expiresAtMs, int validDays) {
        if (!cfg.isUsable()) {
            return "Server not updated (set api + secret first).";
        }
        String json =
                "{\"apiKey\":\""
                        + escapeJson(apiKey)
                        + "\",\"expiresAtMs\":"
                        + expiresAtMs
                        + ",\"validDays\":"
                        + validDays
                        + "}";
        for (String base : FlipBridgeConfig.apiBasesForHypixelKeyPush(cfg)) {
            try {
                String url = base.trim().replaceAll("/+$", "") + "/api/bin-deal-hypixel-key";
                HttpURLConnection conn =
                        (HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setRequestProperty("Authorization", "Bearer " + cfg.secret.trim());
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                conn.getOutputStream().write(bytes);
                int code = conn.getResponseCode();
                if (code == 200) {
                    return "Server updated for seller lookups.";
                }
            } catch (Exception e) {
                MansifUtilities.LOGGER.debug(
                        "Hypixel key push to {} failed: {}", base, e.toString());
            }
        }
        return "Server unreachable — key saved locally only.";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static int defaultValidDays() {
        return DEFAULT_VALID_DAYS;
    }
}
