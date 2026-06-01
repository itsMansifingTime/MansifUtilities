package com.mansif.utilities;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class MansifUtilities implements ClientModInitializer {
	public static final String MOD_ID = "mansifutilities";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
    public void onInitializeClient() {
        LOGGER.info("Hello Fabric world! ~Mansif mod active");

        // Write bundled defaults to config on first launch (persists across sessions).
        FlipBridgeConfig.loadAndSync();

        InventoryReader.registerHooks();
        DebugHudRenderer.registerHooks();
        InventoryHighlighter.registerHooks();
        BazaarLog.registerHooks();
        FlipBridgeCommands.register();
        RecipeCommands.register();
        FlipAlertBridge.registerHooks();
//        ItemHoverDisplay.registerHooks();
    }
}

