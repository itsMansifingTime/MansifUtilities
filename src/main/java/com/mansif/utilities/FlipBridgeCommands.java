package com.mansif.utilities;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public final class FlipBridgeCommands {
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) ->
                        dispatcher.register(
                                ClientCommandManager.literal("mansifbridge")
                                        .then(
                                                ClientCommandManager.literal("status")
                                                        .executes(
                                                                ctx -> {
                                                                    status(ctx.getSource());
                                                                    return 1;
                                                                }))
                                        .then(
                                                ClientCommandManager.literal("sync")
                                                        .executes(
                                                                ctx -> {
                                                                    sync(ctx.getSource());
                                                                    return 1;
                                                                }))
                                        .then(
                                                ClientCommandManager.literal("poll")
                                                        .executes(
                                                                ctx -> {
                                                                    pollNow(ctx.getSource());
                                                                    return 1;
                                                                })
                                                        .then(
                                                                ClientCommandManager.argument(
                                                                                "ms",
                                                                                IntegerArgumentType
                                                                                        .integer(
                                                                                                500, 60000))
                                                                        .executes(
                                                                                ctx -> {
                                                                                    setPoll(
                                                                                            ctx.getSource(),
                                                                                            IntegerArgumentType
                                                                                                    .getInteger(
                                                                                                            ctx,
                                                                                                            "ms"));
                                                                                    return 1;
                                                                                })))
                                        .then(
                                                ClientCommandManager.literal("direct")
                                                        .executes(
                                                                ctx -> {
                                                                    setDirectFromDefaults(
                                                                            ctx.getSource());
                                                                    return 1;
                                                                })
                                                        .then(
                                                                ClientCommandManager.argument(
                                                                                "host",
                                                                                StringArgumentType
                                                                                        .string())
                                                                        .executes(
                                                                                ctx -> {
                                                                                    String host =
                                                                                            StringArgumentType
                                                                                                    .getString(
                                                                                                            ctx,
                                                                                                            "host");
                                                                                    setDirectApi(
                                                                                            ctx
                                                                                                    .getSource(),
                                                                                            normalizeDirectUrl(
                                                                                                    host,
                                                                                                    3001));
                                                                                    return 1;
                                                                                })
                                                                        .then(
                                                                                ClientCommandManager
                                                                                        .argument(
                                                                                                "port",
                                                                                                IntegerArgumentType
                                                                                                        .integer(
                                                                                                                1,
                                                                                                                65535))
                                                                                        .executes(
                                                                                                ctx -> {
                                                                                                    String
                                                                                                            host =
                                                                                                                    StringArgumentType
                                                                                                                            .getString(
                                                                                                                                    ctx,
                                                                                                                                    "host");
                                                                                                    int port =
                                                                                                            IntegerArgumentType
                                                                                                                    .getInteger(
                                                                                                                            ctx,
                                                                                                                            "port");
                                                                                                    setDirectApi(
                                                                                                            ctx
                                                                                                                    .getSource(),
                                                                                                            normalizeDirectUrl(
                                                                                                                    host,
                                                                                                                    port));
                                                                                                    return 1;
                                                                                                }))))
                                        .then(
                                                ClientCommandManager.literal("api")
                                                        .then(
                                                                ClientCommandManager.argument(
                                                                                "url",
                                                                                StringArgumentType
                                                                                        .greedyString())
                                                                        .executes(
                                                                                ctx -> {
                                                                                    String url =
                                                                                            StringArgumentType
                                                                                                    .getString(
                                                                                                            ctx,
                                                                                                            "url");
                                                                                    setApi(
                                                                                            ctx.getSource(),
                                                                                            url);
                                                                                    return 1;
                                                                                })))
                                        .then(
                                                ClientCommandManager.literal("secret")
                                                        .then(
                                                                ClientCommandManager.argument(
                                                                                "value",
                                                                                StringArgumentType
                                                                                        .greedyString())
                                                                        .executes(
                                                                                ctx -> {
                                                                                    String secret =
                                                                                            StringArgumentType
                                                                                                    .getString(
                                                                                                            ctx,
                                                                                                            "value");
                                                                                    setSecret(
                                                                                            ctx.getSource(),
                                                                                            secret);
                                                                                    return 1;
                                                                                })))
                                        .then(
                                                ClientCommandManager.literal("enable")
                                                        .executes(
                                                                ctx -> {
                                                                    setEnabled(ctx.getSource(), true);
                                                                    return 1;
                                                                }))
                                        .then(
                                                ClientCommandManager.literal("hypixel")
                                                        .then(
                                                                ClientCommandManager.literal("clear")
                                                                        .executes(
                                                                                ctx -> {
                                                                                    HypixelKeyHelper
                                                                                            .clearKey(
                                                                                                    ctx
                                                                                                            .getSource());
                                                                                    return 1;
                                                                                }))
                                                        .then(
                                                                ClientCommandManager.argument(
                                                                                "key",
                                                                                StringArgumentType
                                                                                        .word())
                                                                        .executes(
                                                                                ctx -> {
                                                                                    HypixelKeyHelper
                                                                                            .saveKey(
                                                                                                    ctx
                                                                                                            .getSource(),
                                                                                                    StringArgumentType
                                                                                                            .getString(
                                                                                                                    ctx,
                                                                                                                    "key"),
                                                                                                    HypixelKeyHelper
                                                                                                            .defaultValidDays());
                                                                                    return 1;
                                                                                })
                                                                        .then(
                                                                                ClientCommandManager
                                                                                        .argument(
                                                                                                "days",
                                                                                                IntegerArgumentType
                                                                                                        .integer(
                                                                                                                1,
                                                                                                                365))
                                                                                        .executes(
                                                                                                ctx -> {
                                                                                                    HypixelKeyHelper
                                                                                                            .saveKey(
                                                                                                                    ctx
                                                                                                                            .getSource(),
                                                                                                                    StringArgumentType
                                                                                                                            .getString(
                                                                                                                                    ctx,
                                                                                                                                    "key"),
                                                                                                                    IntegerArgumentType
                                                                                                                            .getInteger(
                                                                                                                                    ctx,
                                                                                                                                    "days"));
                                                                                                    return 1;
                                                                                                }))))
                                        .then(
                                                ClientCommandManager.literal("disable")
                                                        .executes(
                                                                ctx -> {
                                                                    setEnabled(ctx.getSource(), false);
                                                                    return 1;
                                                                }))
                                        .then(
                                                ClientCommandManager.literal("enabled")
                                                        .then(
                                                                ClientCommandManager.argument(
                                                                                "on",
                                                                                BoolArgumentType.bool())
                                                                        .executes(
                                                                                ctx -> {
                                                                                    setEnabled(
                                                                                            ctx.getSource(),
                                                                                            BoolArgumentType
                                                                                                    .getBool(
                                                                                                            ctx,
                                                                                                            "on"));
                                                                                    return 1;
                                                                                })))));
    }

    private static void status(FabricClientCommandSource source) {
        FlipBridgeConfig cfg = FlipBridgeConfig.loadAndSync();
        String secretState =
                cfg.secret == null || cfg.secret.isBlank()
                        ? "missing"
                        : "set (" + cfg.secret.length() + " chars)";
        source.sendFeedback(
                Component.literal("[MansifUtilities] Flip bridge config: ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(
                                Component.literal(FlipBridgeConfig.configPath().toString())
                                        .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("\n  enabled: " + cfg.enabled))
                        .append(
                                Component.literal(
                                        "\n  apiBase: "
                                                + (cfg.apiBase == null || cfg.apiBase.isBlank()
                                                        ? "(empty)"
                                                        : cfg.apiBase)))
                        .append(
                                Component.literal(
                                        "\n  directApiBase: "
                                                + (cfg.directApiBase == null
                                                                || cfg.directApiBase.isBlank()
                                                        ? "(empty)"
                                                        : cfg.directApiBase)))
                        .append(Component.literal("\n  secret: " + secretState))
                        .append(Component.literal("\n  pollIntervalMs: " + cfg.pollIntervalMs))
                        .append(
                                Component.literal(
                                        "\n  hypixelApiKey: "
                                                + HypixelKeyHelper.hypixelKeyStatusLine(cfg)))
                        .append(
                                Component.literal(
                                                "\n  ready: "
                                                        + (cfg.isUsable()
                                                                ? "yes"
                                                                : "no — run /mansifbridge sync or set api/secret"))
                                        .withStyle(
                                                cfg.isUsable()
                                                        ? ChatFormatting.GREEN
                                                        : ChatFormatting.YELLOW)));
        FlipAlertBridge.reloadConfig();
    }

    private static void pollNow(FabricClientCommandSource source) {
        if (!FlipAlertBridge.reloadConfigForPoll()) {
            source.sendFeedback(
                    Component.literal(
                                    "[MansifUtilities] Flip bridge not ready — set api + secret first.")
                            .withStyle(ChatFormatting.RED));
            return;
        }
        source.sendFeedback(
                Component.literal("[MansifUtilities] Polling flip feed now (catch-up)…")
                        .withStyle(ChatFormatting.YELLOW));
        FlipAlertBridge.requestCatchUpPoll();
    }

    private static void sync(FabricClientCommandSource source) {
        FlipBridgeConfig cfg = FlipBridgeConfig.loadAndSync();
        boolean ok = FlipBridgeConfig.syncFromServer(cfg);
        FlipAlertBridge.reloadConfig();
        if (ok) {
            source.sendFeedback(
                    Component.literal("[MansifUtilities] Synced config from server → saved to disk.")
                            .withStyle(ChatFormatting.GREEN));
            status(source);
        } else {
            source.sendFeedback(
                    Component.literal(
                                    "[MansifUtilities] Could not reach /api/bin-deal-ingame-bridge-config. Set api with /mansifbridge api <url>")
                            .withStyle(ChatFormatting.RED));
        }
    }

    private static void setApi(FabricClientCommandSource source, String url) {
        FlipBridgeConfig.update(
                cfg -> {
                    cfg.apiBase = url.trim().replaceAll("/+$", "");
                    cfg.enabled = true;
                });
        FlipAlertBridge.reloadConfig();
        source.sendFeedback(
                Component.literal("[MansifUtilities] Saved apiBase to config file.")
                        .withStyle(ChatFormatting.GREEN));
    }

    private static void setDirectFromDefaults(FabricClientCommandSource source) {
        FlipBridgeConfig defaults = FlipBridgeConfig.bundledDefaults();
        String direct = defaults.directApiBase;
        if (direct == null || direct.isBlank()) {
            source.sendFeedback(
                    Component.literal(
                                    "[MansifUtilities] No bundled direct URL — use /mansifbridge direct <ip> [port]")
                            .withStyle(ChatFormatting.RED));
            return;
        }
        setDirectApi(source, direct);
    }

    /** Accepts full URL or bare IP/hostname; default port 3001. */
    private static String normalizeDirectUrl(String hostOrUrl, int port) {
        String t = hostOrUrl.trim().replaceAll("/+$", "");
        if (t.startsWith("http://") || t.startsWith("https://")) {
            return t;
        }
        return "http://" + t + ":" + port;
    }

    private static void setDirectApi(FabricClientCommandSource source, String url) {
        String normalized = normalizeDirectUrl(url, 3001);
        FlipBridgeConfig.update(
                cfg -> {
                    cfg.directApiBase = normalized.replaceAll("/+$", "");
                    cfg.enabled = true;
                });
        FlipAlertBridge.reloadConfig();
        source.sendFeedback(
                Component.literal(
                                "[MansifUtilities] Saved directApiBase: "
                                        + normalized
                                        + " — /mansifbridge poll to test")
                        .withStyle(ChatFormatting.GREEN));
    }

    private static void setSecret(FabricClientCommandSource source, String secret) {
        FlipBridgeConfig.update(cfg -> cfg.secret = secret.trim());
        FlipAlertBridge.reloadConfig();
        source.sendFeedback(
                Component.literal("[MansifUtilities] Saved secret to config file.")
                        .withStyle(ChatFormatting.GREEN));
    }

    private static void setPoll(FabricClientCommandSource source, int ms) {
        FlipBridgeConfig.update(cfg -> cfg.pollIntervalMs = ms);
        FlipAlertBridge.reloadConfig();
        source.sendFeedback(
                Component.literal("[MansifUtilities] Saved pollIntervalMs=" + ms)
                        .withStyle(ChatFormatting.GREEN));
    }

    private static void setEnabled(FabricClientCommandSource source, boolean on) {
        FlipBridgeConfig.update(cfg -> cfg.enabled = on);
        FlipAlertBridge.reloadConfig();
        source.sendFeedback(
                Component.literal("[MansifUtilities] Saved enabled=" + on)
                        .withStyle(ChatFormatting.GREEN));
    }

    private FlipBridgeCommands() {}
}
