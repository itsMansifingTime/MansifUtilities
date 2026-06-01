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
        FlipBridgeConfig.update(
                cfg -> {
                    cfg.hypixelApiKey = apiKey.trim();
                    cfg.hypixelApiKeyExpiresAtMs = expiresAtMs;
                });

        FlipBridgeConfig cfg = FlipBridgeConfig.loadAndSync();
        String pushResult = pushKeyToServer(cfg, apiKey.trim(), expiresAtMs, validDays);
        source.sendFeedback(
                Component.literal(
                                "[MansifUtilities] Saved Hypixel API key (expires in "
                                        + validDays
                                        + " day"
                                        + (validDays == 1 ? "" : "s")
                                        + "). "
                                        + pushResult)
                        .withStyle(ChatFormatting.GREEN));
        FlipAlertBridge.reloadConfig();
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
        try {
            String url =
                    cfg.apiBase.trim().replaceAll("/+$", "")
                            + "/api/bin-deal-hypixel-key";
            HttpURLConnection conn =
                    (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("Authorization", "Bearer " + cfg.secret.trim());
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) {
                return;
            }
            byte[] body = conn.getInputStream().readAllBytes();
            JsonObject root =
                    JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (!root.has("expiresAtMs") || root.get("expiresAtMs").isJsonNull()) {
                return;
            }
            long expiresAtMs = root.get("expiresAtMs").getAsLong();
            if (expiresAtMs <= 0) {
                return;
            }
            FlipBridgeConfig.update(
                    local -> {
                        if (local.hypixelApiKeyExpiresAtMs <= 0) {
                            local.hypixelApiKeyExpiresAtMs = expiresAtMs;
                        }
                    });
        } catch (Exception e) {
            MansifUtilities.LOGGER.debug("Hypixel key status sync failed: {}", e.toString());
        }
    }

    private static String pushKeyToServer(
            FlipBridgeConfig cfg, String apiKey, long expiresAtMs, int validDays) {
        if (!cfg.isUsable()) {
            return "Server not updated (set api + secret first).";
        }
        try {
            String url =
                    cfg.apiBase.trim().replaceAll("/+$", "")
                            + "/api/bin-deal-hypixel-key";
            String json =
                    "{\"apiKey\":\""
                            + escapeJson(apiKey)
                            + "\",\"expiresAtMs\":"
                            + expiresAtMs
                            + ",\"validDays\":"
                            + validDays
                            + "}";
            HttpURLConnection conn =
                    (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Authorization", "Bearer " + cfg.secret.trim());
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(bytes);
            int code = conn.getResponseCode();
            if (code == 200) {
                return "Server updated for seller lookups.";
            }
            return "Server HTTP " + code + " (key saved locally only).";
        } catch (Exception e) {
            return "Server unreachable (" + e.getMessage() + ") — key saved locally only.";
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static int defaultValidDays() {
        return DEFAULT_VALID_DAYS;
    }
}
