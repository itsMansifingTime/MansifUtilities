package com.mansif.utilities;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

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
                                                ClientCommandManager.literal("startup")
                                                        .executes(
                                                                ctx -> {
                                                                    startup(ctx.getSource());
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
                                                                                            host,
                                                                                                    3001);
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
                                                                                                            host,
                                                                                                                    port);
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
                                                                                })))
                                        .then(
                                                ClientCommandManager.literal("hab")
                                                        .executes(
                                                                ctx ->
                                                                        RecipeCommands.showHabanero(
                                                                                ctx)))));
    }

    private static void status(FabricClientCommandSource source) {
        FlipBridgeConfig cfg = FlipBridgeConfig.loadAndSync();
        String secretState =
                cfg.secret == null || cfg.secret.isBlank()
                        ? "missing"
                        : "set (" + cfg.secret.length() + " chars)";
        String preferred = FlipAlertBridge.preferredFeedBase();
        source.sendFeedback(
                Component.literal("[MansifUtilities] Flip bridge status")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        source.sendFeedback(
                Component.literal("  config: " + FlipBridgeConfig.configPath().toString())
                        .withStyle(ChatFormatting.GRAY));
        source.sendFeedback(Component.literal("  enabled: " + cfg.enabled));
        source.sendFeedback(
                Component.literal(
                        "  apiBase: "
                                + (cfg.apiBase == null || cfg.apiBase.isBlank()
                                        ? "(empty)"
                                        : cfg.apiBase)));
        source.sendFeedback(
                Component.literal(
                        "  directApiBase: "
                                + (cfg.directApiBase == null || cfg.directApiBase.isBlank()
                                        ? "(empty)"
                                        : cfg.directApiBase)));
        source.sendFeedback(Component.literal("  secret: " + secretState));
        source.sendFeedback(Component.literal("  pollIntervalMs: " + cfg.pollIntervalMs));
        source.sendFeedback(
                Component.literal(
                        "  hypixelApiKey: " + HypixelKeyHelper.hypixelKeyStatusLine(cfg)));
        source.sendFeedback(
                Component.literal(
                        "  active feed host: "
                                + (preferred == null || preferred.isBlank()
                                        ? "(not chosen yet — wait for startup)"
                                        : preferred)));
        source.sendFeedback(
                Component.literal(
                        "  last poll error: " + FlipAlertBridge.lastPollErrorForStatus())
                        .withStyle(ChatFormatting.GRAY));

        for (String issue : FlipBridgeHealth.issuesForConfig(cfg)) {
            source.sendFeedback(
                    Component.literal("  ! " + issue).withStyle(ChatFormatting.RED));
        }

        source.sendFeedback(
                Component.literal("  Probing feed hosts…").withStyle(ChatFormatting.YELLOW));

        Minecraft client = source.getClient();
        Thread probeThread =
                new Thread(
                        () -> {
                            List<FlipBridgeHealth.HostProbe> probes = new ArrayList<>();
                            for (String base : cfg.pollApiBasesForFeed()) {
                                probes.add(FlipBridgeHealth.probeFeedHost(cfg, base));
                            }
                            boolean anyOk =
                                    probes.stream()
                                            .anyMatch(
                                                    p ->
                                                            p.kind()
                                                                            == FlipBridgeHealth
                                                                                    .ProbeKind.OK
                                                                    || p.kind()
                                                                            == FlipBridgeHealth
                                                                                    .ProbeKind
                                                                                    .EMPTY_FEED);
                            client.execute(
                                    () -> {
                                        for (FlipBridgeHealth.HostProbe p : probes) {
                                            ChatFormatting lineColor =
                                                    switch (p.kind()) {
                                                        case OK, EMPTY_FEED -> ChatFormatting.GREEN;
                                                        case SKIPPED -> ChatFormatting.GRAY;
                                                        default -> ChatFormatting.RED;
                                                    };
                                            source.sendFeedback(
                                                    Component.literal(
                                                                    "  "
                                                                            + FlipBridgeHealth
                                                                                    .formatProbeLine(
                                                                                            p))
                                                            .withStyle(lineColor));
                                            if (p.fix() != null && !p.fix().isBlank()) {
                                                source.sendFeedback(
                                                        Component.literal("      → " + p.fix())
                                                                .withStyle(
                                                                        ChatFormatting.YELLOW));
                                            }
                                        }
                                        source.sendFeedback(
                                                Component.literal(
                                                                "  ready: "
                                                                        + (cfg.isUsable() && anyOk
                                                                                ? "yes"
                                                                                : "no"))
                                                        .withStyle(
                                                                cfg.isUsable() && anyOk
                                                                        ? ChatFormatting.GREEN
                                                                        : ChatFormatting.RED));
                                        if (!anyOk) {
                                            source.sendFeedback(
                                                    Component.literal(
                                                                    "  Fix: /mansifbridge direct https://api.mansif.dev  OR  /mansifbridge direct YOUR_EC2_IP 3001")
                                                            .withStyle(ChatFormatting.AQUA));
                                        }
                                    });
                        },
                        "mansifutilities-flip-status");
        probeThread.setDaemon(true);
        probeThread.start();
        FlipAlertBridge.reloadConfig();
    }

    private static void startup(FabricClientCommandSource source) {
        source.sendFeedback(
                Component.literal("[MansifUtilities] Running flip bridge startup…")
                        .withStyle(ChatFormatting.YELLOW));
        FlipAlertBridge.requestStartup(source.getClient());
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
        Minecraft client = source.getClient();
        source.sendFeedback(
                Component.literal("[MansifUtilities] Syncing flip bridge config (background)…")
                        .withStyle(ChatFormatting.YELLOW));
        Thread syncThread =
                new Thread(
                        () -> {
                            FlipBridgeConfig cfg = FlipBridgeConfig.loadAndSync();
                            boolean ok = FlipBridgeConfig.syncFromServer(cfg);
                            FlipAlertBridge.reloadConfig();
                            client.execute(
                                    () -> {
                                        if (ok) {
                                            FlipBridgeConfig fresh =
                                                    FlipBridgeConfig.loadAndSync();
                                            source.sendFeedback(
                                                    Component.literal(
                                                                    "[MansifUtilities] Synced from server → saved.")
                                                            .withStyle(ChatFormatting.GREEN)
                                                            .append(
                                                                    Component.literal(
                                                                            "\n  apiBase: "
                                                                                    + blank(
                                                                                            fresh
                                                                                                    .apiBase)
                                                                                    + "\n  direct: "
                                                                                    + blank(
                                                                                            fresh
                                                                                                    .directApiBase)
                                                                                    + "\n  ready: "
                                                                                    + (fresh
                                                                                                    .isUsable()
                                                                                            ? "yes"
                                                                                            : "no — /mansifbridge secret <feedSecret>"))
                                                                            .withStyle(
                                                                                    ChatFormatting
                                                                                            .GRAY)));
                                        } else {
                                            source.sendFeedback(
                                                    Component.literal(
                                                                    "[MansifUtilities] Could not reach /api/bin-deal-ingame-bridge-config. Try /mansifbridge api https://mansiftracker.vercel.app")
                                                            .withStyle(ChatFormatting.RED));
                                        }
                                    });
                        },
                        "mansifutilities-flip-sync-cmd");
        syncThread.setDaemon(true);
        syncThread.start();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? "(empty)" : s;
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
        setDirectApi(source, direct, 3001);
    }

    private static void setDirectApi(FabricClientCommandSource source, String hostOrUrl, int port) {
        String before = hostOrUrl.trim();
        String normalized = FlipBridgeHealth.normalizeDirectInput(before, port);
        FlipBridgeConfig.update(
                cfg -> {
                    cfg.directApiBase = normalized.replaceAll("/+$", "");
                    cfg.enabled = true;
                });
        FlipAlertBridge.reloadConfig();
        MutableComponent msg =
                Component.literal("[MansifUtilities] Saved directApiBase: " + normalized)
                        .withStyle(ChatFormatting.GREEN);
        if (!before.equals(normalized)
                && before.toLowerCase().contains("mansif.dev")
                && port == 3001) {
            msg = msg.append(
                    Component.literal(
                                    "\n  (auto-fixed: api.mansif.dev uses HTTPS on 443, not :3001)")
                            .withStyle(ChatFormatting.YELLOW));
        }
        source.sendFeedback(msg.append(Component.literal("\n  /mansifbridge status to verify")));
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
