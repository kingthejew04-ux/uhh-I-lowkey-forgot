package com.blockhighlighter;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

public class HighlighterClient implements ClientModInitializer {
	public static final String MOD_ID = "blockhighlighter";
	public static KeyMapping OPEN_KEY;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitializeClient() {
		Config.load();

		KeyMapping.Category category = KeyMapping.Category.register(id("main"));
		// Right Shift by default so it doesn't clash with sneaking (Left Shift). Rebind in Controls if you like.
		OPEN_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.blockhighlighter.open_menu",
				InputConstants.Type.KEYSYM,
				InputConstants.KEY_RSHIFT,
				category
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (OPEN_KEY.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) {
					client.gui.setScreen(new HighlightScreen());
				}
			}
			BlockScanner.tick(client);
		});

		HighlightRenderer.register();
	}
}
