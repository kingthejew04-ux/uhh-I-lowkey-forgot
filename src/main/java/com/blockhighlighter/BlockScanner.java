package com.blockhighlighter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Periodically scans loaded chunks around the player for blocks of enabled features. */
public final class BlockScanner {
	public record Hit(int x, int y, int z, Feature feature) { }

	private static List<Hit> hits = List.of();
	private static int cooldown = 0;

	private BlockScanner() { }

	public static List<Hit> hits() {
		return hits;
	}

	public static void requestRescan() {
		cooldown = 0;
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			hits = List.of();
			return;
		}
		if (cooldown-- > 0) return;
		cooldown = 10;
		scan(mc.level, mc.player.blockPosition());
	}

	private static void scan(ClientLevel level, BlockPos center) {
		List<Feature> active = new ArrayList<>();
		for (Feature f : Feature.values()) {
			if (f.enabled) active.add(f);
		}
		if (active.isEmpty()) {
			hits = List.of();
			return;
		}

		Predicate<BlockState> any = state -> {
			for (Feature f : active) {
				if (f.matches(state)) return true;
			}
			return false;
		};

		int range = Config.range;
		long range2 = (long) range * range;
		int chunkRange = (range + 15) >> 4;
		int pcx = center.getX() >> 4;
		int pcz = center.getZ() >> 4;
		int minY = level.getMinY();

		List<Hit> out = new ArrayList<>();
		for (int cx = pcx - chunkRange; cx <= pcx + chunkRange; cx++) {
			for (int cz = pcz - chunkRange; cz <= pcz + chunkRange; cz++) {
				if (!level.hasChunk(cx, cz)) continue;
				LevelChunk chunk = level.getChunk(cx, cz);
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection section = sections[i];
					if (section.hasOnlyAir() || !section.maybeHas(any)) continue;
					int baseY = minY + i * 16;
					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								BlockState state = section.getBlockState(x, y, z);
								for (Feature f : active) {
									if (!f.matches(state)) continue;
									int wx = (cx << 4) + x;
									int wy = baseY + y;
									int wz = (cz << 4) + z;
									long dx = wx - center.getX();
									long dy = wy - center.getY();
									long dz = wz - center.getZ();
									if (dx * dx + dy * dy + dz * dz <= range2) {
										out.add(new Hit(wx, wy, wz, f));
									}
									break;
								}
							}
						}
					}
				}
			}
		}
		hits = out;
	}
}
