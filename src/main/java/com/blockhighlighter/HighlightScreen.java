package com.blockhighlighter;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The dropdown menu. Left click a row to toggle it. Right click a row (or click its swatch) to edit its color.
 * Press the open key again (or Esc) to close.
 */
public class HighlightScreen extends Screen {
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
