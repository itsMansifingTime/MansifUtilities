package com.mansif.utilities;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extract SkyBlock item id and attribute names from stack components. */
public final class SkyblockStackData {
    private static final Pattern LITERAL_PATTERN = Pattern.compile("literal\\{([^}]*)}");
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([A-Z0-9_]+)");
    private static final Pattern ATTR_LINE =
            Pattern.compile(
                    "(Mana Pool|Mana Regeneration|Magic Find|Veteran|Dominance|Speed|Lifeline|Blazing Fortune|Fishing Experience|Sea Wisdom|Vitality)",
                    Pattern.CASE_INSENSITIVE);

    private SkyblockStackData() {}

    public record Parsed(
            String itemId,
            String displayName,
            /** Greyed / strikethrough legacy attrs in lore (Ananke salvage uses these). */
            Set<String> legacyLoreAttributes,
            /** Non-strikethrough attribute lines in lore. */
            Set<String> activeLoreAttributes,
            /** Keys in ExtraAttributes.attributes only (usually the rolled pair). */
            Set<String> nbtAttributes) {

        /** Exactly two attrs to test against the wiki table — never a loose superset. */
        public Optional<Set<String>> exactPairForSalvage() {
            if (legacyLoreAttributes.size() == 2) {
                return Optional.of(legacyLoreAttributes);
            }
            if (nbtAttributes.size() == 2) {
                return Optional.of(nbtAttributes);
            }
            if (activeLoreAttributes.size() == 2) {
                return Optional.of(activeLoreAttributes);
            }
            return Optional.empty();
        }
    }

    public static Parsed parse(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new Parsed(null, "", Set.of(), Set.of(), Set.of());
        }

        String displayName = stripFormatting(stack.getHoverName().getString());
        String itemId = null;
        Set<String> legacyLore = new LinkedHashSet<>();
        Set<String> activeLore = new LinkedHashSet<>();
        Set<String> nbtAttrs = new LinkedHashSet<>();

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            CompoundTag root = customData.copyTag();
            CompoundTag extra = root.getCompound("ExtraAttributes").orElse(null);
            if (extra != null) {
                if (extra.contains("id")) {
                    itemId = normalizeHypixelId(extra.getString("id").orElse(null));
                }
                collectNbtAttributePair(extra, nbtAttrs);
            }
        }

        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                collectAttributesFromLoreLine(line, legacyLore, activeLore);
            }
        }

        for (TypedDataComponent<?> component : stack.getComponents()) {
            String raw = component.toString();
            if (raw.startsWith("minecraft:custom_data=>")) {
                Matcher idMatcher = ID_PATTERN.matcher(raw);
                if (idMatcher.find() && itemId == null) {
                    itemId = normalizeHypixelId(idMatcher.group(1));
                }
            }
        }

        itemId = AnankeGodrollLookup.resolveItemId(itemId, displayName);

        return new Parsed(
                itemId,
                displayName,
                Set.copyOf(legacyLore),
                Set.copyOf(activeLore),
                Set.copyOf(nbtAttrs));
    }

    private static void collectNbtAttributePair(CompoundTag extra, Set<String> nbtAttrs) {
        Tag attributesTag = extra.get("attributes");
        if (attributesTag instanceof CompoundTag compound) {
            for (String key : compound.keySet()) {
                addAttributeSlug(nbtAttrs, key);
            }
        }
        for (String legacyKey :
                List.of("legacy_attributes", "old_attributes", "removed_attributes", "deprecated_attributes")) {
            Tag tag = extra.get(legacyKey);
            if (tag instanceof CompoundTag compound) {
                for (String attrKey : compound.keySet()) {
                    addAttributeSlug(nbtAttrs, attrKey);
                }
            }
        }
    }

    private static void collectAttributesFromLoreLine(
            Component line, Set<String> legacy, Set<String> active) {
        final Set<String>[] strikeBucket = new Set[] {new LinkedHashSet<>()};
        final Set<String>[] normalBucket = new Set[] {new LinkedHashSet<>()};

        line.visit(
                (style, text) -> {
                    if (text == null || text.isEmpty()) {
                        return Optional.empty();
                    }
                    String cleaned = stripFormatting(text);
                    Set<String> bucket =
                            style != null && style.isStrikethrough() ? strikeBucket[0] : normalBucket[0];
                    matchAttributeLines(cleaned, bucket);
                    AnankeGodrollLookup.matchAttributesInText(cleaned, bucket);
                    return Optional.empty();
                },
                Style.EMPTY);

        legacy.addAll(strikeBucket[0]);
        active.addAll(normalBucket[0]);

        if (line.getStyle().isStrikethrough()) {
            String whole = stripFormatting(line.getString());
            matchAttributeLines(whole, legacy);
            AnankeGodrollLookup.matchAttributesInText(whole, legacy);
        }
    }

    private static void matchAttributeLines(String text, Set<String> out) {
        if (text == null || text.isBlank()) return;
        Matcher m = ATTR_LINE.matcher(text);
        while (m.find()) {
            String display = AnankeGodrollLookup.displayNameForSlug(m.group(1));
            if (display != null) {
                out.add(display);
            }
        }
    }

    static void addAttributeSlug(Set<String> out, String slug) {
        String display = AnankeGodrollLookup.displayNameForSlug(slug);
        if (display != null) {
            out.add(display);
        }
    }

    static String normalizeHypixelId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String id = raw.trim();
        int semi = id.indexOf(';');
        if (semi > 0) {
            id = id.substring(0, semi);
        }
        return id.toUpperCase(Locale.ROOT);
    }

    static List<String> extractLiterals(String inp) {
        Matcher matcher = LITERAL_PATTERN.matcher(inp);
        ArrayList<String> results = new ArrayList<>();
        while (matcher.find()) {
            results.add(stripFormatting(matcher.group(1)));
        }
        return results;
    }

    static String stripFormatting(String s) {
        if (s == null) return "";
        return s.replaceAll("§.", "").trim();
    }
}
