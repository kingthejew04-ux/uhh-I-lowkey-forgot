package com.blockhighlighter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
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

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.event.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/** Draws filled, translucent boxes through walls. Based on Fabric's custom render pipeline docs for 26.2. */
public final class HighlightRenderer {
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
