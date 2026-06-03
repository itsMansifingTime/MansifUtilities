package com.mansif.utilities;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Probes MansifTracker flip-bridge endpoints and builds human-readable status. */
public final class FlipBridgeHealth {

    public static final int PROBE_CONNECT_MS = 5_000;
    public static final int PROBE_READ_MS = 12_000;
    public static final int STARTUP_CONNECT_MS = 3_500;
    public static final int STARTUP_READ_MS = 6_000;

    public enum ProbeKind {
        OK,
        EMPTY_FEED,
        AUTH_FAIL,
        HTTP_ERROR,
        TIMEOUT,
        CONNECTION_REFUSED,
        UNREACHABLE,
        BAD_URL,
        SKIPPED
    }

    public record HostProbe(
            String label,
            String baseUrl,
            ProbeKind kind,
            String detail,
            String fix) {}

    public record StartupResult(
            boolean ready,
            String chosenFeedBase,
            List<String> chatLines,
            List<HostProbe> probes) {}

    /**
     * Fixes common mistakes: {@code api.mansif.dev + port 3001} → {@code https://api.mansif.dev}
     * (TLS on 443, not raw :3001 on the domain).
     */
    public static String sanitizeApiBaseUrl(String base) {
        if (base == null || base.isBlank()) {
            return "";
        }
        String t = base.trim().replaceAll("/+$", "");
        String lower = t.toLowerCase(Locale.ROOT);

        if (lower.contains("mansif.dev") && (lower.contains(":3001") || lower.startsWith("http://"))) {
            if (!lower.startsWith("https://api.mansif.dev")) {
                return "https://api.mansif.dev";
            }
        }
        if (lower.contains("vercel.app") && lower.contains(":3001")) {
            return "https://mansiftracker.vercel.app";
        }
        return t;
    }

    /** Bare host from /mansifbridge direct — domain → HTTPS, IP → http://ip:port. */
    public static String normalizeDirectInput(String hostOrUrl, int defaultPort) {
        String t = hostOrUrl.trim().replaceAll("/+$", "");
        if (t.startsWith("http://") || t.startsWith("https://")) {
            return sanitizeApiBaseUrl(t);
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("mansif.dev")) {
            return "https://api.mansif.dev";
        }
        if (lower.contains("vercel.app")) {
            return "https://mansiftracker.vercel.app";
        }
        return "http://" + t + ":" + defaultPort;
    }

    public static List<String> issuesForConfig(FlipBridgeConfig cfg) {
        List<String> issues = new ArrayList<>();
        if (!cfg.enabled) {
            issues.add("Polling is disabled (enabled=false). Run /mansifbridge enable");
        }
        if (!cfg.hasValidSecret()) {
            issues.add("Missing feed secret — bundled default should apply; try /mansifbridge sync");
        }
        if (!cfg.hasValidApiEndpoint()) {
            issues.add("No API URL — startup will set directApiBase from bundled defaults");
        }
        String direct = cfg.directApiBase == null ? "" : cfg.directApiBase;
        if (direct.toLowerCase(Locale.ROOT).contains("mansif.dev")
                && direct.contains(":3001")) {
            issues.add(
                    "directApiBase is wrong: "
                            + direct
                            + " — use https://api.mansif.dev (no :3001 on the domain)");
        }
        return issues;
    }

    public static HostProbe probeFeedHost(FlipBridgeConfig cfg, String base) {
        return probeFeedHost(cfg, base, PROBE_CONNECT_MS, PROBE_READ_MS);
    }

    public static HostProbe probeFeedHost(
            FlipBridgeConfig cfg, String base, int connectMs, int readMs) {
        String label = shortLabel(base);
        String sanitized = sanitizeApiBaseUrl(base);
        if (sanitized.isBlank()) {
            return new HostProbe(label, base, ProbeKind.SKIPPED, "not configured", null);
        }
        if (!cfg.hasValidSecret()) {
            return new HostProbe(
                    label,
                    sanitized,
                    ProbeKind.SKIPPED,
                    "no secret",
                    "/mansifbridge sync or check config file");
        }

        String url = cfg.feedUrlForBase(sanitized, 0L);
        try {
            HttpURLConnection conn =
                    (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("Authorization", "Bearer " + cfg.secret.trim());
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            String body =
                    new String(
                            (code >= 200 && code < 300
                                            ? conn.getInputStream()
                                            : conn.getErrorStream())
                                    .readAllBytes(),
                            StandardCharsets.UTF_8);

            if (code == 401) {
                return new HostProbe(
                        label,
                        sanitized,
                        ProbeKind.AUTH_FAIL,
                        "HTTP 401 — wrong feed secret",
                        "Match secret to data/bin-deal-ingame-bridge.json on server (feedSecret)");
            }
            if (code == 502 && sanitized.contains("vercel.app")) {
                String hint = parseJsonHint(body);
                return new HostProbe(
                        label,
                        sanitized,
                        ProbeKind.HTTP_ERROR,
                        "HTTP 502 — Vercel cannot reach EC2"
                                + (hint != null ? " (" + hint + ")" : ""),
                        "Fix SITE_API_ORIGIN on Vercel or use direct https://api.mansif.dev");
            }
            if (code != 200) {
                String hint = parseJsonHint(body);
                return new HostProbe(
                        label,
                        sanitized,
                        ProbeKind.HTTP_ERROR,
                        "HTTP " + code + (hint != null ? " — " + hint : ""),
                        suggestFixForBase(sanitized, code));
            }

            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("ok") && root.get("ok").isJsonPrimitive() && !root.get("ok").getAsBoolean()) {
                String hint = parseJsonHint(body);
                return new HostProbe(
                        label,
                        sanitized,
                        ProbeKind.HTTP_ERROR,
                        hint != null ? hint : "feed unavailable (proxy)",
                        sanitized.contains("vercel.app")
                                ? "Vercel cannot reach EC2 — use /mansifbridge direct EC2_IP 3001"
                                : fixForHost(sanitized, ProbeKind.HTTP_ERROR));
            }
            int flips =
                    root.has("flips") && root.get("flips").isJsonArray()
                            ? root.getAsJsonArray("flips").size()
                            : 0;
            String hint = parseJsonHint(body);
            if (flips == 0 && hint != null && !hint.isBlank()) {
                return new HostProbe(
                        label,
                        sanitized,
                        ProbeKind.EMPTY_FEED,
                        "reachable but 0 flips — " + truncate(hint, 100),
                        "Normal if no recent BIN alerts; EC2 must run scanner");
            }
            return new HostProbe(
                    label,
                    sanitized,
                    ProbeKind.OK,
                    "reachable — " + flips + " flip(s) in feed",
                    null);
        } catch (SocketTimeoutException e) {
            return new HostProbe(
                    label,
                    sanitized,
                    ProbeKind.TIMEOUT,
                    "timed out after " + (connectMs + readMs) / 1000 + "s",
                    fixForHost(sanitized, ProbeKind.TIMEOUT));
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            ProbeKind kind = ProbeKind.UNREACHABLE;
            if (msg.toLowerCase(Locale.ROOT).contains("connection refused")) {
                kind = ProbeKind.CONNECTION_REFUSED;
            }
            return new HostProbe(label, sanitized, kind, msg, fixForHost(sanitized, kind));
        } catch (Exception e) {
            return new HostProbe(
                    label,
                    sanitized,
                    ProbeKind.UNREACHABLE,
                    e.getMessage() != null ? e.getMessage() : "failed",
                    null);
        }
    }

    public static StartupResult runStartup(FlipBridgeConfig cfg) {
        List<String> lines = new ArrayList<>();
        List<HostProbe> probes = new ArrayList<>();

        cfg.directApiBase = sanitizeApiBaseUrl(cfg.directApiBase);
        cfg.apiBase = sanitizeApiBaseUrl(cfg.apiBase);

        boolean synced = FlipBridgeConfig.syncFromServer(cfg);
        lines.add(
                synced
                        ? "Synced bridge-config from server."
                        : "Could not sync bridge-config (will use local/bundled URLs).");

        List<String> bases = FlipBridgeConfig.startupProbeBases(cfg);
        String chosen = null;
        for (String base : bases) {
            HostProbe probe =
                    probeFeedHost(cfg, base, STARTUP_CONNECT_MS, STARTUP_READ_MS);
            probes.add(probe);
            if (chosen == null
                    && (probe.kind() == ProbeKind.OK || probe.kind() == ProbeKind.EMPTY_FEED)) {
                chosen = probe.baseUrl();
            }
        }

        pruneFailedDirectIp(cfg, probes);

        if (chosen != null) {
            if (chosen.contains("mansif.dev") || chosen.contains("vercel.app")) {
                cfg.directApiBase = chosen.contains("mansif.dev")
                        ? "https://api.mansif.dev"
                        : cfg.directApiBase;
                cfg.apiBase =
                        chosen.contains("vercel.app")
                                ? "https://mansiftracker.vercel.app"
                                : cfg.apiBase;
            } else {
                cfg.directApiBase = chosen;
            }
            FlipBridgeConfig.save(cfg);
            lines.add("Active flip feed host: " + chosen);
        }

        boolean ready = cfg.isUsable() && chosen != null;
        if (ready) {
            lines.add("Flip bridge ready — polling every " + cfg.pollIntervalMs + "ms.");
        } else {
            appendCompactStartupFailure(lines, probes);
        }

        return new StartupResult(ready, chosen, lines, probes);
    }

    /** At most three chat lines on failure (no per-host spam). */
    private static void appendCompactStartupFailure(
            List<String> lines, List<HostProbe> probes) {
        lines.add("Flip bridge NOT ready — /mansifbridge status for full probe list.");
        HostProbe headline = pickHeadlineFailure(probes);
        if (headline != null) {
            lines.add(headline.label() + ": " + headline.detail());
        }
        String action = consolidatedStartupFix(probes);
        if (action != null && !action.isBlank()) {
            lines.add("→ " + action);
        }
    }

    private static HostProbe pickHeadlineFailure(List<HostProbe> probes) {
        HostProbe directFail = null;
        HostProbe anyFail = null;
        for (HostProbe p : probes) {
            if (p.kind() == ProbeKind.OK || p.kind() == ProbeKind.EMPTY_FEED || p.kind() == ProbeKind.SKIPPED) {
                continue;
            }
            if (anyFail == null) {
                anyFail = p;
            }
            if (looksLikeRawIpBase(p.baseUrl())) {
                directFail = p;
            }
        }
        return directFail != null ? directFail : anyFail;
    }

    private static String consolidatedStartupFix(List<HostProbe> probes) {
        boolean ipFailed = false;
        boolean mansifDevFailed = false;
        boolean vercel502 = false;
        for (HostProbe p : probes) {
            if (p.kind() == ProbeKind.OK || p.kind() == ProbeKind.EMPTY_FEED) {
                return null;
            }
            if (looksLikeRawIpBase(p.baseUrl()) && p.kind() != ProbeKind.OK) {
                ipFailed = true;
            }
            if (p.baseUrl().contains("mansif.dev") && p.kind() != ProbeKind.OK) {
                mansifDevFailed = true;
            }
            if (p.baseUrl().contains("vercel.app")
                    && p.kind() == ProbeKind.HTTP_ERROR
                    && p.detail() != null
                    && p.detail().contains("502")) {
                vercel502 = true;
            }
        }
        if (ipFailed && mansifDevFailed) {
            return "/mansifbridge sync (server EC2 URL) then /mansifbridge status — api.mansif.dev HTTPS may be down; use direct EC2 :3001";
        }
        if (ipFailed) {
            return "/mansifbridge sync — your saved EC2 IP may be wrong or SG :3001 blocked";
        }
        if (mansifDevFailed) {
            return "api.mansif.dev HTTPS is down — /mansifbridge direct YOUR_EC2_IP 3001";
        }
        if (vercel502) {
            return "Vercel cannot reach EC2 — fix SITE_API_ORIGIN on Vercel or use /mansifbridge direct EC2_IP 3001";
        }
        return "/mansifbridge status";
    }

    private static void pruneFailedDirectIp(FlipBridgeConfig cfg, List<HostProbe> probes) {
        if (cfg.directApiBase == null || cfg.directApiBase.isBlank()) {
            return;
        }
        if (!looksLikeRawIpBase(cfg.directApiBase)) {
            return;
        }
        for (HostProbe p : probes) {
            if (!p.baseUrl().equals(sanitizeApiBaseUrl(cfg.directApiBase))) {
                continue;
            }
            if (p.kind() == ProbeKind.OK || p.kind() == ProbeKind.EMPTY_FEED) {
                return;
            }
            cfg.directApiBase = "";
            FlipBridgeConfig.save(cfg);
            MansifUtilities.LOGGER.warn(
                    "Cleared dead directApiBase {} after startup probe ({})",
                    p.baseUrl(),
                    p.kind());
            return;
        }
    }

    static boolean looksLikeRawIpBase(String base) {
        if (base == null || base.isBlank()) {
            return false;
        }
        String lower = base.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://")
                && !lower.contains("mansif.dev")
                && !lower.contains("vercel.app")
                && !lower.contains("localhost")
                && !lower.contains("127.0.0.1");
    }

    private static String fixForHost(String sanitized, ProbeKind kind) {
        if (sanitized.contains("mansif.dev")) {
            if (sanitized.contains(":3001")) {
                return "Use https://api.mansif.dev (no :3001 on the domain)";
            }
            if (kind == ProbeKind.CONNECTION_REFUSED || kind == ProbeKind.TIMEOUT) {
                return "HTTPS on api.mansif.dev is down — use /mansifbridge direct EC2_IP 3001 or fix nginx/443 on server";
            }
        }
        if (looksLikeRawIpBase(sanitized)) {
            if (kind == ProbeKind.TIMEOUT) {
                return "Wrong IP, firewall, or EC2 stopped — /mansifbridge sync for current IP";
            }
            return "pm2 mansif-next-api on :3001 and open SG TCP 3001";
        }
        if (sanitized.contains("vercel.app") && kind == ProbeKind.HTTP_ERROR) {
            return "Fix SITE_API_ORIGIN on Vercel or /mansifbridge direct EC2_IP 3001";
        }
        if (sanitized.contains("127.0.0.1") || sanitized.contains("localhost")) {
            return "Local Next not running — start mansif-next-api or use /mansifbridge direct EC2_IP 3001";
        }
        return "/mansifbridge status";
    }

    public static String formatProbeLine(HostProbe p) {
        String status =
                switch (p.kind()) {
                    case OK -> "OK";
                    case EMPTY_FEED -> "OK (empty feed)";
                    case AUTH_FAIL -> "AUTH FAIL";
                    case HTTP_ERROR -> "HTTP ERROR";
                    case TIMEOUT -> "TIMEOUT";
                    case CONNECTION_REFUSED -> "REFUSED";
                    case UNREACHABLE -> "FAIL";
                    case BAD_URL -> "BAD URL";
                    case SKIPPED -> "SKIP";
                };
        String line = p.label() + " [" + status + "] " + p.baseUrl();
        if (p.detail() != null && !p.detail().isBlank()) {
            line += " — " + p.detail();
        }
        return line;
    }

    public static String formatFailureForChat(String base, String rawDetail) {
        String sanitized = sanitizeApiBaseUrl(base);
        String label = shortLabel(sanitized.isBlank() ? base : sanitized);
        String lower = rawDetail == null ? "" : rawDetail.toLowerCase(Locale.ROOT);

        if (lower.contains("connection refused")) {
            if (sanitized.contains("mansif.dev") && sanitized.contains(":3001")) {
                return label
                        + ": connection refused on "
                        + sanitized
                        + " — the domain uses HTTPS only. Fix: /mansifbridge direct https://api.mansif.dev";
            }
            return label
                    + ": connection refused — nothing on that host:port. Fix: /mansifbridge direct YOUR_EC2_IP 3001 OR https://api.mansif.dev";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return label
                    + ": timed out — server slow or blocked. Fix: /mansifbridge status (see which host is OK)";
        }
        if (lower.contains("401") || lower.contains("auth")) {
            return label + ": wrong secret — must match feedSecret on server. Fix: /mansifbridge sync";
        }
        if (lower.contains("502") && sanitized.contains("vercel.app")) {
            return label
                    + ": Vercel cannot reach EC2. Fix: /mansifbridge direct https://api.mansif.dev";
        }
        return label + ": " + (rawDetail != null ? rawDetail : "cannot reach API");
    }

    private static String suggestFixForBase(String base, int code) {
        if (base.contains("mansif.dev") && base.contains(":3001")) {
            return "Use https://api.mansif.dev without :3001";
        }
        if (code == 502 && base.contains("vercel.app")) {
            return "/mansifbridge direct https://api.mansif.dev";
        }
        return "/mansifbridge status";
    }

    private static String parseJsonHint(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("hint") && !o.get("hint").isJsonNull()) {
                return o.get("hint").getAsString();
            }
            if (o.has("error") && !o.get("error").isJsonNull()) {
                return o.get("error").getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static String shortLabel(String base) {
        if (base == null || base.isBlank()) return "(none)";
        if (base.contains("vercel.app")) return "Vercel";
        if (base.contains("mansif.dev")) return "api.mansif.dev";
        if (base.startsWith("http://127.0.0.1") || base.startsWith("http://localhost")) {
            return "localhost:3001";
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
        if (s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }

    private FlipBridgeHealth() {}
}
