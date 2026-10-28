package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Tiny drawing helpers built only on fill() and text(). */
final class Ui {
    private Ui() {}

    static final int PANEL = 0xC0100C14, LINE = 0xFF3A3040, ACCENT = 0xFF58D68D, GOLD = 0xFFFFC857,
            GEM = 0xFFD070FF, TEXT = 0xFFF0EAF5, DIM = 0xFF9A8FA6, GAIN = 0xFF6BFF9C;

    static int tw(String s) {
        return Minecraft.getInstance().font.width(s);
    }

    static void text(GuiGraphicsExtractor g, String s, int x, int y, int c) {
        g.text(Minecraft.getInstance().font, s, x, y, c, true);
    }

    /** The panel background with the opacity chosen in the settings (0 = text only). */
    static int panel() {
        int a = (int) Math.round(0xC0 * Math.max(0, Math.min(100, Cfg.I.panelOpacity)) / 100.0);
        return alpha(PANEL, a);
    }

    /** The coloured stripe at the left of a panel; hidden in text-only mode. */
    static void stripe(GuiGraphicsExtractor g, int x, int y, int h, int c) {
        if (Cfg.I.panelOpacity <= 4) return;
        g.fill(x, y, x + 2, y + h, c);
    }

    static void rr(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        if (c == PANEL) {
            c = panel();
            if ((c >>> 24) == 0) return;
        }
        g.fill(x + 1, y, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + w, y + h - 1, c);
    }

    static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y + 1, x + 1, y + h - 1, c);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }

    static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int A = (int) (((a >>> 24) & 255) * (1 - t) + ((b >>> 24) & 255) * t);
        int R = (int) (((a >>> 16) & 255) * (1 - t) + ((b >>> 16) & 255) * t);
        int G = (int) (((a >>> 8) & 255) * (1 - t) + ((b >>> 8) & 255) * t);
        int B = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (A << 24) | (R << 16) | (G << 8) | B;
    }

    static int alpha(int c, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (c & 0xFFFFFF);
    }
}
