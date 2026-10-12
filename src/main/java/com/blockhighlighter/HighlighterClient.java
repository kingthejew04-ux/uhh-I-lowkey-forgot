package com.blockhighlighter;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
	public static class HighlightScreen extends Screen {
		private static final int PX = 8, PY = 8, W = 190, HEADER_H = 16, ROW_H = 16;
		private static final int POP_W = 150, SLIDER_H = 14;
		private static final String[] CHANNELS = {"R", "G", "B", "A"};
		private static final int[] SHIFTS = {16, 8, 0, 24};

		private static boolean collapsed = false;

		private Feature picker = null;
		private int dragging = -1;

		public HighlightScreen() {
			super(Component.literal("Block Highlighter"));
		}

		@Override
		public boolean isPauseScreen() {
			return false; // keep the world running so you can see highlight changes live
		}

		@Override
		public void onClose() {
			Config.save();
			super.onClose();
		}

		private int rowY(int i) {
			return PY + HEADER_H + i * ROW_H;
		}

		private int popX() {
			return PX + W + 6;
		}

		private int popY() {
			int idx = picker == null ? 0 : picker.ordinal();
			return rowY(idx);
		}

		private int sliderY(int i) {
			return popY() + 16 + i * (SLIDER_H + 4);
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
			// No super call: we deliberately skip the dimmed/blurred background so you can see the world.
			Feature[] features = Feature.values();

			g.fill(PX, PY, PX + W, PY + HEADER_H, 0xE0181818);
			g.text(this.font, (collapsed ? "[+] " : "[-] ") + "Block Highlighter", PX + 6, PY + 4, 0xFFFFFFFF, true);

			if (collapsed) return;

			for (int i = 0; i < features.length; i++) {
				Feature f = features[i];
				int ry = rowY(i);
				boolean hover = mouseX >= PX && mouseX < PX + W && mouseY >= ry && mouseY < ry + ROW_H;
				g.fill(PX, ry, PX + W, ry + ROW_H, hover ? 0xE0383838 : 0xE0262626);

				g.outline(PX + 5, ry + 3, 10, 10, 0xFFAAAAAA);
				if (f.enabled) g.fill(PX + 7, ry + 5, PX + 13, ry + 11, 0xFF55FF55);

				g.text(this.font, f.label, PX + 20, ry + 4, f.enabled ? 0xFFFFFFFF : 0xFF888888, true);

				g.fill(PX + W - 24, ry + 3, PX + W - 6, ry + 13, f.argb | 0xFF000000);
				g.outline(PX + W - 24, ry + 3, 18, 10, picker == f ? 0xFFFFFF55 : 0xFFFFFFFF);
			}

			int ry = rowY(features.length);
			g.fill(PX, ry, PX + W, ry + ROW_H, 0xE0262626);
			g.text(this.font, "Range: " + Config.range + " blocks", PX + 6, ry + 4, 0xFFDDDDDD, true);
			g.text(this.font, "Left/right click", PX + W - 6 - this.font.width("Left/right click"), ry + 4, 0xFF777777, false);

			g.text(this.font, "Click: toggle  |  Right-click: color", PX, ry + ROW_H + 4, 0xFFCCCCCC, true);

			if (picker != null) extractPicker(g);
		}

		private void extractPicker(GuiGraphicsExtractor g) {
			int x = popX(), y = popY();
			int h = 16 + 4 * (SLIDER_H + 4) + 30;
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

			int py = sliderY(4) + 0;
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
			Feature[] features = Feature.values();

			// Color picker sliders
			if (picker != null && mx >= popX() && mx < popX() + POP_W) {
				for (int i = 0; i < 4; i++) {
					int sy = sliderY(i);
					if (my >= sy && my < sy + SLIDER_H) {
						dragging = i;
						setChannel(i, mx);
						return true;
					}
				}
				if (my >= popY() && my < popY() + 16 + 4 * (SLIDER_H + 4) + 30) return true;
			}

			// Header: collapse / expand
			if (mx >= PX && mx < PX + W && my >= PY && my < PY + HEADER_H) {
				collapsed = !collapsed;
				if (collapsed) picker = null;
				return true;
			}
			if (collapsed) return super.mouseClicked(event, doubleClick);

			// Feature rows
			for (int i = 0; i < features.length; i++) {
				int ry = rowY(i);
				if (mx >= PX && mx < PX + W && my >= ry && my < ry + ROW_H) {
					Feature f = features[i];
					boolean onSwatch = mx >= PX + W - 24;
					if (button == 1 || onSwatch) {
						picker = (picker == f) ? null : f;
					} else if (button == 0) {
						f.enabled = !f.enabled;
						BlockScanner.requestRescan();
						Config.save();
					}
					return true;
				}
			}

			// Range row
			int ry = rowY(features.length);
			if (mx >= PX && mx < PX + W && my >= ry && my < ry + ROW_H) {
				int idx = 0;
				for (int i = 0; i < Config.RANGES.length; i++) {
					if (Config.RANGES[i] == Config.range) idx = i;
				}
				int step = button == 1 ? -1 : 1;
				idx = Math.floorMod(idx + step, Config.RANGES.length);
				Config.range = Config.RANGES[idx];
				BlockScanner.requestRescan();
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
