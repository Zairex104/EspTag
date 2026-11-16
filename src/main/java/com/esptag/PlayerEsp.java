package com.esptag;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Oyuncu ESP: tum cizim oyunun kendi dunya matrisleriyle ve ayni frame'in
 * tickDelta'siyla yapildigi icin hitbox ve nametag kaymaz.
 */
public final class PlayerEsp {
	public static boolean enabled = false;

	private static final int HEART_COLOR = 0xFFFF3B3B;
	private static final double MAX_DISTANCE = 256.0;

	private PlayerEsp() {}

	public static void render(WorldRenderContext context) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (!enabled || mc.world == null || mc.player == null) return;

		MatrixStack stack = context.matrixStack();
		if (stack == null) return;

		float delta = context.tickCounter().getTickDelta(false);
		Camera camera = context.camera();
		Vec3d cam = camera.getPos();

		// ---- 1) Hitbox cerceveleri (duvar arkasi, depth test kapali) ----
		RenderSystem.disableDepthTest();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.lineWidth(2.0f);
		RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);

		Matrix4f matrix = stack.peek().getPositionMatrix();
		BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
		boolean any = false;

		for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
			if (p == mc.player || !p.isAlive()) continue;
			if (p.squaredDistanceTo(mc.player) > MAX_DISTANCE * MAX_DISTANCE) continue;

			Vec3d pos = p.getLerpedPos(delta);
			Box bb = p.getBoundingBox().offset(pos.subtract(p.getPos()));
			int color = healthColor(p);

			float x1 = (float) (bb.minX - cam.x), y1 = (float) (bb.minY - cam.y), z1 = (float) (bb.minZ - cam.z);
			float x2 = (float) (bb.maxX - cam.x), y2 = (float) (bb.maxY - cam.y), z2 = (float) (bb.maxZ - cam.z);

			// alt yuz
			line(buf, matrix, x1, y1, z1, x2, y1, z1, color);
			line(buf, matrix, x2, y1, z1, x2, y1, z2, color);
			line(buf, matrix, x2, y1, z2, x1, y1, z2, color);
			line(buf, matrix, x1, y1, z2, x1, y1, z1, color);
			// ust yuz
			line(buf, matrix, x1, y2, z1, x2, y2, z1, color);
			line(buf, matrix, x2, y2, z1, x2, y2, z2, color);
			line(buf, matrix, x2, y2, z2, x1, y2, z2, color);
			line(buf, matrix, x1, y2, z2, x1, y2, z1, color);
			// dikey kenarlar
			line(buf, matrix, x1, y1, z1, x1, y2, z1, color);
			line(buf, matrix, x2, y1, z1, x2, y2, z1, color);
			line(buf, matrix, x2, y1, z2, x2, y2, z2, color);
			line(buf, matrix, x1, y1, z2, x1, y2, z2, color);
			any = true;
		}

		if (any) {
			BufferRenderer.drawWithGlobalProgram(buf.end());
		}
		RenderSystem.lineWidth(1.0f);

		// ---- 2) Nametag: isim + kalp + can (duvar arkasi) ----
		TextRenderer tr = mc.textRenderer;
		VertexConsumerProvider.Immediate consumers = mc.getBufferBuilders().getEntityVertexConsumers();

		for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
			if (p == mc.player || !p.isAlive()) continue;
			double distSq = p.squaredDistanceTo(mc.player);
			if (distSq > MAX_DISTANCE * MAX_DISTANCE) continue;

			Vec3d pos = p.getLerpedPos(delta);
			double top = pos.y + p.getBoundingBox().getLengthY() + 0.45;
			double dist = Math.sqrt(distSq);

			// 8 blok sonrasi ekranda sabit boyut
			float scale = 0.025f * (float) MathHelper.clamp(dist / 8.0, 1.0, 40.0);

			stack.push();
			stack.translate(pos.x - cam.x, top - cam.y, pos.z - cam.z);
			stack.multiply(camera.getRotation());
			stack.scale(-scale, -scale, scale);

			float hp = p.getHealth() + p.getAbsorptionAmount();
			String hpText = String.valueOf(Math.round(hp * 10f) / 10f).replace(".0", "");
			int hpColor = healthColor(p) & 0xFFFFFF;

			Text label = Text.literal(p.getName().getString())
					.setStyle(Style.EMPTY.withColor(0xFFFFFF))
					.append(Text.literal(" ❤").setStyle(Style.EMPTY.withColor(HEART_COLOR & 0xFFFFFF)))
					.append(Text.literal(" " + hpText).setStyle(Style.EMPTY.withColor(hpColor)));

			float x = -tr.getWidth(label) / 2f;
			tr.draw(label, x, 0f, 0xFFFFFFFF, true, stack.peek().getPositionMatrix(), consumers,
					TextRenderer.TextLayerType.SEE_THROUGH, 0x90000000, 0xF000F0);
			stack.pop();
		}
		consumers.draw();

		RenderSystem.enableDepthTest();
		RenderSystem.disableBlend();
	}

	private static void line(BufferBuilder buf, Matrix4f m, float x1, float y1, float z1,
							 float x2, float y2, float z2, int argb) {
		buf.vertex(m, x1, y1, z1).color(argb);
		buf.vertex(m, x2, y2, z2).color(argb);
	}

	private static int healthColor(AbstractClientPlayerEntity p) {
		float ratio = MathHelper.clamp(p.getHealth() / Math.max(1f, p.getMaxHealth()), 0f, 1f);
		if (ratio > 0.66f) return 0xFF55FF55;
		if (ratio > 0.33f) return 0xFFFFFF55;
		return 0xFFFF5555;
	}
}
