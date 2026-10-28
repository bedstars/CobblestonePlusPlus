package dev.cobblestoneplus;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * "What to do next": every progression part with its progress, least finished first. Click one to track it on
 * screen; click it again (or GLOBAL) to track everything. Data comes from /stats, read when you open it yourself.
 */
final class PlanScreen extends CppScreen {
    private record Hit(int x, int y, int w, int h, Runnable r) {
        boolean in(double a, double b) {
            return a >= x && a < x + w && b >= y && b < y + h;
        }
    }

    private static final int LW = 380, LH = 312, ROWH = 14, TOP = 74, VISIBLE = 14;

    private final List<Hit> hits = new ArrayList<>();
    private int scroll;
    private float s = 1f;
    private int ox, oy;

    PlanScreen() {
        super("cobblestone++ planner");
        CppInput.ensure();
    }

    private static int ma(int argb) {
        int a = (int) Math.round(((argb >>> 24) & 255) * Math.max(0, Math.min(100, Cfg.I.menuOpacity)) / 100.0);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        hits.clear();
        g.fill(0, 0, this.width, this.height, ma(0xA0050308));
        s = Math.max(0.35f, Math.min(1f, Math.min((this.width - 8f) / LW, (this.height - 8f) / LH)));
        ox = Math.round((this.width - LW * s) / 2f);
        oy = Math.round((this.height - LH * s) / 2f);
        double[] mm = CppInput.gui();
        double lx = (mm[0] - ox) / s, ly = (mm[1] - oy) / s;

        var ps = g.pose();
        ps.pushMatrix();
        ps.translate((float) ox, (float) oy);
        ps.scale(s, s);
        Ui.rr(g, 0, 0, LW, LH, ma(0xF2140F19));
        Ui.border(g, 0, 0, LW, LH, 0xFF392C43);
        Ui.text(g, "WHAT TO DO NEXT", 12, 9, Ui.TEXT);

        Completion.Data d = Completion.data;
        String pct = d.percent >= 0 ? String.format(Locale.ROOT, "%.1f%%", d.percent) : "?";
        Ui.text(g, pct, LW - 12 - Ui.tw(pct), 9, CompletionHud.pctColor(Math.max(0, d.percent)));
        g.fill(12, 22, LW - 12, 26, 0xFF2A2230);
        g.fill(12, 22, 12 + (int) ((LW - 24) * Math.max(0, Math.min(100, d.percent)) / 100.0), 26, CompletionHud.pctColor(Math.max(0, d.percent)));

        if (!Completion.known()) {
            Ui.text(g, "No data yet. Open /stats once (the Completion item) - it is", 12, 44, Ui.GOLD);
            Ui.text(g, "remembered. Nothing is ever opened for you.", 12, 56, Ui.GOLD);
            ps.popMatrix();
            return;
        }

        boolean globalOn = Cfg.I.focus.isEmpty();
        boolean hovG = lx >= 12 && lx < 132 && ly >= 32 && ly < 48;
        Ui.rr(g, 12, 32, 120, 16, ma(globalOn ? 0xFF2B2234 : hovG ? 0xFF241C2C : 0xFF1A1420));
        if (globalOn) g.fill(12, 46, 132, 48, Ui.GOLD);
        Ui.text(g, "GLOBAL (everything)", 18, 36, globalOn ? Ui.TEXT : Ui.DIM);
        hits.add(new Hit(12, 32, 120, 16, () -> Cfg.I.focus = ""));
        Ui.text(g, "Click a part to track it on screen", 140, 36, Ui.DIM);

        List<Completion.Cat> cats = new ArrayList<>(d.cats);
        cats.sort(Comparator.comparingInt((Completion.Cat c) -> c.done() ? 1 : 0).thenComparingDouble(Completion.Cat::pct));
        scroll = Math.max(0, Math.min(Math.max(0, cats.size() - VISIBLE), scroll));

        Ui.text(g, "PART", 18, TOP - 12, Ui.DIM);
        Ui.text(g, "PROGRESS", 230, TOP - 12, Ui.DIM);
        Completion.Cat hint = null;
        for (int i = 0; i < VISIBLE && i + scroll < cats.size(); i++) {
            Completion.Cat c = cats.get(i + scroll);
            int y = TOP + i * ROWH;
            boolean sel = c.name.equalsIgnoreCase(Cfg.I.focus);
            boolean hov = lx >= 12 && lx < LW - 12 && ly >= y && ly < y + ROWH - 1;
            if (hov) hint = c;
            Ui.rr(g, 12, y, LW - 24, ROWH - 1, ma(sel ? 0xFF2B2234 : hov ? 0xFF241C2C : 0xFF1A1420));
            if (sel) g.fill(12, y, 14, y + ROWH - 1, Ui.GOLD);
            Ui.text(g, c.name, 18, y + 3, c.done() ? 0xFF55E58A : Ui.TEXT);
            g.fill(230, y + 5, 300, y + 8, 0xFF2A2230);
            g.fill(230, y + 5, 230 + (int) (70 * c.pct() / 100.0), y + 8, CompletionHud.pctColor(c.pct()));
            String v = Nums.fmt(c.cur) + "/" + Nums.fmt(c.max);
            Ui.text(g, v, LW - 18 - Ui.tw(v), y + 3, Ui.DIM);
            final String nm = c.name;
            hits.add(new Hit(12, y, LW - 24, ROWH - 1, () -> Cfg.I.focus = nm.equalsIgnoreCase(Cfg.I.focus) ? "" : nm));
        }
        if (hint == null && !Cfg.I.focus.isEmpty()) hint = Completion.find(Cfg.I.focus);
        if (hint != null) {
            int ly2 = TOP + VISIBLE * ROWH + 8;
            Ui.text(g, hint.name + (hint.done() ? "  - finished" : "  - " + Nums.fmt(hint.max - hint.cur) + " left"), 12, ly2, Ui.GOLD);
            int k = 0;
            for (String line : CompletionHud.wrap(Completion.hint(hint.name), 62)) Ui.text(g, line, 12, ly2 + 11 + 11 * k++, Ui.DIM);
        }
        Ui.text(g, "Wheel scrolls  -  ESC closes", 12, LH - 14, Ui.DIM);
        ps.popMatrix();
    }

    @Override
    void onMouse(int button, int action) {
        if (action != GLFW.GLFW_PRESS || button != 0) return;
        double[] m = CppInput.gui();
        double lx = (m[0] - ox) / s, ly = (m[1] - oy) / s;
        for (Hit h : new ArrayList<>(hits)) {
            if (h.in(lx, ly)) {
                h.r.run();
                Cfg.save();
                return;
            }
        }
    }

    @Override
    void onScroll(double dy) {
        scroll -= (int) Math.signum(dy) * 2;
    }
}
