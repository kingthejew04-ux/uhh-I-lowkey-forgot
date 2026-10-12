package com.blockhighlighter;

import java.util.function.Predicate;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

/** One toggleable highlight. Add your own by adding a line here. */
public enum Feature {
	CHEST("Chests", 0x70FFA000, true, s -> s.is(Blocks.CHEST) || s.is(Blocks.TRAPPED_CHEST)),
	ENDER_CHEST("Ender Chests", 0x70B020FF, true, s -> s.is(Blocks.ENDER_CHEST)),
	RESPAWN_ANCHOR("Respawn Anchors", 0x70FF3030, true, s -> s.is(Blocks.RESPAWN_ANCHOR)),
	SPAWNER("Spawners", 0x7030C0FF, false, s -> s.is(Blocks.SPAWNER) || s.is(Blocks.TRIAL_SPAWNER)),
	SHULKER("Shulker Boxes", 0x70FF70D0, false, s -> s.getBlock() instanceof ShulkerBoxBlock),
	ANCIENT_DEBRIS("Ancient Debris", 0x70A06A40, false, s -> s.is(Blocks.ANCIENT_DEBRIS)),
	DIAMOND_ORE("Diamond Ore", 0x7040FFFF, false, s -> s.is(Blocks.DIAMOND_ORE) || s.is(Blocks.DEEPSLATE_DIAMOND_ORE));

	public final String label;
	public final int defaultArgb;
	public final boolean defaultEnabled;
	private final Predicate<BlockState> matcher;

	public boolean enabled;
	public int argb;

	Feature(String label, int defaultArgb, boolean defaultEnabled, Predicate<BlockState> matcher) {
		this.label = label;
		this.defaultArgb = defaultArgb;
		this.defaultEnabled = defaultEnabled;
		this.matcher = matcher;
		this.enabled = defaultEnabled;
		this.argb = defaultArgb;
	}

	public boolean matches(BlockState state) {
		return this.matcher.test(state);
	}
}
