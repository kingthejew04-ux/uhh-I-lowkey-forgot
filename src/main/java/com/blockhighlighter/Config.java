package com.blockhighlighter;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

public final class Config {
	public static final int[] RANGES = {16, 32, 48, 64, 96};
	public static int range = 48;

	private Config() { }

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("blockhighlighter.properties");
	}

	public static void load() {
		Path f = file();
		if (!Files.exists(f)) return;
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(f)) {
			p.load(r);
		} catch (IOException e) {
			return;
		}
		for (Feature feat : Feature.values()) {
			String key = feat.name().toLowerCase();
			feat.enabled = Boolean.parseBoolean(p.getProperty(key + ".enabled", String.valueOf(feat.defaultEnabled)));
			try {
				feat.argb = (int) Long.parseLong(p.getProperty(key + ".color", Integer.toHexString(feat.defaultArgb)), 16);
			} catch (NumberFormatException ignored) {
				feat.argb = feat.defaultArgb;
			}
		}
		try {
			range = Integer.parseInt(p.getProperty("range", "48"));
		} catch (NumberFormatException ignored) {
			range = 48;
		}
	}

	public static void save() {
		Properties p = new Properties();
		for (Feature feat : Feature.values()) {
			String key = feat.name().toLowerCase();
			p.setProperty(key + ".enabled", String.valueOf(feat.enabled));
			p.setProperty(key + ".color", Integer.toHexString(feat.argb));
		}
		p.setProperty("range", String.valueOf(range));
		try (Writer w = Files.newBufferedWriter(file())) {
			p.store(w, "Block Highlighter");
		} catch (IOException ignored) {
		}
	}
}
