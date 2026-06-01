package com.mansif.utilities;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Live Habanero Tactics IV craft cost from MansifTracker.
 *
 * <p>Uses only {@code /hab} and {@code /mansifbridge hab}. Does not register {@code /recipe} or
 * {@code /re} — those names must stay free for Hypixel's recipe command.
 */
public final class RecipeCommands {
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) ->
                        dispatcher.register(
                                ClientCommandManager.literal("hab")
                                        .executes(RecipeCommands::showHabanero)));
    }

    public static int showHabanero(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        Minecraft client = source.getClient();
        FlipBridgeConfig cfg = FlipBridgeConfig.loadAndSync();

        source.sendFeedback(
                mansifPrefix()
                        .append(
                                Component.literal(" Fetching Hab craft cost…")
                                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));

        Thread fetchThread =
                new Thread(
                        () -> {
                            JsonObject result = fetchHabaneroCost(cfg);
                            client.execute(
                                    () -> {
                                        if (result == null) {
                                            source.sendError(
                                                    Component.literal(
                                                                    "[Mansif] Could not reach MansifTracker — set /mansifbridge direct <ip> [port] or /mansifbridge api <url>")
                                                            .withStyle(ChatFormatting.RED));
                                            return;
                                        }
                                        if (!result.has("ok") || !result.get("ok").getAsBoolean()) {
                                            String err =
                                                    result.has("error")
                                                            ? result.get("error").getAsString()
                                                            : "Unknown error";
                                            source.sendError(
                                                    Component.literal("[Mansif] " + err)
                                                            .withStyle(ChatFormatting.RED));
                                            return;
                                        }
                                        displayHabaneroRecipe(client, result);
                                    });
                        },
                        "mansifutilities-recipe-hab");
        fetchThread.setDaemon(true);
        fetchThread.start();
        return 1;
    }

    private static JsonObject fetchHabaneroCost(FlipBridgeConfig cfg) {
        List<String> bases = cfg.pollApiBasesInOrder();
        if (bases.isEmpty()) {
            bases = List.of("https://mansiftracker.vercel.app", "http://127.0.0.1:3001");
        }
        for (String base : bases) {
            JsonObject parsed = fetchHabaneroFromBase(base);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static JsonObject fetchHabaneroFromBase(String base) {
        String url = base.replaceAll("/+$", "") + "/api/habanero-tactics";
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(body);
            int code = conn.getResponseCode();
            if (code != 200) {
                MansifUtilities.LOGGER.debug("Hab recipe HTTP {} from {}", code, url);
                return null;
            }
            String response =
                    new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(response).getAsJsonObject();
        } catch (Exception e) {
            MansifUtilities.LOGGER.debug("Hab recipe fetch from {} failed: {}", url, e.toString());
            return null;
        }
    }

    private static void displayHabaneroRecipe(Minecraft client, JsonObject data) {
        if (client.player == null) {
            return;
        }

        String bookName =
                data.has("bookName") ? data.get("bookName").getAsString() : "Habanero Tactics IV";
        long totalCost = data.get("totalCraftCost").getAsLong();
        long marketProfit = data.get("marketProfitAfterTax").getAsLong();
        long instaSellProfit =
                data.has("instaSellProfitAfterTax")
                        ? data.get("instaSellProfitAfterTax").getAsLong()
                        : marketProfit;
        int taxPct =
                data.has("auctionTaxRate")
                        ? Math.round(data.get("auctionTaxRate").getAsFloat() * 100f)
                        : 1;

        client.player.displayClientMessage(divider(), false);

        client.player.displayClientMessage(
                mansifPrefix()
                        .append(
                                Component.literal(" Habanero craft ")
                                        .withStyle(ChatFormatting.GRAY))
                        .append(
                                Component.literal(bookName)
                                        .withStyle(
                                                ChatFormatting.LIGHT_PURPLE,
                                                ChatFormatting.BOLD)),
                false);

        client.player.displayClientMessage(
                Component.literal("  Craft ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(
                                Component.literal(bookName)
                                        .withStyle(
                                                ChatFormatting.WHITE,
                                                ChatFormatting.BOLD))
                        .append(Component.literal("  ·  ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(
                                Component.literal(formatCoins(totalCost))
                                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)),
                false);

        client.player.displayClientMessage(spacer(), false);

        if (data.has("lines") && data.get("lines").isJsonArray()) {
            JsonArray lines = data.getAsJsonArray("lines");
            for (JsonElement el : lines) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject line = el.getAsJsonObject();
                String label = line.get("label").getAsString();
                int qty = line.get("quantity").getAsInt();
                long lineCost = line.get("totalCost").getAsLong();

                client.player.displayClientMessage(ingredientRow(qty, label, lineCost), false);
            }
        }

        client.player.displayClientMessage(spacer(), false);

        client.player.displayClientMessage(
                profitRow("Buy order book", marketProfit, taxPct), false);
        if (instaSellProfit != marketProfit) {
            client.player.displayClientMessage(
                    profitRow("Instant sell book", instaSellProfit, taxPct), false);
        }

        client.player.displayClientMessage(
                Component.literal("  ↳ ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(
                                Component.literal("instant buy inputs")
                                        .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(
                                Component.literal(taxPct + "% bazaar tax on sell")
                                        .withStyle(ChatFormatting.GRAY)),
                false);

        client.player.displayClientMessage(divider(), false);
    }

    private static MutableComponent mansifPrefix() {
        return Component.literal("[Mansif]").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    private static MutableComponent divider() {
        return Component.literal("▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬")
                .withStyle(ChatFormatting.DARK_GRAY);
    }

    private static MutableComponent spacer() {
        return Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY);
    }

    private static MutableComponent ingredientRow(int qty, String label, long lineCost) {
        return Component.literal("  ")
                .withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(String.valueOf(qty)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("× ").withStyle(ChatFormatting.GRAY))
                .append(
                        Component.literal(label)
                                .withStyle(ingredientColor(label), ChatFormatting.BOLD))
                .append(Component.literal("  ▸  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(
                        Component.literal(formatCoins(lineCost))
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
    }

    private static ChatFormatting ingredientColor(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        if (lower.contains("chili") || lower.contains("pepper")) {
            return ChatFormatting.RED;
        }
        if (lower.contains("plasma")) {
            return ChatFormatting.LIGHT_PURPLE;
        }
        if (lower.contains("enchanted")) {
            return ChatFormatting.AQUA;
        }
        return ChatFormatting.BLUE;
    }

    private static MutableComponent profitRow(String bookPriceLabel, long profit, int taxPct) {
        ChatFormatting profitColor =
                profit > 0
                        ? ChatFormatting.GREEN
                        : profit < 0 ? ChatFormatting.RED : ChatFormatting.GRAY;
        String profitPrefix = profit > 0 ? "+" : "";
        return Component.literal("  Profit ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("(" + bookPriceLabel + ", −" + taxPct + "%) ")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(
                        Component.literal(profitPrefix + formatCoins(profit))
                                .withStyle(profitColor, ChatFormatting.BOLD));
    }

    private static String formatCoins(long coins) {
        long abs = Math.abs(coins);
        String sign = coins < 0 ? "-" : "";
        if (abs >= 1_000_000_000L) {
            return sign + String.format(Locale.US, "%.2fB", abs / 1_000_000_000.0);
        }
        if (abs >= 1_000_000L) {
            return sign + String.format(Locale.US, "%.1fM", abs / 1_000_000.0);
        }
        if (abs >= 1_000L) {
            return sign + String.format(Locale.US, "%.0fk", abs / 1_000.0);
        }
        return sign + Long.toString(abs);
    }

    private RecipeCommands() {}
}
