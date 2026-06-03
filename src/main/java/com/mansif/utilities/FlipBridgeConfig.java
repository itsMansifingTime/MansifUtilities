package com.mansif.utilities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Client config: `.minecraft/config/MansifUtilities-flip-bridge.json` (auto-created and updated by the mod). */
public final class FlipBridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("MansifUtilities-flip-bridge.json");
    private static final Path SECRET_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("MansifUtilities-flip-bridge.secret");

    public boolean enabled = true;
    public String apiBase = "";
    /** EC2 :3001 when open to your PC — tried before apiBase (avoids slow Vercel proxy). */
    public String directApiBase = "";
    /** Same as `feedSecret` in data/bin-deal-ingame-bridge.json on the server. */
    public String secret = "";
    public int pollIntervalMs = 2000;
    /** Hypixel developer API key (for server seller lookup via POST /api/bin-deal-hypixel-key). */
    public String hypixelApiKey = "";
    /** Unix ms — dev keys last ~3 days; reminder fires within 3 days of this time. */
    public long hypixelApiKeyExpiresAtMs = 0L;

    /** Load, normalize, merge defaults, and write the file when anything changed. */
    public static FlipBridgeConfig loadAndSync() {
        FlipBridgeConfig fromDisk = readDiskOrNew();
        FlipBridgeConfig defaults = loadBundledDefaults();
        boolean changed = mergeDefaults(fromDisk, defaults);
        changed |= normalize(fromDisk);
        changed |= applySecretFile(fromDisk);
        if (changed) {
            save(fromDisk);
        }
        return fromDisk;
    }

    public static void save(FlipBridgeConfig cfg) {
        normalize(cfg);
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(cfg), StandardCharsets.UTF_8);
            MansifUtilities.LOGGER.info("Wrote flip bridge config to {}", CONFIG_PATH);
        } catch (IOException e) {
            MansifUtilities.LOGGER.error("Failed to write MansifUtilities-flip-bridge.json: {}", e.toString());
        }
    }

    public static void update(Consumer<FlipBridgeConfig> edit) {
        FlipBridgeConfig cfg = loadAndSync();
        edit.accept(cfg);
        save(cfg);
    }

    public static Path configPath() {
        return CONFIG_PATH;
    }

    /** Defaults from the mod JAR (used by /mansifbridge direct with no args). */
    public static FlipBridgeConfig bundledDefaults() {
        return loadBundledDefaults();
    }

    /** Pull apiBase/poll/enabled from MansifTracker (no auth). Updates config file on success. */
    public static boolean syncFromServer(FlipBridgeConfig cfg) {
        for (String base : candidateApiBasesForConfigPull(cfg)) {
            FlipBridgeConfig remote = fetchRemoteConfig(base);
            if (remote == null) {
                continue;
            }
            boolean changed = false;
            if (remote.apiBase != null && !remote.apiBase.isBlank()) {
                String normalized = remote.apiBase.trim().replaceAll("/+$", "");
                if (!normalized.equals(cfg.apiBase)) {
                    cfg.apiBase = normalized;
                    changed = true;
                }
            }
            if (remote.pollIntervalMs >= 500 && remote.pollIntervalMs != cfg.pollIntervalMs) {
                cfg.pollIntervalMs = remote.pollIntervalMs;
                changed = true;
            }
            if (remote.directApiBase != null && !remote.directApiBase.isBlank()) {
                String normalized = remote.directApiBase.trim().replaceAll("/+$", "");
                if (!normalized.equals(cfg.directApiBase)) {
                    cfg.directApiBase = normalized;
                    changed = true;
                }
            }
            if (cfg.enabled != remote.enabled) {
                cfg.enabled = remote.enabled;
                changed = true;
            }
            if (changed) {
                save(cfg);
            }
            return true;
        }
        return false;
    }

    public boolean isUsable() {
        return enabled && hasValidApiEndpoint() && hasValidSecret();
    }

    /** True when apiBase or directApiBase is set (either is enough to reach MansifTracker). */
    public boolean hasValidApiEndpoint() {
        return hasValidBaseUrl(apiBase) || hasValidBaseUrl(directApiBase);
    }

    private static boolean hasValidBaseUrl(String base) {
        return base != null && !base.isBlank() && !isPlaceholder(base);
    }

    private static boolean hasValidSecret(String secret) {
        return secret != null && !secret.isBlank() && !isPlaceholder(secret);
    }

    public boolean hasValidSecret() {
        return hasValidSecret(secret);
    }

    public String feedUrl(long sinceMs) {
        return feedUrlForBase(pollApiBase(), sinceMs);
    }

    public String feedUrlForBase(String base, long sinceMs) {
        String normalized = base.trim().replaceAll("/+$", "");
        return normalized + "/api/bin-deal-ingame-feed?since=" + sinceMs;
    }

    /** First base to poll: direct EC2 when set, otherwise public apiBase. */
    public String pollApiBase() {
        if (directApiBase != null && !directApiBase.isBlank() && !isPlaceholder(directApiBase)) {
            return directApiBase.trim().replaceAll("/+$", "");
        }
        return apiBase == null ? "" : apiBase.trim().replaceAll("/+$", "");
    }

    /** Bases to try in order when polling (direct, then public, then localhost). */
    public List<String> pollApiBasesInOrder() {
        List<String> out = new ArrayList<>();
        addCandidate(out, directApiBase);
        addCandidate(out, apiBase);
        FlipBridgeConfig defaults = loadBundledDefaults();
        addCandidate(out, defaults.directApiBase);
        addCandidate(out, defaults.apiBase);
        addCandidate(out, "https://mansiftracker.vercel.app");
        addCandidate(out, "https://api.mansif.dev");
        addCandidate(out, "http://127.0.0.1:3001");
        return out;
    }

    /**
     * Flip feed poll order: user {@link #directApiBase} first when set (EC2 has the feed file),
     * then public apiBase / Vercel. Empty feeds on one host fall through to the next in
     * {@link FlipAlertBridge#pollFeed}.
     */
    public List<String> pollApiBasesForFeed() {
        List<String> out = new ArrayList<>();
        addCandidate(out, directApiBase);
        addCandidate(out, apiBase);
        FlipBridgeConfig defaults = loadBundledDefaults();
        addCandidate(out, defaults.directApiBase);
        addCandidate(out, defaults.apiBase);
        addCandidate(out, "https://mansiftracker.vercel.app");
        addCandidate(out, "https://api.mansif.dev");
        addCandidate(out, "http://127.0.0.1:3001");
        return out;
    }

    private static FlipBridgeConfig readDiskOrNew() {
        if (!Files.isRegularFile(CONFIG_PATH)) {
            return loadBundledDefaults();
        }
        try {
            String raw = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            FlipBridgeConfig cfg = GSON.fromJson(raw, FlipBridgeConfig.class);
            return cfg != null ? cfg : new FlipBridgeConfig();
        } catch (IOException | JsonSyntaxException e) {
            MansifUtilities.LOGGER.error("Failed to read MansifUtilities-flip-bridge.json: {}", e.toString());
            return loadBundledDefaults();
        }
    }

    private static FlipBridgeConfig loadBundledDefaults() {
        try (InputStream in =
                MansifUtilities.class.getResourceAsStream(
                        "/assets/mansifutilities/flip-bridge-defaults.json")) {
            if (in == null) {
                return new FlipBridgeConfig();
            }
            FlipBridgeConfig cfg =
                    GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), FlipBridgeConfig.class);
            return cfg != null ? cfg : new FlipBridgeConfig();
        } catch (Exception e) {
            MansifUtilities.LOGGER.warn("Could not read bundled flip-bridge-defaults.json: {}", e.toString());
            return new FlipBridgeConfig();
        }
    }

    private static boolean mergeDefaults(FlipBridgeConfig cfg, FlipBridgeConfig defaults) {
        boolean changed = false;
        if (cfg.enabled != defaults.enabled && !Files.isRegularFile(CONFIG_PATH)) {
            cfg.enabled = defaults.enabled;
            changed = true;
        }
        if (isBlankOrPlaceholder(cfg.apiBase) && !isBlankOrPlaceholder(defaults.apiBase)) {
            cfg.apiBase = defaults.apiBase;
            changed = true;
        }
        if (isBlankOrPlaceholder(cfg.directApiBase)
                && !isBlankOrPlaceholder(defaults.directApiBase)) {
            cfg.directApiBase = defaults.directApiBase;
            changed = true;
        }
        if (isBlankOrPlaceholder(cfg.secret) && !isBlankOrPlaceholder(defaults.secret)) {
            cfg.secret = defaults.secret;
            changed = true;
        }
        if (cfg.pollIntervalMs < 500 && defaults.pollIntervalMs >= 500) {
            cfg.pollIntervalMs = defaults.pollIntervalMs;
            changed = true;
        }
        return changed;
    }

    private static boolean normalize(FlipBridgeConfig cfg) {
        boolean changed = false;
        if (cfg.apiBase != null) {
            String trimmed = cfg.apiBase.trim().replaceAll("/+$", "");
            if (!trimmed.equals(cfg.apiBase)) {
                cfg.apiBase = trimmed;
                changed = true;
            }
            if (isPlaceholder(trimmed)) {
                cfg.apiBase = "";
                changed = true;
            }
        }
        if (cfg.secret != null) {
            String trimmed = cfg.secret.trim();
            if (!trimmed.equals(cfg.secret)) {
                cfg.secret = trimmed;
                changed = true;
            }
            if (isPlaceholder(trimmed)) {
                cfg.secret = "";
                changed = true;
            }
        }
        if (cfg.directApiBase != null) {
            String trimmed = cfg.directApiBase.trim().replaceAll("/+$", "");
            if (!trimmed.equals(cfg.directApiBase)) {
                cfg.directApiBase = trimmed;
                changed = true;
            }
            if (isPlaceholder(trimmed)) {
                cfg.directApiBase = "";
                changed = true;
            }
        } else {
            cfg.directApiBase = "";
            changed = true;
        }
        if (cfg.hypixelApiKey != null) {
            String trimmed = cfg.hypixelApiKey.trim();
            if (!trimmed.equals(cfg.hypixelApiKey)) {
                cfg.hypixelApiKey = trimmed;
                changed = true;
            }
            if (isPlaceholder(trimmed)) {
                cfg.hypixelApiKey = "";
                changed = true;
            }
        } else {
            cfg.hypixelApiKey = "";
            changed = true;
        }
        if (cfg.pollIntervalMs < 500) {
            cfg.pollIntervalMs = 2000;
            changed = true;
        }
        return changed;
    }

    /** Optional one-line secret file (not overwritten by the mod). */
    private static boolean applySecretFile(FlipBridgeConfig cfg) {
        if (!isBlankOrPlaceholder(cfg.secret)) {
            return false;
        }
        if (!Files.isRegularFile(SECRET_PATH)) {
            return false;
        }
        try {
            String line =
                    Files.readString(SECRET_PATH, StandardCharsets.UTF_8)
                            .lines()
                            .map(String::trim)
                            .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                            .findFirst()
                            .orElse("");
            if (!line.isEmpty() && !isPlaceholder(line)) {
                cfg.secret = line;
                return true;
            }
        } catch (IOException e) {
            MansifUtilities.LOGGER.warn("Could not read MansifUtilities-flip-bridge.secret: {}", e.toString());
        }
        return false;
    }

    /**
     * Authenticated POST/GET (Hypixel key push, etc.): public HTTPS before direct EC2 so a dead
     * :3001 does not stall for multiple connect timeouts. Feed poll still prefers direct.
     */
    public static List<String> apiBasesForServerPush(FlipBridgeConfig cfg) {
        return candidateApiBasesForConfigPull(cfg);
    }

    /**
     * Config pull: try HTTPS / public apiBase first so a dead direct EC2 IP does not block for
     * multiple connect timeouts (feed poll still prefers direct — see pollApiBasesInOrder).
     */
    private static List<String> candidateApiBasesForConfigPull(FlipBridgeConfig cfg) {
        List<String> out = new ArrayList<>();
        addCandidate(out, cfg.apiBase);
        addCandidate(out, cfg.directApiBase);
        FlipBridgeConfig defaults = loadBundledDefaults();
        addCandidate(out, defaults.apiBase);
        addCandidate(out, defaults.directApiBase);
        addCandidate(out, "https://mansiftracker.vercel.app");
        addCandidate(out, "https://api.mansif.dev");
        addCandidate(out, "http://127.0.0.1:3001");
        return out;
    }

    private static List<String> candidateApiBases(FlipBridgeConfig cfg) {
        List<String> out = new ArrayList<>();
        addCandidate(out, cfg.directApiBase);
        addCandidate(out, cfg.apiBase);
        FlipBridgeConfig defaults = loadBundledDefaults();
        addCandidate(out, defaults.directApiBase);
        addCandidate(out, defaults.apiBase);
        addCandidate(out, "https://api.mansif.dev");
        addCandidate(out, "http://127.0.0.1:3001");
        return out;
    }

    private static void addCandidate(List<String> out, String base) {
        if (base == null || base.isBlank() || isPlaceholder(base)) {
            return;
        }
        String t = base.trim().replaceAll("/+$", "");
        if (!out.contains(t)) {
            out.add(t);
        }
    }

    private static FlipBridgeConfig fetchRemoteConfig(String apiBase) {
        String url = apiBase.replaceAll("/+$", "") + "/api/bin-deal-ingame-bridge-config";
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return GSON.fromJson(body, FlipBridgeConfig.class);
        } catch (Exception e) {
            MansifUtilities.LOGGER.debug("Remote bridge config from {} failed: {}", url, e.toString());
            return null;
        }
    }

    private static boolean isBlankOrPlaceholder(String value) {
        return value == null || value.isBlank() || isPlaceholder(value);
    }

    private static boolean isPlaceholder(String value) {
        if (value == null) {
            return true;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("your_ec2")
                || lower.contains("replace-me")
                || lower.contains("same-as-bin_deal")
                || lower.contains("same-as-cron")
                || lower.startsWith("paste-");
    }

    private FlipBridgeConfig() {}
}
