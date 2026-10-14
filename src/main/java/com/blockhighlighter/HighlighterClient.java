package com.blockhighlighter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Properties;
import java.util.function.Predicate;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

public class HighlighterClient implements ClientModInitializer {
	public static final String MOD_ID = "blockhighlighter";
	public static KeyMapping OPEN_KEY;
	public static KeyMapping ZOOM_KEY;

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
		ZOOM_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.blockhighlighter.zoom",
				InputConstants.Type.KEYSYM,
				InputConstants.KEY_C,
				category
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (OPEN_KEY.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) {
					client.gui.setScreen(new HighlightScreen());
				}
			}
			BlockScanner.tick(client);
			ChunkFinder.tick(client);
			Zoom.tick(client);
		});

		LevelRenderEvents.START_MAIN.register(ctx -> Zoom.frame(Minecraft.getInstance()));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Zoom.restore(client));

		HighlightRenderer.register();
	}

	/** One toggleable highlight (a block type or an entity type). Add your own by adding a line here. */
	public enum Feature {
		// ---- Blocks ----
		CHEST("Chests", 0x70FFA000, true, s -> s.is(Blocks.CHEST) || s.is(Blocks.TRAPPED_CHEST), null),
		ENDER_CHEST("Ender Chests", 0x70B020FF, true, s -> s.is(Blocks.ENDER_CHEST), null),
		RESPAWN_ANCHOR("Respawn Anchors", 0x70FF3030, true, s -> s.is(Blocks.RESPAWN_ANCHOR), null),
		SPAWNER("Spawners", 0x7030C0FF, false, s -> s.is(Blocks.SPAWNER) || s.is(Blocks.TRIAL_SPAWNER), null),
		SHULKER("Shulker Boxes", 0x70FF70D0, false, s -> s.getBlock() instanceof ShulkerBoxBlock, null),
		ANCIENT_DEBRIS("Ancient Debris", 0x70A06A40, false, s -> s.is(Blocks.ANCIENT_DEBRIS), null),
		DIAMOND_ORE("Diamond Ore", 0x7040FFFF, false, s -> s.is(Blocks.DIAMOND_ORE) || s.is(Blocks.DEEPSLATE_DIAMOND_ORE), null),
		AMETHYST_GEODES("Amethyst Geodes", 0x70C070FF, false, s -> s.is(Blocks.BUDDING_AMETHYST), null),
		// ---- Modules (drawn by their own code, not by block/entity matching) ----
		CHUNK_FINDER("Chunk Finder", 0x60FF8800, false, null, null),
		// ---- Entities ----
		PLAYERS("Players", 0x8055FF55, true, null, e -> e instanceof Player),
		HOSTILE_MOBS("Hostile Mobs", 0x80FF5555, false, null, e -> e instanceof Enemy),
		ITEMS("Dropped Items", 0x80FFFF55, false, null, e -> e instanceof ItemEntity);

		public final String label;
		public final int defaultArgb;
		public final boolean defaultEnabled;
		private final Predicate<BlockState> blockMatcher;
		private final Predicate<Entity> entityMatcher;

		public boolean enabled;
		public int argb;

		Feature(String label, int defaultArgb, boolean defaultEnabled, Predicate<BlockState> blockMatcher, Predicate<Entity> entityMatcher) {
			this.label = label;
			this.defaultArgb = defaultArgb;
			this.defaultEnabled = defaultEnabled;
			this.blockMatcher = blockMatcher;
			this.entityMatcher = entityMatcher;
			this.enabled = defaultEnabled;
			this.argb = defaultArgb;
		}

		public boolean isBlock() {
			return this.blockMatcher != null;
		}

		public boolean isEntity() {
			return this.entityMatcher != null;
		}

		public boolean matches(BlockState state) {
			return this.blockMatcher != null && this.blockMatcher.test(state);
		}

		public boolean matchesEntity(Entity entity) {
			return this.entityMatcher != null && this.entityMatcher.test(entity);
		}
	}

	public static final class Config {
		public static final int[] RANGES = {16, 32, 48, 64, 96, 200, 300, 400};
		public static final int[] ZOOM_LEVELS = {2, 3, 4, 6, 8, 10, 15, 20};
		public static boolean zoomEnabled = true;
		public static boolean zoomSmooth = true;
		public static boolean zoomLowerSens = true;
		public static int zoomIndex = 2;
		public static final String[] FINDER_SENS_NAMES = {"Low", "Medium", "High", "Max"};
		public static final int[] FINDER_THRESHOLDS = {60, 25, 10, 4};
		public static final int[] FINDER_DISTANCES = {2, 4, 6, 8, 12, 16, 24, 32};
		public static final String[] FINDER_DEPTH_NAMES = {"Any", "Y < 60", "Y < 45", "Y < 30", "Y < 0"};
		public static final int[] FINDER_DEPTHS = {Integer.MAX_VALUE, 60, 45, 30, 0};
		public static int finderDepth = 1;
		public static int finderSens = 1;
		public static int finderDist = 3;

		public static int finderThreshold() {
			return FINDER_THRESHOLDS[finderSens];
		}

		/** Blocks at or above this Y are ignored by Chunk Finder. */
		public static int finderMaxY() {
			return FINDER_DEPTHS[finderDepth];
		}

		public static int finderDistance() {
			return FINDER_DISTANCES[finderDist];
		}
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
			zoomEnabled = Boolean.parseBoolean(p.getProperty("zoom.enabled", "true"));
			zoomSmooth = Boolean.parseBoolean(p.getProperty("zoom.smooth", "true"));
			zoomLowerSens = Boolean.parseBoolean(p.getProperty("zoom.lowersens", "true"));
			try {
				zoomIndex = Math.max(0, Math.min(ZOOM_LEVELS.length - 1, Integer.parseInt(p.getProperty("zoom.index", "2"))));
			} catch (NumberFormatException ignored) {
				zoomIndex = 2;
			}
			try {
				finderSens = Math.max(0, Math.min(FINDER_THRESHOLDS.length - 1, Integer.parseInt(p.getProperty("finder.sens", "1"))));
				finderDist = Math.max(0, Math.min(FINDER_DISTANCES.length - 1, Integer.parseInt(p.getProperty("finder.dist", "3"))));
			} catch (NumberFormatException ignored) {
				finderSens = 1;
				finderDist = 3;
			}
			try {
				finderDepth = Math.max(0, Math.min(FINDER_DEPTHS.length - 1, Integer.parseInt(p.getProperty("finder.depth", "1"))));
			} catch (NumberFormatException ignored) {
				finderDepth = 1;
			}
			for (Indicator ind : Indicator.values()) {
				ind.enabled = Boolean.parseBoolean(p.getProperty("indicator." + ind.name().toLowerCase(), "true"));
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
			p.setProperty("zoom.enabled", String.valueOf(zoomEnabled));
			p.setProperty("zoom.smooth", String.valueOf(zoomSmooth));
			p.setProperty("zoom.lowersens", String.valueOf(zoomLowerSens));
			p.setProperty("zoom.index", String.valueOf(zoomIndex));
			p.setProperty("finder.sens", String.valueOf(finderSens));
			p.setProperty("finder.dist", String.valueOf(finderDist));
			p.setProperty("finder.depth", String.valueOf(finderDepth));
			for (Indicator ind : Indicator.values()) {
				p.setProperty("indicator." + ind.name().toLowerCase(), String.valueOf(ind.enabled));
			}
			try (Writer w = Files.newBufferedWriter(file())) {
				p.store(w, "Block Highlighter");
			} catch (IOException ignored) {
			}
		}
	}

	/** Periodically scans loaded chunks around the player for blocks of enabled features. */
	public static final class BlockScanner {
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
				if (f.enabled && f.isBlock()) active.add(f);
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

	/** Draws filled, translucent boxes through walls. Based on Fabric's custom render pipeline docs for 26.2. */
	public static final class HighlightRenderer {
		private record BoxState(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float r, float g, float b, float a) { }

		private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipelines.register(
				RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
						.withLocation(HighlighterClient.id("pipeline/filled_through_walls"))
						.withDepthStencilState(Optional.empty())
						.build()
		);

		private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
		private static final Vector3f MODEL_OFFSET = new Vector3f();
		private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
		private static final StagedVertexBuffer STAGED_BUFFER = new StagedVertexBuffer(() -> "Block Highlighter Buffer", RenderType.SMALL_BUFFER_SIZE);

		private static List<BoxState> boxes = List.of();

		private HighlightRenderer() { }

		public static void register() {
			LevelExtractionEvents.END_EXTRACTION.register(HighlightRenderer::extract);
			LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(HighlightRenderer::renderAndDraw);
			ClientLifecycleEvents.CLIENT_STOPPING.register(client -> STAGED_BUFFER.close());
		}

		// Extraction phase: copy everything we need into immutable state.
		private static void extract(LevelExtractionContext context) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null || mc.player == null) {
				boxes = List.of();
				return;
			}
			List<BoxState> out = new ArrayList<>();

			// Blocks (from the scanner cache)
			for (BlockScanner.Hit hit : BlockScanner.hits()) {
				Feature f = hit.feature();
				if (!f.enabled) continue;
				out.add(box(hit.x(), hit.y(), hit.z(), hit.x() + 1, hit.y() + 1, hit.z() + 1, f.argb));
			}

			// Chunk Finder: a flat slab over each flagged chunk plus a thin pillar so it's visible from far away
			if (Feature.CHUNK_FINDER.enabled) {
				int c = Feature.CHUNK_FINDER.argb;
				int strong = (Math.min(255, ((c >>> 24) & 0xFF) + 0x40) << 24) | (c & 0xFFFFFF);
				for (ChunkFinder.Result r : ChunkFinder.flagged()) {
					float x = r.cx() * 16f, z = r.cz() * 16f;
					out.add(box(x, r.meanY(), z, x + 16f, r.meanY() + 0.4f, z + 16f, c));
					out.add(box(x + 7.75f, r.minY(), z + 7.75f, x + 8.25f, r.maxY() + 1f, z + 8.25f, strong));
				}
			}

			// Entities (computed every frame so they move smoothly)
			List<Feature> entityFeatures = new ArrayList<>();
			for (Feature f : Feature.values()) {
				if (f.enabled && f.isEntity()) entityFeatures.add(f);
			}
			if (!entityFeatures.isEmpty()) {
				float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
				AABB area = mc.player.getBoundingBox().inflate(Config.range);
				double range2 = (double) Config.range * Config.range;
				for (Entity e : mc.level.getEntities((Entity) null, area, other -> other != mc.player)) {
					if (e.distanceToSqr(mc.player) > range2) continue;
					for (Feature f : entityFeatures) {
						if (!f.matchesEntity(e)) continue;
						Vec3 p = e.getPosition(pt);
						AABB bb = e.getBoundingBox().move(p.x - e.getX(), p.y - e.getY(), p.z - e.getZ());
						out.add(box((float) bb.minX, (float) bb.minY, (float) bb.minZ, (float) bb.maxX, (float) bb.maxY, (float) bb.maxZ, f.argb));
						break;
					}
				}
			}
			boxes = out;
		}

		private static BoxState box(float x1, float y1, float z1, float x2, float y2, float z2, int c) {
			return new BoxState(x1, y1, z1, x2, y2, z2,
					((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f, ((c >>> 24) & 0xFF) / 255f);
		}

		// Drawing phase.
		private static void renderAndDraw(LevelRenderContext context) {
			RenderPipeline pipeline = FILLED_THROUGH_WALLS;
			VertexFormat formatBinding = pipeline.getVertexFormatBinding(0);
			assert formatBinding != null;

			PrimitiveTopology primitive = pipeline.getPrimitiveTopology();
			StagedVertexBuffer.Draw draw = STAGED_BUFFER.appendDraw(formatBinding, primitive,
					primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

			PoseStack matrices = context.poseStack();
			Vec3 camera = context.levelState().cameraRenderState.pos;
			matrices.pushPose();
			matrices.translate(-camera.x, -camera.y, -camera.z);
			if (!boxes.isEmpty()) {
				VertexConsumer builder = STAGED_BUFFER.getVertexBuilder(draw);
				Matrix4fc pose = matrices.last().pose();
				for (BoxState b : boxes) {
					filledBox(pose, builder, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), b.r(), b.g(), b.b(), b.a());
				}
			}
			matrices.popPose();

			STAGED_BUFFER.upload();
			StagedVertexBuffer.ExecuteInfo info = STAGED_BUFFER.getExecuteInfo(draw);
			if (info != null) {
				drawInfo(Minecraft.getInstance(), info, pipeline);
			}
			STAGED_BUFFER.endFrame();
		}

		private static void filledBox(Matrix4fc m, VertexConsumer buf, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float r, float g, float b, float a) {
			// Front
			buf.addVertex(m, minX, minY, maxZ).setColor(r, g, b, a);
			buf.addVertex(m, maxX, minY, maxZ).setColor(r, g, b, a);
			buf.addVertex(m, maxX, maxY, maxZ).setColor(r, g, b, a);
			buf.addVertex(m, minX, maxY, maxZ).setColor(r, g, b, a);
			// Back
			buf.addVertex(m, maxX, minY, minZ).setColor(r, g, b, a);
			buf.addVertex(m, minX, minY, minZ).setColor(r, g, b, a);
			buf.addVertex(m, minX, maxY, minZ).setColor(r, g, b, a);
			buf.addVertex(m, maxX, maxY, minZ).setColor(r, g, b, a);
			// Left
			buf.addVertex(m, minX, minY, minZ).setColor(r, g, b, a);
			buf.addVertex(m, minX, minY, maxZ).setColor(r, g, b, a);
			buf.addVertex(m, minX, maxY, maxZ).setColor(r, g, b, a);
			buf.addVertex(m, minX, maxY, minZ).setColor(r, g, b, a);
			// Right
			buf.addVertex(m, maxX, minY, maxZ).setColor(r, g, b, a);
			buf.addVertex(m, maxX, minY, minZ).setColor(r, g, b, a);
			buf.addVertex(m, maxX, maxY, minZ).setColor(r, g, b, a);
			buf.addVertex(m, maxX, maxY, maxZ).setColor(r, g, b, a);
			// Top
			buf.addVertex(m, minX, maxY, maxZ).setColor(r, g, b, a);
			buf.addVertex(m, maxX, maxY, maxZ).setColor(r, g, b, a);
			buf.addVertex(m, maxX, maxY, minZ).setColor(r, g, b, a);
			buf.addVertex(m, minX, maxY, minZ).setColor(r, g, b, a);
			// Bottom
			buf.addVertex(m, minX, minY, minZ).setColor(r, g, b, a);
			buf.addVertex(m, maxX, minY, minZ).setColor(r, g, b, a);
			buf.addVertex(m, maxX, minY, maxZ).setColor(r, g, b, a);
			buf.addVertex(m, minX, minY, maxZ).setColor(r, g, b, a);
		}

		private static void drawInfo(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
			GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
					.writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

			RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
			GpuTextureView colorTexture = mainTarget.getColorTextureView();
			assert colorTexture != null;

			try (RenderPass pass = RenderSystem.getDevice()
					.createCommandEncoder()
					.createRenderPass(() -> HighlighterClient.MOD_ID + " highlight pass", colorTexture, Optional.empty(), mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
				pass.setPipeline(pipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", dynamicTransforms);
				pass.setVertexBuffer(0, info.vertexBuffer().slice());
				pass.setIndexBuffer(info.indexBuffer(), info.indexType());
				pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
			}
		}
	}

	/**
	 * The dropdown menu. Left click a row to toggle it. Right click a row (or click its swatch) to edit its color.
	 * Press the open key again (or Esc) to close.
	 */
	/**
	 * Things Chunk Finder looks for. "Growth" indicators only reach these states when a chunk has been loaded and ticking
	 * for a while (plants grow, bees fill nests), which suggests someone spent time nearby.
	 */
	public enum Indicator {
		PLACED("Player-made blocks", 30),
		AMETHYST("Grown amethyst", 15),
		KELP("Fully grown kelp", 15),
		CAVE_VINES("Glow berries", 15),
		BAMBOO("Mature bamboo", 15),
		COCOA("Ripe cocoa", 15),
		BEE_NESTS("Full honey nests", 15),
		SWEET_BERRIES("Ripe sweet berries", 15);

		public final String label;
		/** Max points one indicator can add to a chunk, so one common plant can't flag a chunk alone at higher thresholds. */
		public final int cap;
		public boolean enabled = true;

		Indicator(String label, int cap) {
			this.label = label;
			this.cap = cap;
		}
	}

	/**
	 * Chunk Finder: scores every loaded chunk by how many player-placed-looking blocks it contains and flags the ones
	 * over the sensitivity threshold. Only chunks your game has loaded can be scanned. Scanning is spread over many ticks
	 * so it never causes a lag spike.
	 */
	public static final class ChunkFinder {
		public record Result(int cx, int cz, int score, float meanY, int minY, int maxY, long scannedTick, int[] sums) { }

		private static final int SCANS_PER_TICK = 8;
		private static final int CHECKS_PER_TICK = 64;
		private static final long STALE_TICKS = 1200;

		// Blocks that almost never generate naturally (strong signal) and ones that are common in player builds (weak signal).
		private static final Set<String> STRONG = Set.of(
				"hopper", "piston", "sticky_piston", "observer", "repeater", "comparator", "redstone_lamp", "dropper",
				"enchanting_table", "beacon", "conduit", "ender_chest", "respawn_anchor", "glass", "tnt", "note_block", "jukebox",
				"iron_block", "diamond_block", "emerald_block", "redstone_block", "lapis_block", "slime_block", "honey_block",
				"target", "lightning_rod", "daylight_detector", "crafter");
		private static final Set<String> WEAK = Set.of(
				"torch", "wall_torch", "soul_torch", "soul_wall_torch", "crafting_table", "furnace", "blast_furnace", "smoker",
				"chest", "trapped_chest", "barrel", "ladder");

		private static final Map<Block, Integer> WEIGHTS = new IdentityHashMap<>();
		private static final Map<Long, Result> results = new HashMap<>();
		private static final Map<BlockState, int[]> STATE_WEIGHTS = new IdentityHashMap<>();
		private static final int[] NONE = new int[0];
		private static List<Result> flagged = List.of();
		private static long ticks = 0;
		private static int cursor = 0;
		private static int hereCx = 0, hereCz = 0;

		private ChunkFinder() { }

		/** Debug text for the menu: how many chunks were scanned and flagged. */
		public static String scannedText() {
			if (!Feature.CHUNK_FINDER.enabled) return "finder is off";
			return results.size() + " chunks, " + flagged.size() + " flagged";
		}

		/** Debug text for the menu: the score of the chunk you're standing in and its strongest indicator. */
		public static String hereText() {
			if (!Feature.CHUNK_FINDER.enabled) return "finder is off";
			Result r = results.get(key(hereCx, hereCz));
			if (r == null) return "not scanned yet";
			Indicator[] inds = Indicator.values();
			int best = -1;
			for (int i = 0; i < inds.length; i++) {
				if (r.sums()[i] > 0 && (best < 0 || r.sums()[i] > r.sums()[best])) best = i;
			}
			return best < 0 ? r.score() + " pts" : r.score() + " (" + inds[best].label + ")";
		}

		public static List<Result> flagged() {
			return flagged;
		}

		private static long key(int cx, int cz) {
			return ((long) cx & 0xFFFFFFFFL) | ((long) cz << 32);
		}

		private static int placedWeight(BlockState state) {
			Block block = state.getBlock();
			Integer cached = WEIGHTS.get(block);
			if (cached != null) return cached;
			String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
			int w = 0;
			if (STRONG.contains(path) || path.endsWith("_concrete") || path.endsWith("_concrete_powder")
					|| path.endsWith("stained_glass") || path.endsWith("stained_glass_pane") || path.endsWith("_glazed_terracotta")) {
				w = 3;
			} else if (WEAK.contains(path) || path.endsWith("_bed")) {
				w = 1;
			}
			WEIGHTS.put(block, w);
			return w;
		}

		public static void tick(Minecraft mc) {
			if (!Feature.CHUNK_FINDER.enabled || mc.level == null || mc.player == null) {
				if (!results.isEmpty() || !flagged.isEmpty()) {
					results.clear();
					flagged = List.of();
				}
				return;
			}
			ticks++;

			ClientLevel level = mc.level;
			int dist = Config.finderDistance();
			int pcx = mc.player.blockPosition().getX() >> 4;
			int pcz = mc.player.blockPosition().getZ() >> 4;
			hereCx = pcx;
			hereCz = pcz;
			int side = dist * 2 + 1;
			int total = side * side;
			int dist2 = dist * dist;

			int scans = 0;
			for (int n = 0; n < CHECKS_PER_TICK && scans < SCANS_PER_TICK; n++) {
				cursor = (cursor + 1) % total;
				int dx = cursor % side - dist;
				int dz = cursor / side - dist;
				if (dx * dx + dz * dz > dist2) continue;
				int cx = pcx + dx, cz = pcz + dz;
				if (!level.hasChunk(cx, cz)) continue;
				Result old = results.get(key(cx, cz));
				if (old != null && ticks - old.scannedTick() < STALE_TICKS) continue;
				results.put(key(cx, cz), scanChunk(level, cx, cz));
				scans++;
			}

			if (ticks % 100 == 0) {
				results.values().removeIf(r -> {
					int dx = r.cx() - pcx, dz = r.cz() - pcz;
					return dx * dx + dz * dz > (dist + 1) * (dist + 1) || !level.hasChunk(r.cx(), r.cz());
				});
			}

			if (ticks % 10 == 0) {
				int threshold = Config.finderThreshold();
				List<Result> out = new ArrayList<>();
				for (Result r : results.values()) {
					int dx = r.cx() - pcx, dz = r.cz() - pcz;
					if (dx * dx + dz * dz <= dist2 && r.score() >= threshold) out.add(r);
				}
				flagged = out;
			}
		}

		/** Call after changing which indicators are enabled so chunks get rescored. */
		public static void invalidate() {
			results.clear();
			flagged = List.of();
		}

		/** Points each indicator awards for this block state (indexed by Indicator ordinal). Cached per state. */
		private static int[] weights(BlockState s) {
			int[] cached = STATE_WEIGHTS.get(s);
			if (cached != null) return cached;

			int[] w = new int[Indicator.values().length];
			w[Indicator.PLACED.ordinal()] = placedWeight(s);
			if (s.is(Blocks.AMETHYST_CLUSTER)) {
				w[Indicator.AMETHYST.ordinal()] = 3;
			} else if (s.is(Blocks.LARGE_AMETHYST_BUD)) {
				w[Indicator.AMETHYST.ordinal()] = 2;
			}
			if (s.is(Blocks.KELP) && s.getValue(BlockStateProperties.AGE_25) >= 24) {
				w[Indicator.KELP.ordinal()] = 2;
			}
			if ((s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT)) && s.getValue(BlockStateProperties.BERRIES)) {
				w[Indicator.CAVE_VINES.ordinal()] = 1;
			}
			if (s.is(Blocks.BAMBOO) && s.getValue(BlockStateProperties.AGE_1) == 1) {
				w[Indicator.BAMBOO.ordinal()] = 1;
			}
			if (s.is(Blocks.COCOA) && s.getValue(BlockStateProperties.AGE_2) == 2) {
				w[Indicator.COCOA.ordinal()] = 2;
			}
			if ((s.is(Blocks.BEE_NEST) || s.is(Blocks.BEEHIVE)) && s.getValue(BlockStateProperties.LEVEL_HONEY) == 5) {
				w[Indicator.BEE_NESTS.ordinal()] = 3;
			}
			if (s.is(Blocks.SWEET_BERRY_BUSH) && s.getValue(BlockStateProperties.AGE_3) == 3) {
				w[Indicator.SWEET_BERRIES.ordinal()] = 2;
			}

			int[] result = NONE;
			for (int v : w) {
				if (v > 0) {
					result = w;
					break;
				}
			}
			STATE_WEIGHTS.put(s, result);
			return result;
		}

		/** True if this state matters to at least one enabled indicator. */
		private static boolean relevant(BlockState s) {
			int[] w = weights(s);
			if (w == NONE) return false;
			Indicator[] inds = Indicator.values();
			for (int i = 0; i < inds.length; i++) {
				if (w[i] > 0 && inds[i].enabled) return true;
			}
			return false;
		}

		private static Result scanChunk(ClientLevel level, int cx, int cz) {
			LevelChunk chunk = level.getChunk(cx, cz);
			LevelChunkSection[] sections = chunk.getSections();
			int minWorldY = level.getMinY();
			int maxScanY = Config.finderMaxY();
			Indicator[] inds = Indicator.values();
			int[] sums = new int[inds.length];
			int count = 0;
			long sumY = 0;
			int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

			for (int i = 0; i < sections.length; i++) {
				LevelChunkSection section = sections[i];
				int baseY = minWorldY + i * 16;
				if (baseY >= maxScanY) break; // everything from here up is above the depth limit
				if (section.hasOnlyAir() || !section.maybeHas(ChunkFinder::relevant)) continue;
				for (int y = 0; y < 16; y++) {
					if (baseY + y >= maxScanY) break;
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							int[] w = weights(section.getBlockState(x, y, z));
							if (w == NONE) continue;
							boolean hit = false;
							for (int k = 0; k < inds.length; k++) {
								if (w[k] > 0 && inds[k].enabled) {
									sums[k] += w[k];
									hit = true;
								}
							}
							if (!hit) continue;
							int wy = baseY + y;
							count++;
							sumY += wy;
							minY = Math.min(minY, wy);
							maxY = Math.max(maxY, wy);
						}
					}
				}
			}

			int score = 0;
			for (int k = 0; k < inds.length; k++) {
				score += Math.min(sums[k], inds[k].cap);
			}
			float meanY = count == 0 ? 0f : (float) sumY / count;
			return new Result(cx, cz, score, meanY, count == 0 ? 0 : minY, count == 0 ? 0 : maxY, ticks, sums);
		}
	}

	/**
	 * Hold-to-zoom. Works by temporarily overriding the FOV (and optionally sensitivity) options while the key is held,
	 * then restoring your real values. No mixins needed.
	 */
	public static final class Zoom {
		private static Field valueField;
		private static boolean reflectionFailed = false;

		private static boolean active = false;
		private static int savedFov;
		private static double savedSens;
		private static double factor = 1.0;
		private static long lastNs = 0;

		private Zoom() { }

		public static double targetLevel() {
			return Config.ZOOM_LEVELS[Config.zoomIndex];
		}

		/** Runs every frame (from the level render event). */
		public static void frame(Minecraft mc) {
			if (mc.options == null) return;
			long now = System.nanoTime();
			double dt = lastNs == 0 ? 0.016 : Math.min(0.1, (now - lastNs) / 1e9);
			lastNs = now;

			boolean want = Config.zoomEnabled && mc.player != null && mc.level != null
					&& mc.gui.screen() == null && ZOOM_KEY.isDown();

			if (!active && !want) return;

			OptionInstance<Integer> fovOpt = mc.options.fov();
			OptionInstance<Double> sensOpt = mc.options.sensitivity();

			if (!active) {
				savedFov = fovOpt.get();
				savedSens = sensOpt.get();
				factor = 1.0;
				active = true;
			}

			double target = want ? targetLevel() : 1.0;
			if (Config.zoomSmooth) {
				factor += (target - factor) * (1.0 - Math.exp(-dt * 16.0));
			} else {
				factor = target;
			}

			if (!want && Math.abs(factor - 1.0) < 0.02) {
				restore(mc);
				return;
			}

			setValue(fovOpt, Math.max(1, (int) Math.round(savedFov / factor)), Math.max(30, (int) Math.round(savedFov / factor)));
			if (Config.zoomLowerSens) {
				double s = savedSens / Math.pow(factor, 0.8);
				setValue(sensOpt, s, Math.max(0.0, Math.min(1.0, s)));
			}
		}

		/** Safety net: if the world closes while zooming, put the options back. */
		public static void tick(Minecraft mc) {
			if (active && (mc.level == null || mc.player == null)) restore(mc);
		}

		public static void restore(Minecraft mc) {
			if (!active || mc == null || mc.options == null) return;
			setValue(mc.options.fov(), savedFov, savedFov);
			setValue(mc.options.sensitivity(), savedSens, savedSens);
			active = false;
			factor = 1.0;
		}

		/** Writes straight into the option's field so values outside the slider's range (like FOV 10) are allowed. */
		private static <T> void setValue(OptionInstance<T> opt, T value, T clampedFallback) {
			if (!reflectionFailed) {
				try {
					if (valueField == null) {
						valueField = OptionInstance.class.getDeclaredField("value");
						valueField.setAccessible(true);
					}
					valueField.set(opt, value);
					return;
				} catch (ReflectiveOperationException | RuntimeException e) {
					reflectionFailed = true;
				}
			}
			opt.set(clampedFallback);
		}
	}

	/** One line in the menu. */
	private static final class Row {
		final String label;
		final Feature feature;          // highlight rows (checkbox + color swatch)
		final BooleanSupplier checked;  // toggle rows (checkbox only)
		final Runnable toggle;
		final Supplier<String> value;   // cycle rows (shows a value, click to change)
		final IntConsumer cycle;

		private Row(String label, Feature feature, BooleanSupplier checked, Runnable toggle, Supplier<String> value, IntConsumer cycle) {
			this.label = label;
			this.feature = feature;
			this.checked = checked;
			this.toggle = toggle;
			this.value = value;
			this.cycle = cycle;
		}

		static Row feature(Feature f) {
			return new Row(f.label, f, () -> f.enabled, () -> {
				f.enabled = !f.enabled;
				BlockScanner.requestRescan();
			}, null, null);
		}

		static Row toggle(String label, BooleanSupplier checked, Runnable toggle) {
			return new Row(label, null, checked, toggle, null, null);
		}

		static Row info(String label, Supplier<String> value) {
			return new Row(label, null, null, null, value, null);
		}

		static Row cycle(String label, Supplier<String> value, IntConsumer cycle) {
			return new Row(label, null, null, null, value, cycle);
		}
	}

	/** A collapsible group of rows. To add a new section, add one more entry in HighlightScreen's constructor. */
	private static final class Section {
		final String title;
		final List<Row> rows;
		boolean collapsed = false;

		Section(String title, List<Row> rows) {
			this.title = title;
			this.rows = rows;
		}
	}

	/**
	 * The menu. Left click a row to toggle/change it. Right click a highlight row (or click its swatch) to edit its color.
	 * Click a section header to collapse it. Press the open key again (or Esc) to close.
	 */
	public static class HighlightScreen extends Screen {
		private static final int PX = 8, PY = 8, W = 190, HEADER_H = 16, ROW_H = 16;
		private static final int POP_W = 150, SLIDER_H = 14;
		private static final String[] CHANNELS = {"R", "G", "B", "A"};
		private static final int[] SHIFTS = {16, 8, 0, 24};

		// Kept static so sections stay collapsed/expanded between openings.
		private static List<Section> sections;

		private Feature picker = null;
		private int pickerY = 0;
		private boolean indicatorsOpen = false;
		private int indicatorsY = 0;
		private int dragging = -1;

		public HighlightScreen() {
			super(Component.literal("Block Highlighter"));
			if (sections == null) {
				sections = buildSections();
			}
		}

		private static List<Section> buildSections() {
			List<Row> highlights = new ArrayList<>();
			List<Row> entities = new ArrayList<>();
			for (Feature f : Feature.values()) {
				if (f == Feature.CHUNK_FINDER) continue; // has its own section below
				(f.isEntity() ? entities : highlights).add(Row.feature(f));
			}
			highlights.add(Row.cycle("Range", () -> Config.range + " blocks", dir -> {
				int idx = 0;
				for (int i = 0; i < Config.RANGES.length; i++) {
					if (Config.RANGES[i] == Config.range) idx = i;
				}
				Config.range = Config.RANGES[Math.floorMod(idx + dir, Config.RANGES.length)];
				BlockScanner.requestRescan();
			}));

			List<Row> utilities = new ArrayList<>();
			utilities.add(Row.toggle("Zoom (hold C)", () -> Config.zoomEnabled, () -> Config.zoomEnabled = !Config.zoomEnabled));
			utilities.add(Row.cycle("Zoom level", () -> Config.ZOOM_LEVELS[Config.zoomIndex] + "x",
					dir -> Config.zoomIndex = Math.floorMod(Config.zoomIndex + dir, Config.ZOOM_LEVELS.length)));
			utilities.add(Row.toggle("Smooth zoom", () -> Config.zoomSmooth, () -> Config.zoomSmooth = !Config.zoomSmooth));
			utilities.add(Row.toggle("Lower sensitivity", () -> Config.zoomLowerSens, () -> Config.zoomLowerSens = !Config.zoomLowerSens));

			List<Row> finder = new ArrayList<>();
			finder.add(Row.feature(Feature.CHUNK_FINDER));
			finder.add(Row.cycle("Sensitivity", () -> Config.FINDER_SENS_NAMES[Config.finderSens],
					dir -> Config.finderSens = Math.floorMod(Config.finderSens + dir, Config.FINDER_THRESHOLDS.length)));
			finder.add(Row.cycle("Scan distance", () -> Config.finderDistance() + " chunks",
					dir -> Config.finderDist = Math.floorMod(Config.finderDist + dir, Config.FINDER_DISTANCES.length)));

			finder.add(Row.cycle("Depth", () -> Config.FINDER_DEPTH_NAMES[Config.finderDepth], dir -> {
				Config.finderDepth = Math.floorMod(Config.finderDepth + dir, Config.FINDER_DEPTHS.length);
				ChunkFinder.invalidate();
			}));
			finder.add(Row.info("Scanned", ChunkFinder::scannedText));
			finder.add(Row.info("Here", ChunkFinder::hereText));

			// Add more sections here later, e.g. new Section("Movement", List.of(...))
			List<Section> out = new ArrayList<>();
			out.add(new Section("Highlights", highlights));
			out.add(new Section("Entities", entities));
			out.add(new Section("Chunk Finder", finder));
			out.add(new Section("Utilities", utilities));
			return out;
		}

		@Override
		public boolean isPauseScreen() {
			return false; // keep the world running so you can see changes live
		}

		@Override
		public void onClose() {
			Config.save();
			super.onClose();
		}

		/** One drawable line: either a section header (row == null) or a row. */
		private record Slot(int y, Section section, Row row) { }

		private List<Slot> layout() {
			List<Slot> slots = new ArrayList<>();
			int y = PY;
			for (Section sec : sections) {
				slots.add(new Slot(y, sec, null));
				y += HEADER_H;
				if (!sec.collapsed) {
					for (Row r : sec.rows) {
						slots.add(new Slot(y, sec, r));
						y += ROW_H;
					}
				}
				y += 4;
			}
			return slots;
		}

		private int popX() {
			return PX + W + 6;
		}

		private int sliderY(int i) {
			return pickerY + 16 + i * (SLIDER_H + 4);
		}

		private int popH() {
			return 16 + 4 * (SLIDER_H + 4) + 30;
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
			// No super call: we deliberately skip the dimmed/blurred background so you can see the world.
			int lastY = PY;
			for (Slot slot : layout()) {
				int y = slot.y();
				lastY = y + (slot.row() == null ? HEADER_H : ROW_H);
				if (slot.row() == null) {
					g.fill(PX, y, PX + W, y + HEADER_H, 0xE0181818);
					g.text(this.font, (slot.section().collapsed ? "[+] " : "[-] ") + slot.section().title, PX + 6, y + 4, 0xFFFFFFFF, true);
					continue;
				}
				Row r = slot.row();
				boolean hover = mouseX >= PX && mouseX < PX + W && mouseY >= y && mouseY < y + ROW_H;
				g.fill(PX, y, PX + W, y + ROW_H, hover ? 0xE0383838 : 0xE0262626);

				if (r.value != null) {
					g.text(this.font, r.label, PX + 6, y + 4, 0xFFDDDDDD, true);
					String v = r.value.get();
					g.text(this.font, v, PX + W - 6 - this.font.width(v), y + 4, 0xFFFFFF55, true);
					continue;
				}

				boolean on = r.checked.getAsBoolean();
				g.outline(PX + 5, y + 3, 10, 10, 0xFFAAAAAA);
				if (on) g.fill(PX + 7, y + 5, PX + 13, y + 11, 0xFF55FF55);
				g.text(this.font, r.label, PX + 20, y + 4, on ? 0xFFFFFFFF : 0xFF888888, true);

				if (r.feature != null) {
					g.fill(PX + W - 24, y + 3, PX + W - 6, y + 13, r.feature.argb | 0xFF000000);
					g.outline(PX + W - 24, y + 3, 18, 10, picker == r.feature ? 0xFFFFFF55 : 0xFFFFFFFF);
				}
			}

			g.text(this.font, "Click: toggle  |  Right-click: color / indicators", PX, lastY + 8, 0xFFCCCCCC, true);

			if (picker != null) extractPicker(g);
			if (indicatorsOpen) extractIndicators(g, mouseX, mouseY);
		}

		private int indicatorsH() {
			return 16 + Indicator.values().length * ROW_H + 4;
		}

		private void extractIndicators(GuiGraphicsExtractor g, int mouseX, int mouseY) {
			int x = popX(), y = indicatorsY, h = indicatorsH();
			g.fill(x, y, x + POP_W, y + h, 0xF0181818);
			g.outline(x, y, POP_W, h, 0xFF666666);
			g.text(this.font, "Chunk Finder indicators", x + 6, y + 4, 0xFFFFFFFF, true);
			Indicator[] inds = Indicator.values();
			for (int i = 0; i < inds.length; i++) {
				int ry = y + 16 + i * ROW_H;
				boolean hover = mouseX >= x && mouseX < x + POP_W && mouseY >= ry && mouseY < ry + ROW_H;
				if (hover) g.fill(x + 1, ry, x + POP_W - 1, ry + ROW_H, 0xE0383838);
				g.outline(x + 6, ry + 3, 10, 10, 0xFFAAAAAA);
				if (inds[i].enabled) g.fill(x + 8, ry + 5, x + 14, ry + 11, 0xFF55FF55);
				g.text(this.font, inds[i].label, x + 22, ry + 4, inds[i].enabled ? 0xFFFFFFFF : 0xFF888888, true);
			}
		}

		private void extractPicker(GuiGraphicsExtractor g) {
			int x = popX(), y = pickerY, h = popH();
			g.fill(x, y, x + POP_W, y + h, 0xF0181818);
			g.outline(x, y, POP_W, h, 0xFF666666);
			g.text(this.font, picker.label + " color", x + 6, y + 4, 0xFFFFFFFF, true);

			for (int i = 0; i < 4; i++) {
				int sy = sliderY(i);
				int tx = x + 8, tw = POP_W - 16;
				int value = (picker.argb >> SHIFTS[i]) & 0xFF;
				g.fill(tx, sy, tx + tw, sy + SLIDER_H, 0xFF3A3A3A);
				g.fill(tx, sy, tx + (int) (tw * value / 255.0), sy + SLIDER_H, channelColor(i));
				g.text(this.font, CHANNELS[i] + " " + value, tx + 4, sy + 3, 0xFFFFFFFF, true);
			}

			int py = sliderY(4);
			g.fill(x + 8, py, x + POP_W - 8, py + 12, picker.argb | 0xFF000000);
			g.outline(x + 8, py, POP_W - 16, 12, 0xFFFFFFFF);
			g.text(this.font, String.format("#%06X  alpha %d", picker.argb & 0xFFFFFF, (picker.argb >>> 24) & 0xFF), x + 8, py + 15, 0xFFAAAAAA, false);
		}

		private static int channelColor(int i) {
			return switch (i) {
				case 0 -> 0xFFCC3333;
				case 1 -> 0xFF33AA33;
				case 2 -> 0xFF3366CC;
				default -> 0xFF888888;
			};
		}

		private void setChannel(int i, double mouseX) {
			int tx = popX() + 8, tw = POP_W - 16;
			double t = Math.max(0, Math.min(1, (mouseX - tx) / tw));
			int value = (int) Math.round(t * 255);
			int shift = SHIFTS[i];
			picker.argb = (picker.argb & ~(0xFF << shift)) | (value << shift);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			double mx = event.x(), my = event.y();
			int button = event.button();

			// Chunk Finder indicator popup
			if (indicatorsOpen && mx >= popX() && mx < popX() + POP_W && my >= indicatorsY && my < indicatorsY + indicatorsH()) {
				int idx = (int) ((my - indicatorsY - 16) / ROW_H);
				Indicator[] inds = Indicator.values();
				if (my >= indicatorsY + 16 && idx >= 0 && idx < inds.length) {
					inds[idx].enabled = !inds[idx].enabled;
					ChunkFinder.invalidate();
					Config.save();
				}
				return true;
			}

			// Color picker sliders
			if (picker != null && mx >= popX() && mx < popX() + POP_W && my >= pickerY && my < pickerY + popH()) {
				for (int i = 0; i < 4; i++) {
					int sy = sliderY(i);
					if (my >= sy && my < sy + SLIDER_H) {
						dragging = i;
						setChannel(i, mx);
						return true;
					}
				}
				return true;
			}

			for (Slot slot : layout()) {
				int y = slot.y();
				int h = slot.row() == null ? HEADER_H : ROW_H;
				if (mx < PX || mx >= PX + W || my < y || my >= y + h) continue;

				if (slot.row() == null) {
					slot.section().collapsed = !slot.section().collapsed;
					picker = null;
					indicatorsOpen = false;
					return true;
				}

				Row r = slot.row();
				if (r.feature != null) {
					boolean onSwatch = mx >= PX + W - 24;
					if (r.feature == Feature.CHUNK_FINDER && button == 1 && !onSwatch) {
						// Right-click the Chunk Finder row: choose which indicators it looks for
						indicatorsOpen = !indicatorsOpen;
						indicatorsY = y;
						picker = null;
						return true;
					}
					if (button == 1 || onSwatch) {
						picker = (picker == r.feature) ? null : r.feature;
						pickerY = y;
						indicatorsOpen = false;
						return true;
					}
				}
				if (r.cycle != null) {
					r.cycle.accept(button == 1 ? -1 : 1);
				} else if (r.toggle != null && button == 0) {
					r.toggle.run();
				} else {
					return true;
				}
				Config.save();
				return true;
			}

			return super.mouseClicked(event, doubleClick);
		}

		@Override
		public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
			if (dragging >= 0 && picker != null) {
				setChannel(dragging, event.x());
				return true;
			}
			return super.mouseDragged(event, dx, dy);
		}

		@Override
		public boolean mouseReleased(MouseButtonEvent event) {
			if (dragging >= 0) {
				dragging = -1;
				Config.save();
				return true;
			}
			return super.mouseReleased(event);
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (HighlighterClient.OPEN_KEY.matches(event)) {
				this.onClose();
				return true;
			}
			return super.keyPressed(event);
		}
	}
}
