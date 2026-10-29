package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * cobblestone++ settings. Laid out on a fixed 380x240 canvas that is scaled down to fit when the game
 * window (or GUI scale) is small, so text and buttons shrink together with the menu.
 */
final class MenuScreen extends CppScreen {
    private record Hit(int x, int y, int w, int h, Runnable r) {
        boolean in(double a, double b) {
            return a >= x && a < x + w && b >= y && b < y + h;
        }
    }

    private interface Ctl {}

    private record Tog(String label, BooleanSupplier get, Consumer<Boolean> set) implements Ctl {}

    private record Cyc(String label, String[] opts, Supplier<String> get, Consumer<String> set) implements Ctl {}

    private record Step(String label, DoubleSupplier get, DoubleConsumer set, double min, double max, double step, double mul, String suf) implements Ctl {}

    private record Btn(String label, Supplier<String> text, Runnable run) implements Ctl {}

    private record Sld(String label, IntSupplier get, IntConsumer set) implements Ctl {}

    private static final String[] TABS = {"GENERAL", "BACKPACK", "MULTI", "GOALS", "TREASURE", "SHINY", "DATA"};
    private static final int LW = 380, LH = 350, ROW = 19;
    private static final int ON = 0xFF34D17A, OFF = 0xFF4B4054, KNOB = 0xFFF0EAF5;

    private final List<Hit> hits = new ArrayList<>();
    private final List<List<Ctl>> pages = new ArrayList<>();
    private int tab = 0;
    private String confirm = "";
    private float s = 1f;
    private int ox, oy;
    private Sld drag;
    private int dragX, dragW;

    MenuScreen() {
        super("cobblestone++");
        CppInput.ensure();
        build();
    }

    private void build() {
        List<Ctl> g = new ArrayList<>();
        g.add(new Tog("Backpack panel", () -> Cfg.I.backpackHud, v -> Cfg.I.backpackHud = v));
        g.add(new Tog("Multiplier panel", () -> Cfg.I.boostersHud, v -> Cfg.I.boostersHud = v));
        g.add(new Tog("Completion panel", () -> Cfg.I.completionHud, v -> Cfg.I.completionHud = v));
        g.add(new Btn("What to do next / track a part", () -> "OPEN PLANNER", () -> Scr.set(Minecraft.getInstance(), new PlanScreen())));
        g.add(new Tog("Crafting pins panel", () -> Cfg.I.pinsHud, v -> Cfg.I.pinsHud = v));
        g.add(new Tog("Milestones panel", () -> Cfg.I.milestonesHud, v -> Cfg.I.milestonesHud = v));
        g.add(new Tog("Treasure panel", () -> Cfg.I.treasureHud, v -> Cfg.I.treasureHud = v));
        g.add(new Tog("Shiny finder", () -> Cfg.I.shinyHud, v -> Cfg.I.shinyHud = v));
        g.add(new Btn("Move / scale panels", () -> "HUD EDITOR", () -> Scr.set(Minecraft.getInstance(), new EditorScreen())));
        g.add(new Step("Text size", () -> Cfg.I.textScale, v -> Cfg.I.textScale = v, 0.5, 2.0, 0.05, 100, "%"));
        g.add(new Sld("Panel background", () -> Cfg.I.panelOpacity, v -> Cfg.I.panelOpacity = v));
        g.add(new Btn("Panels: only the text", () -> Cfg.I.panelOpacity == 0 ? "TEXT ONLY" : "MAKE TRANSPARENT", () -> Cfg.I.panelOpacity = Cfg.I.panelOpacity == 0 ? 100 : 0));
        g.add(new Sld("Menu background", () -> Cfg.I.menuOpacity, v -> Cfg.I.menuOpacity = v));
        g.add(new Tog("Shrink with window size", () -> Cfg.I.autoShrink, v -> Cfg.I.autoShrink = v));
        g.add(new Step("Full size at GUI width", () -> Cfg.I.refWidth, v -> Cfg.I.refWidth = (int) Math.round(v), 240, 1280, 40, 1, "px"));
        pages.add(g);

        List<Ctl> b = new ArrayList<>();
        b.add(new Cyc("Show", new String[]{"recent", "all"}, () -> Cfg.I.backpackMode, v -> Cfg.I.backpackMode = v));
        b.add(new Tog("Sell value", () -> Cfg.I.backpackValue, v -> Cfg.I.backpackValue = v));
        b.add(new Step("Seconds on screen (recent)", () -> Cfg.I.backpackSecs, v -> Cfg.I.backpackSecs = (int) Math.round(v), 1, 120, 1, 1, "s"));
        b.add(new Step("Max rows", () -> Cfg.I.backpackMax, v -> Cfg.I.backpackMax = (int) Math.round(v), 1, 30, 1, 1, ""));
        b.add(new Btn("Session gains", () -> "RESET", Backpack::resetSession));
        pages.add(b);

        List<Ctl> mu = new ArrayList<>();
        mu.add(new Tog("Multiplier panel", () -> Cfg.I.boostersHud, v -> Cfg.I.boostersHud = v));
        mu.add(new Tog("Open /stats by itself (stops farming)", () -> Cfg.I.autoMenus, v -> Cfg.I.autoMenus = v));
        mu.add(new Tog("Log unread server messages (feed.log)", () -> Cfg.I.feedLog, v -> Cfg.I.feedLog = v));
        mu.add(new Tog("Auto-update from server", () -> Cfg.I.boostersAuto, v -> Cfg.I.boostersAuto = v));
        mu.add(new Step("Re-read at least every", () -> Cfg.I.boostersEverySecs, v -> Cfg.I.boostersEverySecs = (int) Math.round(v), 15, 600, 15, 1, "s"));
        mu.add(new Btn("Update now (/stats)", () -> "REFRESH", Boosters::refresh));
        pages.add(mu);

        List<Ctl> ms = new ArrayList<>();
        ms.add(new Tog("Milestones panel", () -> Cfg.I.milestonesHud, v -> Cfg.I.milestonesHud = v));
        ms.add(new Cyc("World", new String[]{"auto", "1", "2", "3", "4", "5", "6"},
                () -> Cfg.I.milestoneWorld == 0 ? "auto" : String.valueOf(Cfg.I.milestoneWorld),
                v -> Cfg.I.milestoneWorld = v.equals("auto") ? 0 : Integer.parseInt(v)));
        ms.add(new Tog("Show mob milestones", () -> Cfg.I.milestoneMobs, v -> Cfg.I.milestoneMobs = v));
        ms.add(new Tog("Ratio mode: count by learned menu ratio, not +1", () -> Cfg.I.milestoneRatio, v -> Cfg.I.milestoneRatio = v));
        ms.add(new Tog("Status line under the panel", () -> Cfg.I.milestoneStatus, v -> Cfg.I.milestoneStatus = v));
        ms.add(new Tog("Count up live (action bar / chat)", () -> Cfg.I.milestoneLive, v -> Cfg.I.milestoneLive = v));
        ms.add(new Tog("List the other worlds", () -> Cfg.I.milestoneOthers, v -> Cfg.I.milestoneOthers = v));
        ms.add(new Tog("Also show finished ones", () -> Cfg.I.milestoneDone, v -> Cfg.I.milestoneDone = v));
        ms.add(new Step("Max rows per section", () -> Cfg.I.milestoneRows, v -> Cfg.I.milestoneRows = (int) Math.round(v), 1, 30, 1, 1, ""));
        ms.add(new Btn("Learned milestones", () -> sure("ms", "FORGET"), () -> confirmThen("ms", Milestones::forget)));
        pages.add(ms);

        List<Ctl> t = new ArrayList<>();
        t.add(new Cyc("Goal", new String[]{"completion", "max"}, () -> Cfg.I.treasureMode, v -> Cfg.I.treasureMode = v));
        t.add(new Cyc("World", new String[]{"auto", "1", "2", "3", "4", "5", "6"},
                () -> Cfg.I.treasureWorld == 0 ? "auto" : String.valueOf(Cfg.I.treasureWorld),
                v -> Cfg.I.treasureWorld = v.equals("auto") ? 0 : Integer.parseInt(v)));
        t.add(new Tog("List the other worlds", () -> Cfg.I.treasureOthers, v -> Cfg.I.treasureOthers = v));
        t.add(new Step("Max missing rows", () -> Cfg.I.treasureRows, v -> Cfg.I.treasureRows = (int) Math.round(v), 1, 30, 1, 1, ""));
        t.add(new Btn("Ask server (/treasures)", () -> "REFRESH", () -> Treasure.refresh(Minecraft.getInstance())));
        t.add(new Btn("Learned treasure data", () -> sure("treasure", "FORGET"), () -> confirmThen("treasure", Treasure::forget)));
        pages.add(t);

        List<Ctl> sh = new ArrayList<>();
        sh.add(new Tog("Track echo (rainbow)", () -> Cfg.I.shinyEcho, v -> Cfg.I.shinyEcho = v));
        sh.add(new Tog("Track rush (yellow)", () -> Cfg.I.shinyRush, v -> Cfg.I.shinyRush = v));
        sh.add(new Tog("Rush needs its rings", () -> Cfg.I.shinyNeedRings, v -> Cfg.I.shinyNeedRings = v));
        sh.add(new Step("Ring pieces needed", () -> Cfg.I.shinyRingMin, v -> Cfg.I.shinyRingMin = (int) Math.round(v), 1, 40, 1, 1, ""));
        sh.add(new Tog("Steady blue counts as echo", () -> Cfg.I.shinyBlueEcho, v -> Cfg.I.shinyBlueEcho = v));
        sh.add(new Step("Search range", () -> Cfg.I.shinyRange, v -> Cfg.I.shinyRange = (int) Math.round(v), 16, 512, 16, 1, " blocks"));
        pages.add(sh);

        List<Ctl> d = new ArrayList<>();
        d.add(new Btn("Session gains", () -> "RESET", Backpack::resetSession));
        d.add(new Btn("Backpack data", () -> sure("bp", "WIPE"), () -> confirmThen("bp", Backpack::wipe)));
        d.add(new Btn("Learned recipes", () -> sure("recipes", "FORGET"), () -> confirmThen("recipes", Pins::forget)));
        d.add(new Btn("Milestone data", () -> sure("ms2", "FORGET"), () -> confirmThen("ms2", Milestones::forget)));
        d.add(new Btn("Treasure data", () -> sure("treasure2", "FORGET"), () -> confirmThen("treasure2", Treasure::forget)));
        d.add(new Btn("All panel positions", () -> sure("layout", "RESET"), () -> confirmThen("layout", () -> {
            for (Widgets.Widget w : Widgets.all) w.reset();
        })));
        pages.add(d);
    }

    private String sure(String key, String label) {
        return confirm.equals(key) ? "SURE?" : label;
    }

    private void confirmThen(String key, Runnable r) {
        if (confirm.equals(key)) {
            confirm = "";
            r.run();
        } else {
            confirm = key;
        }
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        hits.clear();
        if (drag != null) {
            if (GLFW.glfwGetMouseButton(CppInput.window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS) {
                double[] dm = CppInput.gui();
                drag.set().accept((int) Math.max(0, Math.min(100, Math.round((((dm[0] - ox) / s) - dragX) / dragW * 100.0))));
            } else {
                drag = null;
                Cfg.save();
            }
        }
        g.fill(0, 0, this.width, this.height, ma(0xA0050308));
        s = Math.max(0.35f, Math.min(1f, Math.min((this.width - 8f) / LW, (this.height - 8f) / LH)));
        ox = Math.round((this.width - LW * s) / 2f);
        oy = Math.round((this.height - LH * s) / 2f);
        double[] mm = CppInput.gui();
        double lx = (mm[0] - ox) / s, ly = (mm[1] - oy) / s;
        long now = System.currentTimeMillis();

        var ps = g.pose();
        ps.pushMatrix();
        ps.translate((float) ox, (float) oy);
        ps.scale(s, s);

        Ui.rr(g, 0, 0, LW, LH, ma(0xF2140F19));
        Ui.border(g, 0, 0, LW, LH, 0xFF392C43);
        for (int i = 0; i < LW; i += 2) {
            float wave = (float) (0.5 + 0.5 * Math.sin(now / 500.0 - i * 0.03));
            g.fill(i, 0, Math.min(i + 2, LW), 2, Ui.lerp(Ui.GOLD, Ui.ACCENT, wave));
        }
        Ui.text(g, "cobblestone++", 12, 9, Ui.TEXT);
        String ver = "v0.9";
        Ui.text(g, ver, LW - 12 - Ui.tw(ver), 9, Ui.DIM);

        // tabs
        int tw = (LW - 24) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            int x = 12 + i * tw;
            boolean act = i == tab;
            boolean hov = lx >= x && lx < x + tw - 3 && ly >= 24 && ly < 40;
            Ui.rr(g, x, 24, tw - 3, 16, ma(act ? 0xFF2B2234 : hov ? 0xFF241C2C : 0xFF1A1420));
            if (act) g.fill(x, 38, x + tw - 3, 40, Ui.GOLD);
            Ui.text(g, TABS[i], x + (tw - 3 - Ui.tw(TABS[i])) / 2, 28, act ? Ui.TEXT : Ui.DIM);
            final int idx = i;
            hits.add(new Hit(x, 24, tw - 3, 16, () -> {
                tab = idx;
                confirm = "";
            }));
        }

        // controls
        List<Ctl> page = pages.get(tab);
        int y = 48;
        for (Ctl c : page) {
            boolean hovRow = ly >= y && ly < y + ROW - 2;
            Ui.rr(g, 12, y, LW - 24, ROW - 2, ma(hovRow ? 0xFF241C2C : 0xFF1A1420));
            String label = c instanceof Tog t ? t.label() : c instanceof Cyc cy ? cy.label() : c instanceof Step st ? st.label() : c instanceof Sld sl ? sl.label() : ((Btn) c).label();
            Ui.text(g, label, 20, y + 5, Ui.TEXT);
            int right = LW - 20;
            if (c instanceof Tog t) {
                boolean on = t.get().getAsBoolean();
                int bx = right - 30, by = y + 2;
                Ui.rr(g, bx, by, 30, 12, on ? ON : OFF);
                g.fill(on ? bx + 19 : bx + 2, by + 2, on ? bx + 28 : bx + 11, by + 10, KNOB);
                hits.add(new Hit(bx, by, 30, 12, () -> t.set().accept(!on)));
            } else if (c instanceof Cyc cy) {
                String cur = cy.get().get();
                int bw = 88, bx = right - bw, by = y + 1;
                Ui.rr(g, bx, by, bw, 14, 0xFF2B2234);
                Ui.border(g, bx, by, bw, 14, Ui.alpha(Ui.GOLD, 140));
                Ui.text(g, cur.toUpperCase(Locale.ROOT), bx + (bw - Ui.tw(cur.toUpperCase(Locale.ROOT))) / 2, by + 3, Ui.GOLD);
                hits.add(new Hit(bx, by, bw, 14, () -> {
                    int i = 0;
                    for (int k = 0; k < cy.opts().length; k++) if (cy.opts()[k].equals(cur)) i = k;
                    cy.set().accept(cy.opts()[(i + 1) % cy.opts().length]);
                }));
            } else if (c instanceof Step st) {
                int vw = 66, bx = right - (vw + 32), by = y + 1;
                Ui.rr(g, bx, by, 14, 14, 0xFF2B2234);
                Ui.text(g, "-", bx + 5, by + 3, Ui.GOLD);
                Ui.rr(g, bx + 16, by, vw, 14, 0xFF140F19);
                String v = String.format(Locale.ROOT, "%.0f%s", st.get().getAsDouble() * st.mul(), st.suf());
                Ui.text(g, v, bx + 16 + (vw - Ui.tw(v)) / 2, by + 3, Ui.TEXT);
                Ui.rr(g, bx + 18 + vw, by, 14, 14, 0xFF2B2234);
                Ui.text(g, "+", bx + 18 + vw + 4, by + 3, Ui.GOLD);
                hits.add(new Hit(bx, by, 14, 14, () -> bump(st, -1)));
                hits.add(new Hit(bx + 18 + vw, by, 14, 14, () -> bump(st, 1)));
            } else if (c instanceof Sld sl) {
                int bw = 120, bx = right - bw, by = y + 5;
                int val = Math.max(0, Math.min(100, sl.get().getAsInt()));
                String vt = val + "%";
                Ui.text(g, vt, bx - 8 - Ui.tw(vt), y + 5, Ui.GOLD);
                Ui.rr(g, bx, by, bw, 6, 0xFF2B2234);
                Ui.rr(g, bx, by, Math.max(2, bw * val / 100), 6, Ui.alpha(Ui.GOLD, 200));
                int kx = bx + bw * val / 100;
                g.fill(kx - 1, by - 2, kx + 2, by + 8, KNOB);
                final int fbx = bx, fbw = bw;
                hits.add(new Hit(bx - 4, y, bw + 8, ROW - 2, () -> {
                    drag = sl;
                    dragX = fbx;
                    dragW = fbw;
                    double[] pm = CppInput.gui();
                    sl.set().accept((int) Math.max(0, Math.min(100, Math.round((((pm[0] - ox) / s) - fbx) / fbw * 100.0))));
                }));
            } else if (c instanceof Btn b) {
                String txt = b.text().get();
                int bw = 88, bx = right - bw, by = y + 1;
                boolean hot = txt.equals("SURE?");
                Ui.rr(g, bx, by, bw, 14, hot ? 0xFF7A1B28 : 0xFF2B2234);
                Ui.border(g, bx, by, bw, 14, Ui.alpha(hot ? 0xFFE5243B : Ui.ACCENT, 160));
                Ui.text(g, txt, bx + (bw - Ui.tw(txt)) / 2, by + 3, hot ? 0xFFFFFFFF : Ui.ACCENT);
                hits.add(new Hit(bx, by, bw, 14, b.run()));
            }
            y += ROW;
        }

        Ui.text(g, "ESC closes  -  every panel can be moved and scaled in the HUD editor", 12, LH - 14, Ui.DIM);
        ps.popMatrix();
    }

    /** Scales the alpha of a menu colour with the menu opacity setting. */
    private static int ma(int argb) {
        int a = (int) Math.round(((argb >>> 24) & 255) * Math.max(0, Math.min(100, Cfg.I.menuOpacity)) / 100.0);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static void bump(Step st, int dir) {
        double v = st.get().getAsDouble() + dir * st.step();
        v = Math.round(v / st.step()) * st.step();
        st.set().accept(Math.max(st.min(), Math.min(st.max(), v)));
    }

    // ------------------------------------------------------------------ input

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
    }
}
