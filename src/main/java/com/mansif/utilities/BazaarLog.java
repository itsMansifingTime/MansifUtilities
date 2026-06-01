package com.mansif.utilities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * Recorded fills only (4 patterns):
 *
 * BUY instant:
 *   [Bazaar] Bought 1x ✎ Flawless Sapphire Gemstone for 2,741,172 coins!
 * BUY order claim (filled buy order):
 *   [Bazaar] Claimed 27x ✎ Flawless Sapphire Gemstone worth 68,404,740 coins bought for 2,533,509 each!
 *
 * SELL instant:
 *   [Bazaar] Sold 1x Crude Gabagool for 210.1 coins!
 * SELL order claim (filled sell order):
 *   [Bazaar] Claimed 1.0 coins from selling 1x Crude Gabagool at 1.0 each!
 *
 * Ignored (not a fill):
 *   [Bazaar] Sell Offer Setup! 1x Counter-Strike V for 55,139,901 coins.
 *   [Bazaar] Buy Offer Setup! ...
 */

public final class BazaarLog {
    public static final Logger LOGGER = MansifUtilities.LOGGER;

    /**
     * BUY or SELL from the player's perspective.
     * {@code pricePerUnit} is coins per item, rounded to one decimal place.
     * {@code instant} is true for instant buy/sell chat lines; false for filled buy/sell orders (claims).
     */
    public record Transaction(
            long epochMs, String kind, String itemName, int quantity, double pricePerUnit, boolean instant) {}

    private static final Path DATA_PATH =
            FabricLoader.getInstance().getConfigDir().resolve(MansifUtilities.MOD_ID).resolve("bazaar_transactions.json");

    private static final List<Transaction> TRANSACTIONS = Collections.synchronizedList(new ArrayList<>());

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type TX_LIST_TYPE = new TypeToken<List<Transaction>>() {}.getType();

    private static final Pattern BUY_INSTANT =
            Pattern.compile("^Bought ([\\d,]+)x (.+?) for ([\\d,]+(?:\\.\\d+)?) coins[!.]?\\s*$");
    private static final Pattern SELL_INSTANT =
            Pattern.compile("^Sold ([\\d,]+)x (.+?) for ([\\d,]+(?:\\.\\d+)?) coins[!.]?\\s*$");
    private static final Pattern SELL_ORDER_CLAIM =
            Pattern.compile("^Claimed ([\\d,]+(?:\\.\\d+)?) coins from selling ([\\d,]+)x (.+?) at ([\\d,]+(?:\\.\\d+)?) each[!.]?\\s*$");
    private static final Pattern BUY_ORDER_CLAIM =
            Pattern.compile("^Claimed ([\\d,]+)x (.+?) worth ([\\d,]+(?:\\.\\d+)?) coins bought for ([\\d,]+(?:\\.\\d+)?) each[!.]?\\s*$");

    private BazaarLog() {}

    public static void registerHooks() {
        loadFromDisk();
        Thread backfill =
                new Thread(
                        () -> {
                            FlipBridgeConfig cfg = FlipBridgeConfig.loadAndSync();
                            if (cfg.isUsable()) {
                                synchronized (TRANSACTIONS) {
                                    BazaarPortfolioSync.enqueueAll(
                                            new ArrayList<>(TRANSACTIONS));
                                }
                            }
                        },
                        "mansifutilities-bazaar-backfill");
        backfill.setDaemon(true);
        backfill.start();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String s = message.getString();
            if (!s.contains("[Bazaar]")) return;
            tryParseAndRecord(s);
        });
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    BazaarPortfolioSync.flush();
                    saveToDisk();
                });
        ClientLifecycleEvents.CLIENT_STOPPING.register(
                client -> {
                    BazaarPortfolioSync.flush();
                    saveToDisk();
                });
        Runtime.getRuntime().addShutdownHook(new Thread(BazaarLog::saveToDiskQuietly, "MansifUtilities-bazaar-save"));
    }

    /** Order placement/cancel chat — not an instant fill or claim. */
    private static boolean isIgnoredBazaarNotice(String body) {
        return body.contains("Offer Setup")
                || body.startsWith("Cancelled ")
                || body.startsWith("Canceled ")
                || body.contains(" offer cancelled")
                || body.contains(" offer canceled");
    }

    private static void tryParseAndRecord(String raw) {
        int tag = raw.indexOf("[Bazaar]");
        if (tag < 0) return;
        String body = raw.substring(tag + "[Bazaar]".length()).trim();
        if (isIgnoredBazaarNotice(body)) {
            return;
        }
        long now = System.currentTimeMillis();

        Matcher         m = BUY_INSTANT.matcher(body);
        if (m.matches()) {
            int qty = parseQuantity(m.group(1));
            double total = parseCoins(m.group(3));
            addTxn(
                    now,
                    "BUY",
                    normalizeItemName(m.group(2)),
                    qty,
                    perUnitFromTotal(total, qty),
                    true);
            return;
        }
        m = SELL_INSTANT.matcher(body);
        if (m.matches()) {
            int qty = parseQuantity(m.group(1));
            double total = parseCoins(m.group(3));
            addTxn(
                    now,
                    "SELL",
                    normalizeItemName(m.group(2)),
                    qty,
                    perUnitFromTotal(total, qty),
                    true);
            return;
        }
        m = BUY_ORDER_CLAIM.matcher(body);
        if (m.matches()) {
            int qty = parseQuantity(m.group(1));
            addTxn(
                    now,
                    "BUY",
                    normalizeItemName(m.group(2)),
                    qty,
                    round1(parseCoins(m.group(4))),
                    false);
            return;
        }
        m = SELL_ORDER_CLAIM.matcher(body);
        if (m.matches()) {
            int qty = parseQuantity(m.group(2));
            addTxn(
                    now,
                    "SELL",
                    normalizeItemName(m.group(3)),
                    qty,
                    round1(parseCoins(m.group(4))),
                    false);
        }
    }

    /** Strip chat glyphs (e.g. ✎) before the real item name. */
    static String normalizeItemName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) break;
            i++;
        }
        return s.substring(i).trim();
    }

    private static final java.util.Set<String> HAB_MATERIALS =
            java.util.Set.of(
                    "Chili Pepper",
                    "Stuffed Chili Pepper",
                    "Enchanted Brown Mushroom Block",
                    "Enchanted Rabbit Hide",
                    "Plasma");

    private static boolean isHabCraftMaterial(String itemName) {
        return HAB_MATERIALS.contains(itemName);
    }

    private static void addTxn(
            long epochMs, String kind, String itemName, int quantity, double pricePerUnit, boolean instant) {
        if (quantity <= 0) return;
        if (isHabCraftMaterial(itemName)) {
            return;
        }
        Transaction tx =
                new Transaction(epochMs, kind, itemName, quantity, round1(pricePerUnit), instant);
        TRANSACTIONS.add(tx);
        BazaarPortfolioSync.enqueue(tx);
    }

    /** Coins per unit from a line total, rounded to one decimal. */
    private static double perUnitFromTotal(double totalCoins, int quantity) {
        return round1(totalCoins / quantity);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double parseCoins(String n) {
        return Double.parseDouble(n.replace(",", ""));
    }

    private static int parseQuantity(String n) {
        return Integer.parseInt(n.replace(",", ""));
    }

    private static void loadFromDisk() {
        try {
            if (!Files.isRegularFile(DATA_PATH)) return;
            String json = Files.readString(DATA_PATH, StandardCharsets.UTF_8);
            List<Transaction> loaded = GSON.fromJson(json, TX_LIST_TYPE);
            if (loaded != null && !loaded.isEmpty()) TRANSACTIONS.addAll(loaded);
        } catch (Exception e) {
            LOGGER.warn("Could not load bazaar transaction log from {}, starting fresh: {}", DATA_PATH, e.toString());
        }
    }

    private static void saveToDiskQuietly() {
        try {
            saveToDisk();
        } catch (Throwable t) {
            LOGGER.warn("Shutdown hook bazaar save failed: {}", t.toString());
        }
    }

    private static void saveToDisk() {
        synchronized (TRANSACTIONS) {
            try {
                Files.createDirectories(DATA_PATH.getParent());
                Files.writeString(DATA_PATH, GSON.toJson(TRANSACTIONS), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.error("Failed to save bazaar log to {}", DATA_PATH, e);
            }
        }
    }
}
