package com.blockhighlighter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
			Zoom.tick(client);
		});

		LevelRenderEvents.START_MAIN.register(ctx -> Zoom.frame(Minecraft.getInstance()));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Zoom.restore(client));

		HighlightRenderer.register();
	}

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

	public static final class Config {
		public static final int[] RANGES = {16, 32, 48, 64, 96, 200, 300, 400};
		public static final int[] ZOOM_LEVELS = {2, 3, 4, 6, 8, 10, 15, 20};
		public static boolean zoomEnabled = true;
		public static boolean zoomSmooth = true;
		public static boolean zoomLowerSens = true;
		public static int zoomIndex = 2;
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

	/** Draws filled, translucent boxes through walls. Based on Fabric's custom render pipeline docs for 26.2. */
	public static final class HighlightRenderer {
		private record BoxState(int x, int y, int z, float r, float g, float b, float a) { }

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
			List<BlockScanner.Hit> hits = BlockScanner.hits();
			if (hits.isEmpty() || Minecraft.getInstance().level == null) {
				boxes = List.of();
				return;
			}
			List<BoxState> out = new ArrayList<>(hits.size());
			for (BlockScanner.Hit hit : hits) {
				Feature f = hit.feature();
				if (!f.enabled) continue;
				int c = f.argb;
				out.add(new BoxState(hit.x(), hit.y(), hit.z(),
						((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f, ((c >>> 24) & 0xFF) / 255f));
			}
			boxes = out;
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
					filledBox(pose, builder, b.x(), b.y(), b.z(), b.x() + 1, b.y() + 1, b.z() + 1, b.r(), b.g(), b.b(), b.a());
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
		private int dragging = -1;

		public HighlightScreen() {
			super(Component.literal("Block Highlighter"));
			if (sections == null) {
				sections = buildSections();
			}
		}

		private static List<Section> buildSections() {
			List<Row> highlights = new ArrayList<>();
			for (Feature f : Feature.values()) {
				highlights.add(Row.feature(f));
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

			// Add more sections here later, e.g. new Section("Movement", List.of(...))
			List<Section> out = new ArrayList<>();
			out.add(new Section("Highlights", highlights));
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

			g.text(this.font, "Click: toggle/change  |  Right-click: color / back", PX, lastY + 8, 0xFFCCCCCC, true);

			if (picker != null) extractPicker(g);
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
					return true;
				}

				Row r = slot.row();
				if (r.feature != null) {
					boolean onSwatch = mx >= PX + W - 24;
					if (button == 1 || onSwatch) {
						picker = (picker == r.feature) ? null : r.feature;
						pickerY = y;
						return true;
					}
				}
				if (r.cycle != null) {
					r.cycle.accept(button == 1 ? -1 : 1);
				} else if (button == 0) {
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
