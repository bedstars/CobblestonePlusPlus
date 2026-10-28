package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Every on-screen panel is a Widget: it draws itself at (0,0) and reports its size; this class moves
 * and scales it. Position is stored as a fraction of the free space (so layouts survive window resizes)
 * and the size is  panel scale * global text size * auto-shrink.
 */
public final class Widgets {
    private Widgets() {}

    public static final class Widget {
        public final String id, name;
        final Supplier<Cfg.W> cfg;
        final BooleanSupplier on;
        final Consumer<Boolean> setOn;
        final Function<GuiGraphicsExtractor, int[]> draw;
        final double dfx, dfy;
        int w = 110, h = 40; // last drawn size (panel units)

        Widget(String id, String name, Supplier<Cfg.W> cfg, BooleanSupplier on, Consumer<Boolean> setOn,
               Function<GuiGraphicsExtractor, int[]> draw, double dfx, double dfy) {
            this.id = id;
            this.name = name;
            this.cfg = cfg;
            this.on = on;
            this.setOn = setOn;
            this.draw = draw;
            this.dfx = dfx;
            this.dfy = dfy;
        }

        void reset() {
            Cfg.W c = cfg.get();
            c.fx = dfx;
            c.fy = dfy;
            c.scale = 1f;
        }
    }

    public static final List<Widget> all = new ArrayList<>();

    public static void init() {
        all.add(new Widget("backpack", "Backpack", () -> Cfg.I.backpack, () -> Cfg.I.backpackHud, v -> Cfg.I.backpackHud = v, BackpackHud::draw, 0.0, 0.15));
        all.add(new Widget("boosters", "Multipliers", () -> Cfg.I.boosters, () -> Cfg.I.boostersHud, v -> Cfg.I.boostersHud = v, BoostersHud::draw, 1.0, 0.10));
        all.add(new Widget("pins", "Crafting pins", () -> Cfg.I.pins, () -> Cfg.I.pinsHud, v -> Cfg.I.pinsHud = v, PinsHud::draw, 0.0, 0.55));
        all.add(new Widget("treasure", "Treasures", () -> Cfg.I.treasure, () -> Cfg.I.treasureHud, v -> Cfg.I.treasureHud = v, TreasureHud::draw, 1.0, 0.45));
        all.add(new Widget("shiny", "Shiny finder", () -> Cfg.I.shiny, () -> Cfg.I.shinyHud, v -> Cfg.I.shinyHud = v, ShinyHud::draw, 0.5, 0.0));
    }

    /** Shrinks everything when the GUI area is smaller than the reference size. */
    public static float auto() {
        if (!Cfg.I.autoShrink) return 1f;
        Minecraft mc = Minecraft.getInstance();
        int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
        float ref = Math.max(160, Cfg.I.refWidth);
        float f = Math.min(gw / ref, gh / (ref * 0.5625f));
        return Math.max(0.45f, Math.min(1f, f));
    }

    public static float eff(Widget w) {
        return Math.max(0.2f, w.cfg.get().scale * (float) Cfg.I.textScale * auto());
    }

    /** {x, y, w, h} in GUI units. */
    public static int[] bounds(Widget w) {
        Minecraft mc = Minecraft.getInstance();
        int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
        float s = eff(w);
        double bw = w.w * s, bh = w.h * s;
        Cfg.W c = w.cfg.get();
        double x = Math.max(0, c.fx * (gw - bw));
        double y = Math.max(0, c.fy * (gh - bh));
        return new int[]{(int) x, (int) y, (int) Math.ceil(bw), (int) Math.ceil(bh)};
    }

    /** Change a panel's scale while keeping its top-left corner where it is. */
    public static void setScale(Widget w, float ns, int keepX, int keepY) {
        Cfg.W c = w.cfg.get();
        c.scale = Math.max(0.4f, Math.min(4f, ns));
        Minecraft mc = Minecraft.getInstance();
        int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
        float s = eff(w);
        double dx = gw - w.w * s, dy = gh - w.h * s;
        c.fx = dx <= 1 ? 0 : Math.max(0, Math.min(1, keepX / dx));
        c.fy = dy <= 1 ? 0 : Math.max(0, Math.min(1, keepY / dy));
    }

    public static void render(GuiGraphicsExtractor g, boolean editor) {
        Minecraft mc = Minecraft.getInstance();
        if (!editor && Scr.get(mc) instanceof EditorScreen) return;
        for (Widget w : all) drawOne(g, w, editor);
    }

    private static void drawOne(GuiGraphicsExtractor g, Widget w, boolean editor) {
        int[] b = bounds(w);
        float s = eff(w);
        var ps = g.pose();
        ps.pushMatrix();
        ps.translate((float) b[0], (float) b[1]);
        ps.scale(s, s);
        int[] sz = null;
        try {
            sz = w.draw.apply(g);
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("panel " + w.id + " failed to draw", t);
        }
        if (sz == null && editor) sz = placeholder(g, w);
        ps.popMatrix();
        if (sz != null) {
            w.w = Math.max(20, sz[0]);
            w.h = Math.max(12, sz[1]);
        }
    }

    private static int[] placeholder(GuiGraphicsExtractor g, Widget w) {
        String a = w.name.toUpperCase();
        String b = w.on.getAsBoolean() ? "no data yet" : "turned off";
        int width = Math.max(Ui.tw(a), Ui.tw(b)) + 16;
        int height = 28;
        Ui.rr(g, 0, 0, width, height, Ui.PANEL);
        g.fill(0, 0, 2, height, w.on.getAsBoolean() ? Ui.ACCENT : 0xFF4B4054);
        Ui.text(g, a, 7, 4, w.on.getAsBoolean() ? Ui.TEXT : Ui.DIM);
        Ui.text(g, b, 7, 15, Ui.DIM);
        return new int[]{width, height};
    }
}
