package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lunar-style HUD editor: drag any panel anywhere, drag the corner handle or use the mouse wheel / +/-
 * to scale it, eye to hide it, R to reset it. Positions snap to the screen edges and centre.
 */
final class EditorScreen extends CppScreen {
    private record Hit(int x, int y, int w, int h, Runnable r) {
        boolean in(double a, double b) {
            return a >= x && a < x + w && b >= y && b < y + h;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private Widgets.Widget sel;
    private int mode; // 0 none, 1 move, 2 scale
    private double offX, offY, startX, startY;

    EditorScreen() {
        super("cobblestone++ HUD editor");
        CppInput.ensure();
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        hits.clear();
        int gw = this.width, gh = this.height;
        double[] m = CppInput.gui();
        g.fill(0, 0, gw, gh, 0x58000000);
        g.fill(gw / 2, 0, gw / 2 + 1, gh, 0x24FFFFFF);
        g.fill(0, gh / 2, gw, gh / 2 + 1, 0x24FFFFFF);

        drag(m);
        Widgets.render(g, true);

        for (Widgets.Widget w : Widgets.all) {
            int[] b = Widgets.bounds(w);
            boolean hov = m[0] >= b[0] && m[0] < b[0] + b[2] && m[1] >= b[1] && m[1] < b[1] + b[3];
            int col = w == sel ? Ui.GAIN : hov ? 0xCCFFFFFF : w.on.getAsBoolean() ? 0x66FFFFFF : 0x77FF5555;
            Ui.border(g, b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, col);
        }

        if (sel != null) toolbar(g, m);

        // bottom bar
        String hint = "Drag a panel to move it  -  drag its corner or use the mouse wheel to scale  -  ESC to close";
        float hs = Math.min(1f, (gw - 10f) / Math.max(1, Ui.tw(hint)));
        var ps = g.pose();
        ps.pushMatrix();
        ps.translate((gw - Ui.tw(hint) * hs) / 2f, 5f);
        ps.scale(hs, hs);
        Ui.text(g, hint, 0, 0, Ui.DIM);
        ps.popMatrix();

        int bw = 52, by = gh - 20, bx = (gw - (3 * bw + 8)) / 2;
        button(g, m, bx, by, bw, 14, "DONE", Ui.ACCENT, () -> Scr.set(Minecraft.getInstance(), null));
        button(g, m, bx + bw + 4, by, bw, 14, "MENU", Ui.GOLD, () -> Scr.set(Minecraft.getInstance(), new MenuScreen()));
        button(g, m, bx + 2 * (bw + 4), by, bw, 14, "RESET ALL", 0xFFE5243B, () -> {
            for (Widgets.Widget w : Widgets.all) w.reset();
            Cfg.save();
        });
    }

    private void button(GuiGraphicsExtractor g, double[] m, int x, int y, int w, int h, String label, int col, Runnable r) {
        boolean hov = m[0] >= x && m[0] < x + w && m[1] >= y && m[1] < y + h;
        Ui.rr(g, x, y, w, h, hov ? Ui.lerp(0xF0201A28, col, 0.35f) : 0xF0201A28);
        Ui.border(g, x, y, w, h, Ui.alpha(col, hov ? 255 : 150));
        Ui.text(g, label, x + (w - Ui.tw(label)) / 2, y + (h - 8) / 2, hov ? 0xFFFFFFFF : Ui.TEXT);
        hits.add(new Hit(x, y, w, h, r));
    }

    private void toolbar(GuiGraphicsExtractor g, double[] m) {
        int[] b = Widgets.bounds(sel);
        int ty = b[1] >= 20 ? b[1] - 17 : b[1] + b[3] + 3;
        int x = Math.max(2, Math.min(this.width - 170, b[0]));
        final Widgets.Widget w = sel;

        button(g, m, x, ty, 14, 14, "-", Ui.GOLD, () -> {
            int[] bb = Widgets.bounds(w);
            Widgets.setScale(w, w.cfg.get().scale - 0.1f, bb[0], bb[1]);
        });
        String pct = String.format(Locale.ROOT, "%s %.0f%%", w.name, w.cfg.get().scale * 100f);
        int pw = Ui.tw(pct) + 10;
        Ui.rr(g, x + 16, ty, pw, 14, 0xF0201A28);
        Ui.text(g, pct, x + 21, ty + 3, Ui.TEXT);
        int x2 = x + 16 + pw + 2;
        button(g, m, x2, ty, 14, 14, "+", Ui.GOLD, () -> {
            int[] bb = Widgets.bounds(w);
            Widgets.setScale(w, w.cfg.get().scale + 0.1f, bb[0], bb[1]);
        });
        button(g, m, x2 + 16, ty, 26, 14, w.on.getAsBoolean() ? "ON" : "OFF", w.on.getAsBoolean() ? Ui.ACCENT : 0xFFFF5555,
                () -> w.setOn.accept(!w.on.getAsBoolean()));
        button(g, m, x2 + 44, ty, 14, 14, "R", 0xFFE5243B, w::reset);

        // scale handle (bottom-right corner)
        g.fill(b[0] + b[2] - 3, b[1] + b[3] - 3, b[0] + b[2] + 4, b[1] + b[3] + 4, Ui.GAIN);
    }

    // ------------------------------------------------------------------ dragging

    private void drag(double[] m) {
        if (sel == null || mode == 0) return;
        Cfg.W c = sel.cfg.get();
        int gw = this.width, gh = this.height;
        if (mode == 1) {
            float s = Widgets.eff(sel);
            double dx = gw - sel.w * s, dy = gh - sel.h * s;
            double x = m[0] - offX, y = m[1] - offY;
            double[] sx = {0, dx / 2, dx}, sy = {0, dy / 2, dy};
            for (double v : sx) if (Math.abs(x - v) < 5) x = v;
            for (double v : sy) if (Math.abs(y - v) < 5) y = v;
            c.fx = dx <= 1 ? 0 : Math.max(0, Math.min(1, x / dx));
            c.fy = dy <= 1 ? 0 : Math.max(0, Math.min(1, y / dy));
        } else if (mode == 2) {
            double unit = sel.w * (Cfg.I.textScale) * Widgets.auto();
            if (unit <= 0) return;
            float ns = (float) ((m[0] - startX) / unit);
            Widgets.setScale(sel, ns, (int) startX, (int) startY);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    void onMouse(int button, int action) {
        double[] m = CppInput.gui();
        if (action == GLFW.GLFW_RELEASE) {
            if (mode != 0) Cfg.save();
            mode = 0;
            return;
        }
        if (action != GLFW.GLFW_PRESS || button != 0) return;

        for (Hit h : hits) {
            if (h.in(m[0], m[1])) {
                h.r.run();
                Cfg.save();
                return;
            }
        }
        if (sel != null) {
            int[] b = Widgets.bounds(sel);
            int cx = b[0] + b[2], cy = b[1] + b[3];
            if (Math.abs(m[0] - cx) <= 6 && Math.abs(m[1] - cy) <= 6) {
                mode = 2;
                startX = b[0];
                startY = b[1];
                return;
            }
        }
        for (int i = Widgets.all.size() - 1; i >= 0; i--) {
            Widgets.Widget w = Widgets.all.get(i);
            int[] b = Widgets.bounds(w);
            if (m[0] >= b[0] && m[0] < b[0] + b[2] && m[1] >= b[1] && m[1] < b[1] + b[3]) {
                sel = w;
                mode = 1;
                offX = m[0] - b[0];
                offY = m[1] - b[1];
                return;
            }
        }
        sel = null;
    }

    @Override
    void onScroll(double dy) {
        double[] m = CppInput.gui();
        Widgets.Widget t = sel;
        if (t == null) {
            for (int i = Widgets.all.size() - 1; i >= 0; i--) {
                Widgets.Widget w = Widgets.all.get(i);
                int[] b = Widgets.bounds(w);
                if (m[0] >= b[0] && m[0] < b[0] + b[2] && m[1] >= b[1] && m[1] < b[1] + b[3]) {
                    t = w;
                    break;
                }
            }
        }
        if (t == null) return;
        int[] b = Widgets.bounds(t);
        float cur = t.cfg.get().scale;
        Widgets.setScale(t, dy > 0 ? cur * 1.06f : cur / 1.06f, b[0], b[1]);
        Cfg.save();
    }
}
