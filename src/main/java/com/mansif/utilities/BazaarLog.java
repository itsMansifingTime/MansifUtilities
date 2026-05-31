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
Examples (see latest.log):
[Bazaar] Bought 1x Oak Log for 16.0 coins!
[Bazaar] Sold 1x Oak Log for 7.2 coins!
[Bazaar] Claimed 15.7 coins from selling 1x Oak Log at 15.9 each!
[Bazaar] Claimed 1x Oak Log worth 7.3 coins bought for 7.3 each!
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
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String s = message.getString();
            if (!s.contains("[Bazaar]")) return;
            tryParseAndRecord(s);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> saveToDisk());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> saveToDisk());
        Runtime.getRuntime().addShutdownHook(new Thread(BazaarLog::saveToDiskQuietly, "MansifUtilities-bazaar-save"));
    }

    private static void tryParseAndRecord(String raw) {
        int tag = raw.indexOf("[Bazaar]");
        if (tag < 0) return;
        String body = raw.substring(tag + "[Bazaar]".length()).trim();
        long now = System.currentTimeMillis();

        Matcher m = BUY_INSTANT.matcher(body);
        if (m.matches()) {
            int qty = parseQuantity(m.group(1));
            double total = parseCoins(m.group(3));
            addTxn(now, "BUY", m.group(2).trim(), qty, perUnitFromTotal(total, qty), true);
            return;
        }
        m = SELL_INSTANT.matcher(body);
        if (m.matches()) {
            int qty = parseQuantity(m.group(1));
            double total = parseCoins(m.group(3));
            addTxn(now, "SELL", m.group(2).trim(), qty, perUnitFromTotal(total, qty), true);
            return;
        }
        m = SELL_ORDER_CLAIM.matcher(body);
        if (m.matches()) {
            int qty = parseQuantity(m.group(2));
            addTxn(now, "SELL", m.group(3).trim(), qty, round1(parseCoins(m.group(4))), false);
            return;
        }
        m = BUY_ORDER_CLAIM.matcher(body);
        if (m.matches()) {
            int qty = parseQuantity(m.group(1));
            addTxn(now, "BUY", m.group(2).trim(), qty, round1(parseCoins(m.group(4))), false);
        }
    }

    private static void addTxn(
            long epochMs, String kind, String itemName, int quantity, double pricePerUnit, boolean instant) {
        if (quantity <= 0) return;
        TRANSACTIONS.add(new Transaction(epochMs, kind, itemName, quantity, round1(pricePerUnit), instant));
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
