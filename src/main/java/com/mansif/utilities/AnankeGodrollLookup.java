package com.mansif.utilities;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Wiki salvage table for legacy godroll attribute pairs → Ananke Feather count.
 * Source: https://wiki.hypixel.net/Ananke_Feather (Salvaging Godrolls table).
 */
public final class AnankeGodrollLookup {
    private static final Logger LOGGER = MansifUtilities.LOGGER;
    private static final Gson GSON = new Gson();
    private static final String DATA_PATH = "assets/mansifutilities/ananke-godrolls.json";
    public static final String WIKI_URL = "https://wiki.hypixel.net/Ananke_Feather";

    private static final List<String> DISPLAY_NAMES = List.of(
            "Mana Pool",
            "Mana Regeneration",
            "Magic Find",
            "Veteran",
            "Dominance",
            "Speed",
            "Lifeline",
            "Blazing Fortune",
            "Fishing Experience",
            "Vitality");

    public record Entry(String itemId, String itemName, String attr1, String attr2, int feathers) {}

    public record MatchResult(int feathers, String attr1, String attr2) {}

    private static List<Entry> entries = List.of();
    private static final Map<String, String> SLUG_TO_DISPLAY = new HashMap<>();
    private static final Set<String> KNOWN_ITEM_IDS = new HashSet<>();

    static {
        registerSlug("mana_pool", "Mana Pool");
        registerSlug("mana_regeneration", "Mana Regeneration");
        registerSlug("magic_find", "Magic Find");
        registerSlug("veteran", "Veteran");
        registerSlug("dominance", "Dominance");
        registerSlug("speed", "Speed");
        registerSlug("lifeline", "Lifeline");
        registerSlug("blazing_fortune", "Blazing Fortune");
        registerSlug("fishing_experience", "Fishing Experience");
        registerSlug("sea_wisdom", "Fishing Experience");
        registerSlug("vitality", "Vitality");
    }

    private AnankeGodrollLookup() {}

    private static void registerSlug(String slug, String display) {
        SLUG_TO_DISPLAY.put(slug.toLowerCase(Locale.ROOT), display);
    }

    public static boolean isKnownAttributeSlug(String key) {
        if (key == null || key.isBlank()) return false;
        return SLUG_TO_DISPLAY.containsKey(key.toLowerCase(Locale.ROOT).replace(' ', '_'));
    }

    public static void load() {
        try (InputStream in = AnankeGodrollLookup.class.getClassLoader().getResourceAsStream(DATA_PATH)) {
            if (in == null) {
                LOGGER.warn("Missing {}", DATA_PATH);
                entries = List.of();
                return;
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement root = GSON.fromJson(reader, JsonElement.class);
                JsonArray arr =
                        root != null && root.isJsonObject()
                                ? root.getAsJsonObject().getAsJsonArray("rows")
                                : root.getAsJsonArray();
                ArrayList<Entry> loaded = new ArrayList<>();
                KNOWN_ITEM_IDS.clear();
                for (var el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    Entry entry =
                            new Entry(
                                    o.get("itemId").getAsString(),
                                    o.get("itemName").getAsString(),
                                    o.get("attr1").getAsString(),
                                    o.get("attr2").getAsString(),
                                    o.get("feathers").getAsInt());
                    loaded.add(entry);
                    KNOWN_ITEM_IDS.add(entry.itemId().toUpperCase(Locale.ROOT));
                }
                entries = List.copyOf(loaded);
                LOGGER.info(
                        "Loaded {} Ananke godroll salvage rules from {} ({})",
                        entries.size(),
                        DATA_PATH,
                        WIKI_URL);
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to load {}", DATA_PATH, ex);
            entries = List.of();
        }
    }

    public static String displayNameForSlug(String slug) {
        if (slug == null || slug.isBlank()) return null;
        String key = slug.toLowerCase(Locale.ROOT).replace(' ', '_');
        String direct = SLUG_TO_DISPLAY.get(key);
        if (direct != null) return direct;
        for (Map.Entry<String, String> e : SLUG_TO_DISPLAY.entrySet()) {
            if (key.startsWith(e.getKey() + "_")) {
                return e.getValue();
            }
        }
        return null;
    }

    public static String resolveItemId(String rawId, String displayName) {
        String id = rawId != null ? rawId.toUpperCase(Locale.ROOT) : null;
        if (id != null && KNOWN_ITEM_IDS.contains(id)) {
            return id;
        }
        String lower = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        String bestId = null;
        int bestLen = 0;
        for (Entry e : entries) {
            String name = e.itemName().toLowerCase(Locale.ROOT);
            if (lower.contains(name) && name.length() > bestLen) {
                bestId = e.itemId();
                bestLen = name.length();
            }
        }
        if (bestId != null) {
            return bestId;
        }
        return id;
    }

    public static void matchAttributesInText(String text, Set<String> out) {
        if (text == null || text.isBlank()) return;
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAttributeWord(lower, "sea wisdom")) {
            out.add("Fishing Experience");
        }
        for (String display : DISPLAY_NAMES) {
            if (display.equals("Speed")) {
                if (containsAttributeWord(lower, "speed") && !isStatSpeedLine(lower)) {
                    out.add(display);
                }
            } else if (lower.contains(display.toLowerCase(Locale.ROOT))) {
                out.add(display);
            }
        }
    }

    /**
     * Stat / enchant lines like "Speed: +6" (Sugar Rush) are not the Speed attribute shard.
     * Real attribute lines look like "Speed V" without a colon stat suffix.
     */
    private static boolean isStatSpeedLine(String lower) {
        if (lower.contains("fishing speed")
                || lower.contains("mining speed")
                || lower.contains("walk speed")
                || lower.contains("attack speed")
                || lower.contains("sweep speed")) {
            return true;
        }
        return lower.matches(".*\\bspeed\\s*:\\s*[+\\d].*");
    }

    private static boolean containsAttributeWord(String lower, String word) {
        int idx = lower.indexOf(word);
        while (idx >= 0) {
            boolean startOk = idx == 0 || !Character.isLetterOrDigit(lower.charAt(idx - 1));
            int end = idx + word.length();
            boolean endOk = end >= lower.length() || !Character.isLetterOrDigit(lower.charAt(end));
            if (startOk && endOk) return true;
            idx = lower.indexOf(word, idx + 1);
        }
        return false;
    }

    public static Optional<MatchResult> match(ItemStack stack) {
        return matchParsed(SkyblockStackData.parse(stack));
    }

    public static Optional<MatchResult> matchParsed(SkyblockStackData.Parsed parsed) {
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        Optional<Set<String>> pair = parsed.exactPairForSalvage();
        if (pair.isEmpty()) {
            return Optional.empty();
        }
        String id = parsed.itemId() != null ? parsed.itemId().toUpperCase(Locale.ROOT) : "";
        String name = parsed.displayName().toLowerCase(Locale.ROOT);
        Set<String> attrs = pair.get();

        for (Entry e : entries) {
            if (!itemMatches(e, id, name)) continue;
            if (isExactPair(attrs, e.attr1(), e.attr2())) {
                return Optional.of(new MatchResult(e.feathers(), e.attr1(), e.attr2()));
            }
        }
        return Optional.empty();
    }

    public static String formatDebug(SkyblockStackData.Parsed parsed) {
        Optional<MatchResult> match = matchParsed(parsed);
        return "id="
                + parsed.itemId()
                + " legacy="
                + parsed.legacyLoreAttributes()
                + " nbt="
                + parsed.nbtAttributes()
                + " active="
                + parsed.activeLoreAttributes()
                + " → "
                + match.map(m -> m.feathers() + " (" + m.attr1() + "+" + m.attr2() + ")")
                        .orElse("no row on " + WIKI_URL);
    }

    private static boolean itemMatches(Entry e, String id, String displayLower) {
        if (!e.itemId().isEmpty() && !id.isEmpty() && e.itemId().equalsIgnoreCase(id)) {
            return true;
        }
        String base = e.itemName().toLowerCase(Locale.ROOT);
        return displayLower.contains(base);
    }

    private static boolean isExactPair(Set<String> found, String a1, String a2) {
        return found.size() == 2 && found.contains(a1) && found.contains(a2);
    }
}
