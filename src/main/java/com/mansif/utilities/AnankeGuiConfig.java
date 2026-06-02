package com.mansif.utilities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Client layout for the Ananke salvage overlay — `.minecraft/config/MansifUtilities-ananke-gui.json`. */
public final class AnankeGuiConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("MansifUtilities-ananke-gui.json");

    /** Pixels added to the default anchor (right of container GUI). */
    public int offsetX = 0;
    public int offsetY = 0;
    /** Extra Y below the chest summary block for hovered-item salvage lines. */
    public int hoverOffsetY = 44;

    private static AnankeGuiConfig cached;

    public static AnankeGuiConfig get() {
        if (cached == null) {
            cached = load();
        }
        return cached;
    }

    public static AnankeGuiConfig load() {
        if (!Files.isRegularFile(CONFIG_PATH)) {
            cached = new AnankeGuiConfig();
            save(cached);
            return cached;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            AnankeGuiConfig cfg = GSON.fromJson(json, AnankeGuiConfig.class);
            if (cfg == null) {
                cfg = new AnankeGuiConfig();
            }
            cached = cfg;
            return cfg;
        } catch (IOException | JsonSyntaxException ex) {
            MansifUtilities.LOGGER.warn("Could not read {}, using defaults: {}", CONFIG_PATH, ex.toString());
            cached = new AnankeGuiConfig();
            return cached;
        }
    }

    public static void save(AnankeGuiConfig cfg) {
        cached = cfg;
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(cfg), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            MansifUtilities.LOGGER.error("Failed to write {}: {}", CONFIG_PATH, ex.toString());
        }
    }

    public static void update(Consumer<AnankeGuiConfig> edit) {
        AnankeGuiConfig cfg = load();
        edit.accept(cfg);
        save(cfg);
    }

    public static void reset() {
        AnankeGuiConfig cfg = new AnankeGuiConfig();
        save(cfg);
    }

    public static Path configPath() {
        return CONFIG_PATH;
    }
}
